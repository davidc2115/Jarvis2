package com.jarvis2.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis2.app.ai.AiEngineManager
import com.jarvis2.app.ai.CloudAiClient
import com.jarvis2.app.ai.CommandRouter
import com.jarvis2.app.ai.CommandResult
import com.jarvis2.app.ai.EngineInfo
import com.jarvis2.app.ai.JarvisCommandParser
import com.jarvis2.app.ai.MemoryStore
import com.jarvis2.app.ai.TtsController
import com.jarvis2.app.ai.Turn
import com.jarvis2.app.ai.VoiceModeController
import com.jarvis2.app.ai.VoiceState
import com.jarvis2.app.ai.WebSearchTool
import com.jarvis2.app.data.SettingsDataStore
import com.jarvis2.app.ui.settings.TTS_ENABLED
import com.jarvis2.app.data.db.ChatDao
import com.jarvis2.app.data.db.ChatMessageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiMessage(val id: Long, val role: Turn.Role, val text: String, val timestamp: Long)

data class ChatUiState(
    val messages: List<ChatUiMessage> = emptyList(),
    val engine: EngineInfo? = null,
    val isThinking: Boolean = false,
    val pendingWebSearchQuery: String? = null, // set when the model admits it doesn't know
    // Provenance de la DERNIERE reponse effectivement affichee : "Groq",
    // "Gemini cloud", ou null si c'est le moteur IA local (voir `engine`
    // ci-dessus, deja affiche dans le header -- voir ChatScreen.kt) qui a
    // repondu. Ajoute suite au signalement "toujours pas de choix avec Groq
    // en IA principal" (voir CloudAiClient.CloudReply) : avant ça, rien
    // dans l'UI ne confirmait que Groq etait bien utilise en priorite.
    val lastReplySource: String? = null,
    // Ordre de priorite actuel entre cloud et local (voir AI_PRIORITY_MODE
    // dans CloudAiClient.kt) -- charge au demarrage et rafraichi apres
    // chaque commande geree (voir refreshPresentationPrefs), puisque
    // l'utilisateur peut le changer en direct depuis le chat ("priorite ia
    // locale" / "remets le cloud en priorite"). Affiche dans le header du
    // chat pour rendre ce reglage visible (il ne l'etait auparavant nulle
    // part dans l'UI).
    val aiPriorityMode: String = "cloud_first",
    // Apparence des bulles (voir ui/settings/SettingsScreen.kt) -- chargee une
    // fois au demarrage, comme `engine` ci-dessus ; un changement de reglage
    // s'applique a la prochaine ouverture de l'ecran Chat.
    val bubbleShape: String = "rounded",
    val bubbleUserColor: String = "gold",
    val bubbleAssistantColor: String = "cyan",
    // Mode vocal mains-libres (voir ai/VoiceModeController.kt) : OFF tant que
    // l'utilisateur n'a pas explicitement appuye sur le bouton dedie.
    val voiceState: VoiceState = VoiceState.OFF,
    val voiceModeError: String? = null,
)

class ChatViewModel(
    private val engineManager: AiEngineManager,
    private val commandRouter: CommandRouter,
    private val memoryStore: MemoryStore,
    private val chatDao: ChatDao,
    private val webSearchTool: WebSearchTool,
    private val settings: SettingsDataStore,
    private val tts: TtsController,
    private val voiceMode: VoiceModeController,
    private val cloudAiClient: CloudAiClient,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        // Mode vocal mains-libres : le texte reconnu par VoiceModeController
        // (ecoute en continu, coupure de parole -- voir ai/VoiceModeController.kt)
        // est envoye exactement comme un message tape au clavier.
        voiceMode.onFinalSpeech = { text -> sendMessage(text) }
        viewModelScope.launch {
            voiceMode.state.collect { vs -> _state.value = _state.value.copy(voiceState = vs) }
        }
        viewModelScope.launch {
            val info = engineManager.ensureReady()
            _state.value = _state.value.copy(engine = info)
            val recent = chatDao.recent(50).reversed()
            _state.value = _state.value.copy(
                messages = recent.map { it.toUi() },
                bubbleShape = settings.get(com.jarvis2.app.ui.settings.BUBBLE_SHAPE) ?: "rounded",
                bubbleUserColor = settings.get(com.jarvis2.app.ui.settings.BUBBLE_USER_COLOR) ?: "gold",
                bubbleAssistantColor = settings.get(com.jarvis2.app.ui.settings.BUBBLE_ASSISTANT_COLOR) ?: "cyan",
                aiPriorityMode = settings.get(com.jarvis2.app.ai.AI_PRIORITY_MODE) ?: "cloud_first",
            )
        }
        // Meme raison que dans SettingsViewModel : observe la progression en
        // direct (voir AiEngineManager.activeEngine) pour que l'indicateur
        // en haut du chat ("SmolVLM2 · prêt", etc.) reflete un telechargement
        // en cours (ex: si l'utilisateur choisit Qwen/Phi/Dolphin dans
        // Reglages puis revient sur le chat) au lieu de rester fige sur le
        // dernier moteur pret constate au tout premier lancement de l'ecran.
        viewModelScope.launch {
            engineManager.activeEngine.collect { info ->
                if (info != null) _state.value = _state.value.copy(engine = info)
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isThinking = true)
            // appendMessage(USER) est maintenant DANS le try (juste en-dessous)
            // -- avant, un echec Room (base corrompue, disque plein...) sur
            // CETTE insertion precise plantait avant meme que isThinking soit
            // visible, mais restait quand meme un crash total de l'appli.
            // Tout le corps ci-dessous est desormais enveloppe en try/catch/
            // finally (task #326/#328, signalement utilisateur "des que
            // Jarvis reflechit, l'appli crash") : avant ça, la moindre
            // exception Kotlin non prevue levee pendant cette fenetre
            // (isThinking=true) -- lecture calendrier/contacts/vault en
            // echec, bug dans une regex CommandRouter, etc. -- remontait
            // jusqu'au bout de la coroutine et faisait planter TOUTE
            // l'application (aucun CoroutineExceptionHandler n'etait
            // installe). Ce filet affiche desormais un message d'erreur dans
            // le chat a la place. Les causes les plus probables identifiees
            // (lectures calendrier/contacts/vault sans runCatching) ont
            // aussi ete corrigees a la source (voir CalendarRepository/
            // ContactsRepository/VaultRepository) -- ce try/catch est un
            // filet de secours final, pas le seul correctif. NE rattrape PAS
            // un crash NATIF (SIGABRT llama.cpp/JNI, ex: format de tenseur
            // non supporte par l'AAR chargee) : impossible a intercepter
            // depuis Kotlin, seul un correctif cote AAR (voir task #325)
            // peut l'empecher.
            try {
                appendMessage(Turn.Role.USER, text)

                // 1. Try a device-action command first (fully local, instant, deterministic).
                when (val result = commandRouter.route(text)) {
                    is CommandResult.Handled -> {
                        appendMessage(Turn.Role.ASSISTANT, result.feedback)
                        maybeSpeak(result.feedback)
                        memoryStore.remember("$text -> ${result.feedback}", source = "command")
                        // Certains commandes (voir CommandRouter.kt : matchers de
                        // presentation en tete de liste) changent bulleShape/
                        // bubbleUserColor/bubbleAssistantColor en direct depuis le
                        // chat -- on relit systematiquement apres une commande
                        // geree, pour que le changement s'applique tout de suite
                        // sans devoir rouvrir l'ecran Chat.
                        refreshPresentationPrefs()
                        return@launch
                    }
                    is CommandResult.NeedsPermission -> {
                        appendMessage(Turn.Role.ASSISTANT, result.feedback)
                        return@launch
                    }
                    CommandResult.NotACommand -> Unit // fall through to the cloud, then the local LLM
                }

                // 2/3. Cloud (Groq/Gemini) et modele IA local : l'ORDRE entre les
                //    deux est piloté par AI_PRIORITY_MODE (voir CloudAiClient.kt) --
                //    "cloud_first" par defaut (historique) ou "local_first" (reglable
                //    depuis le chat, voir CommandRouter.kt, suite au signalement
                //    utilisateur "Groq j'ai l'impression pas fonctionnel" : un filet
                //    de secours immediat qui ne depend pas d'un correctif cote
                //    CloudAiClient). Dans les deux cas, celui tente en second n'est
                //    utilise QUE si le premier echoue (pas de cle/reseau/quota pour
                //    le cloud, ou moteur indisponible pour le local) -- jamais les
                //    deux a la fois.
                val priorityMode = settings.get(com.jarvis2.app.ai.AI_PRIORITY_MODE) ?: "cloud_first"

                if (priorityMode == "groq_only") {
                    // Mode isolation "Groq uniquement" (task #329, demande
                    // explicite "met en place pour pouvoir choisir seulement
                    // Groq pour faire des tests") -- reglable dans Reglages >
                    // Moteur IA (voir SettingsScreen.kt). AUCUN repli, ni
                    // Gemini ni local : le but est de voir exactement ce que
                    // Groq seul renvoie, echec inclus.
                    if (!cloudAiClient.isGroqConfigured()) {
                        appendMessage(
                            Turn.Role.ASSISTANT,
                            "Mode « Groq uniquement » actif mais aucune clé Groq n'est configurée. Ajoute-en une dans Réglages (console.groq.com, gratuit).",
                        )
                    } else if (!tryCloud(text, groqOnly = true)) {
                        appendMessage(
                            Turn.Role.ASSISTANT,
                            "Groq n'a pas répondu (quota atteint, réseau, ou clé invalide). Mode « Groq uniquement » actif : pas de repli automatique vers Gemini ou le modèle local pour ce test.",
                        )
                    }
                } else if (priorityMode == "local_first") {
                    if (tryLocal(text, showErrorOnFailure = !cloudAiClient.isConfigured())) return@launch
                    if (cloudAiClient.isConfigured() && tryCloud(text)) return@launch
                    if (cloudAiClient.isConfigured()) {
                        // Le local ET le repli cloud ont tous les deux echoue --
                        // tryLocal() est reste silencieux (showErrorOnFailure=false
                        // puisqu'un repli cloud etait prevu), donc un message
                        // d'erreur final s'impose ici pour ne pas laisser
                        // l'utilisateur sans aucune reponse.
                        appendMessage(
                            Turn.Role.ASSISTANT,
                            "Moteur IA local indisponible, et le repli cloud (Groq/Gemini) a aussi échoué. Vérifie ta connexion et tes clés dans Réglages.",
                        )
                    }
                } else {
                    if (tryCloud(text)) return@launch
                    // cloud non configuré ou en échec (pas de clé/réseau/quota) :
                    // repli local, avec message d'erreur si lui aussi échoue.
                    tryLocal(text, showErrorOnFailure = true)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce // ne jamais avaler une annulation de coroutine (ecran ferme, etc.)
            } catch (e: Exception) {
                appendMessage(
                    Turn.Role.ASSISTANT,
                    "Oups, une erreur inattendue est survenue (${e.message ?: e::class.simpleName}). Réessaie, et si ça persiste, dis-le-moi.",
                )
            } finally {
                _state.value = _state.value.copy(isThinking = false)
            }
        }
    }

    /**
     * Tente l'IA cloud (Groq/Gemini, voir CloudAiClient.kt) pour [text] --
     * c'est elle qui peut créer des fiches contact, des notes, ou retenir
     * des préférences de présentation à partir de langage naturel libre,
     * via un bloc [JARVIS_CMD:{...}] (voir JarvisCommandParser +
     * CommandRouter.executeAction). Best-effort et TOUJOURS silencieux en
     * cas d'échec (pas de clé, pas de réseau, quota dépassé, non configuré)
     * -- c'est à l'appelant de décider quoi faire ensuite (repli local).
     * Retourne true si une réponse a bien été affichée.
     *
     * [groqOnly] : mode isolation "Groq uniquement" (task #329) -- coupe tout
     * le reste de la cascade (Gemini, Claude, OpenAI, Mistral, DeepSeek,
     * Perplexity, Together, OpenRouter, Pollinations -- voir CloudProvider)
     * dans CloudAiClient.send(), pour que le test porte VRAIMENT que sur Groq.
     */
    private suspend fun tryCloud(text: String, groqOnly: Boolean = false): Boolean {
        if (!cloudAiClient.isConfigured()) return false
        val cloudHistory = _state.value.messages.map { Turn(it.role, it.text) }
        val memoryNote = commandRouter.loadMemoryNote()
        val systemPrompt = buildCloudSystemPrompt(memoryNote)
        val cloudResult = cloudAiClient.send(systemPrompt, cloudHistory, text, allowFallbackChain = !groqOnly)
        var handled = false
        cloudResult.onSuccess { cloudReply ->
            // Portage Newjarvis (task #2/#3, fusion) : parse() renvoie
            // desormais TOUTES les commandes [JARVIS_CMD:...] presentes
            // dans la reponse (pas seulement la premiere), avec un parsing
            // robuste aux tableaux/objets JSON imbriques dans le payload --
            // voir la doc de classe de JarvisCommandParser pour le bug reel
            // que ça corrige (troncature silencieuse au premier "]").
            val (cleanText, commands) = JarvisCommandParser.parse(cloudReply.text)
            val actionFeedback = commands
                .map { commandRouter.executeAction(it.action, it.params) }
                .joinToString("\n\n") { feedback ->
                    when (feedback) {
                        is CommandResult.Handled -> feedback.feedback
                        is CommandResult.NeedsPermission -> feedback.feedback
                        else -> ""
                    }
                }
                .trim()
            val reply = when {
                cleanText.isBlank() && actionFeedback.isBlank() -> "D'accord."
                cleanText.isBlank() -> actionFeedback
                actionFeedback.isBlank() -> cleanText
                else -> "$cleanText\n\n$actionFeedback"
            }
            appendMessage(Turn.Role.ASSISTANT, reply)
            maybeSpeak(reply)
            memoryStore.remember("$text -> $reply", source = if (commands.isNotEmpty()) "cloud_action" else "cloud_chat")
            refreshPresentationPrefs()
            // Voir doc de ChatUiState.lastReplySource : rend visible dans le
            // header du chat que c'est bien Groq (ou Gemini en repli) qui a
            // repondu, pas le moteur local.
            _state.value = _state.value.copy(lastReplySource = cloudReply.provider)
            handled = true
        }
        return handled
    }

    /**
     * Tente le modèle IA local (AiEngineManager) pour [text], augmenté avec
     * la mémoire pertinente (ai/MemoryStore.kt). Si [showErrorOnFailure] est
     * faux, reste complètement silencieux en cas d'échec -- utilisé quand
     * l'appelant prévoit un repli cloud juste après et ne veut pas afficher
     * un message d'erreur transitoire que l'utilisateur ne verra jamais
     * vraiment (la vraie réponse, cloud, arrive juste derrière). Retourne
     * true si une réponse a bien été affichée.
     */
    private suspend fun tryLocal(text: String, showErrorOnFailure: Boolean): Boolean {
        val history = _state.value.messages.map { Turn(it.role, it.text) }
        val memories = memoryStore.relevant(text)
        val augmentedPrompt = if (memories.isEmpty()) text else buildString {
            appendLine("[Contexte mémorisé pertinent]")
            memories.forEach { appendLine("- ${it.text}") }
            appendLine()
            append(text)
        }

        val result = engineManager.generate(augmentedPrompt, history)
        var handled = false
        result.onSuccess { reply ->
            appendMessage(Turn.Role.ASSISTANT, reply)
            maybeSpeak(reply)
            memoryStore.remember("$text -> $reply", source = "chat")
            if (looksUncertain(reply)) {
                _state.value = _state.value.copy(pendingWebSearchQuery = text)
            }
            // Efface une eventuelle provenance cloud precedente : cette
            // reponse-ci vient du moteur local (deja affiche via `engine`
            // dans le header -- voir ChatUiState.lastReplySource).
            _state.value = _state.value.copy(lastReplySource = null)
            handled = true
        }.onFailure { error ->
            if (showErrorOnFailure) {
                appendMessage(
                    Turn.Role.ASSISTANT,
                    "Moteur IA indisponible (${error.message}). Vérifie Réglages : soit AICore n'est pas supporté sur cet appareil, soit aucun modèle local n'est importé.",
                )
            }
        }
        return handled
    }

    fun dismissWebSearchPrompt() {
        _state.value = _state.value.copy(pendingWebSearchQuery = null)
    }

    /**
     * Bascule le mode vocal mains-libres (voir ai/VoiceModeController.kt) :
     * demarre une ecoute en continu (avec coupure de parole automatique) si
     * le mode etait eteint, ou l'arrete completement sinon. Si la permission
     * micro manque ou qu'aucun moteur de reconnaissance vocale n'est
     * disponible sur l'appareil, un message d'erreur est affiche dans le
     * chat plutot que d'echouer silencieusement.
     */
    fun toggleVoiceMode() {
        if (_state.value.voiceState != VoiceState.OFF) {
            voiceMode.stop()
            return
        }
        val started = voiceMode.start()
        if (!started) {
            _state.value = _state.value.copy(
                voiceModeError = if (!voiceMode.hasMicPermission()) {
                    "Permission microphone requise pour le mode vocal mains-libres."
                } else {
                    "Aucun moteur de reconnaissance vocale disponible sur cet appareil."
                },
            )
        }
    }

    fun dismissVoiceModeError() {
        _state.value = _state.value.copy(voiceModeError = null)
    }

    /**
     * L'unique exception explicite et opt-in a "100% local" (voir WebSearchTool) :
     * declenchee seulement ici, sur confirmation explicite de l'utilisateur dans le
     * chat (bouton "Rechercher" apres que le modele ait admis ne pas savoir) --
     * jamais automatiquement. Va chercher le texte reel des pages (pas juste les
     * extraits du moteur de recherche -- voir WebSearchTool.searchAndExtract) et
     * fait synthetiser une reponse directe par CommandRouter.renderWebSearchResults
     * (qui respecte l'instruction de presentation "recherche web" si l'utilisateur
     * en a enregistre une). Le resultat est reinjecte comme un message normal de
     * Jarvis, et memorise comme n'importe quel autre echange.
     */
    fun searchWeb(query: String) {
        _state.value = _state.value.copy(pendingWebSearchQuery = null, isThinking = true)
        viewModelScope.launch {
            // Meme filet de securite que sendMessage() (voir sa doc, task
            // #326/#328) : renderWebSearchResults() peut passer par le
            // moteur IA local (renderWithLlm) et divers appels reseau/vault,
            // donc soumis aux memes risques d'exception non prevue.
            try {
                val result = webSearchTool.searchAndExtract(query)
                result.onSuccess { extracts ->
                    val formatted = commandRouter.renderWebSearchResults(query, extracts)
                    appendMessage(Turn.Role.ASSISTANT, formatted)
                    maybeSpeak(formatted)
                    memoryStore.remember("$query -> $formatted", source = "web_search")
                }.onFailure { error ->
                    appendMessage(Turn.Role.ASSISTANT, "Recherche web impossible : ${error.message}")
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                appendMessage(Turn.Role.ASSISTANT, "Recherche web impossible : erreur inattendue (${e.message ?: e::class.simpleName}).")
            } finally {
                _state.value = _state.value.copy(isThinking = false)
            }
        }
    }

    /**
     * Reprend le principe du SYSTEM_PROMPT de l'ancienne Newjarvis (voir
     * ApiClient.kt de ce depot) mais limite volontairement au sous-ensemble
     * d'actions demande par l'utilisateur (fiches contact, notes, memoire,
     * preferences de presentation) -- pas de GitHub/domotique/fichiers/etc.
     * comme dans l'ancienne version, ce n'est pas ce qui a ete demande ici.
     * [memoryNote] est le contenu integral de la note "Mémoire JARVIS" du
     * vault (voir CommandRouter.MEMORY_NOTE_TITLE / rememberFact) : relu et
     * injecte a chaque envoi pour eviter de tout redemander a l'utilisateur.
     */
    private fun buildCloudSystemPrompt(memoryNote: String?): String = buildString {
        appendLine(
            "Tu es Jarvis, l'assistant personnel Android de l'utilisateur. Réponds toujours en français, " +
                "de façon naturelle et concise, comme le ferait un vrai assistant.",
        )
        appendLine()
        appendLine(
            "Si le message de l'utilisateur demande une des actions suivantes, termine ta réponse (après le " +
                "texte que tu veux afficher à l'utilisateur, ou seul si aucun texte n'est nécessaire) par UN SEUL " +
                "bloc au format exact [JARVIS_CMD:{\"action\":\"NOM_ACTION\",...}] (JSON valide sur une seule ligne). " +
                "Ce bloc est retiré automatiquement avant affichage, n'en parle jamais à l'utilisateur.",
        )
        appendLine()
        appendLine("Actions disponibles :")
        appendLine("- save_contact_profile : créer/mettre à jour une fiche contact. Params : name (obligatoire), " +
            "category, nickname, phone, phonePro, email, address, addressPro, birthday, company, position, notes.")
        appendLine("- obsidian_create_note : créer une note dans le vault Obsidian. Params : title, content, folder (optionnel).")
        appendLine("- remember_fact : mémoriser durablement un fait sur l'utilisateur ou ses préférences. Params : fact.")
        appendLine("- forget_fact : oublier un fait mémorisé précédemment. Params : query (mots-clés du fait à oublier).")
        appendLine("- set_contact_presentation_style : mémoriser comment présenter la liste des contacts. Params : style.")
        appendLine("- set_calendar_presentation_style : mémoriser comment présenter le planning/calendrier. Params : style.")
        appendLine("- set_websearch_presentation_style : mémoriser comment présenter les résultats de recherche web. Params : style.")
        appendLine("- set_contact_fiche_presentation_style : mémoriser comment présenter une fiche contact. Params : style.")
        if (!memoryNote.isNullOrBlank()) {
            appendLine()
            appendLine("Faits déjà mémorisés sur l'utilisateur (note « Mémoire JARVIS ») :")
            appendLine(memoryNote)
        }
    }

    private fun looksUncertain(reply: String): Boolean {
        val markers = listOf("je ne sais pas", "je ne peux pas répondre", "je n'ai pas cette information", "incertain")
        val lower = reply.lowercase()
        return markers.any { lower.contains(it) }
    }

    private suspend fun refreshPresentationPrefs() {
        _state.value = _state.value.copy(
            bubbleShape = settings.get(com.jarvis2.app.ui.settings.BUBBLE_SHAPE) ?: "rounded",
            bubbleUserColor = settings.get(com.jarvis2.app.ui.settings.BUBBLE_USER_COLOR) ?: "gold",
            bubbleAssistantColor = settings.get(com.jarvis2.app.ui.settings.BUBBLE_ASSISTANT_COLOR) ?: "cyan",
            // Reflete immediatement un "priorite ia locale"/"remets le cloud
            // en priorite" tape dans le chat (voir CommandRouter.kt) -- sans
            // ca, l'utilisateur changeait bien le comportement reel mais le
            // header (voir ChatScreen.kt) restait affiche sur l'ancienne
            // valeur jusqu'au redemarrage de l'app.
            aiPriorityMode = settings.get(com.jarvis2.app.ai.AI_PRIORITY_MODE) ?: "cloud_first",
        )
    }

    private suspend fun appendMessage(role: Turn.Role, text: String) {
        val entity = ChatMessageEntity(role = role.name, text = text, timestamp = System.currentTimeMillis())
        val id = chatDao.insert(entity)
        _state.value = _state.value.copy(messages = _state.value.messages + entity.copy(id = id).toUi())
    }

    private fun ChatMessageEntity.toUi() = ChatUiMessage(id, Turn.Role.valueOf(role), text, timestamp)

    /**
     * Lecture a voix haute des reponses de Jarvis (voir ai/TtsController.kt),
     * activable/desactivable depuis Reglages (TTS_ENABLED). Best-effort : une
     * erreur TTS ne doit jamais interrompre la conversation texte.
     */
    private suspend fun maybeSpeak(text: String) {
        val enabled = (settings.get(TTS_ENABLED) ?: "true") == "true"
        if (enabled) {
            tts.speak(text)
        }
    }

    override fun onCleared() {
        voiceMode.stop()
        tts.release()
        super.onCleared()
    }
}
