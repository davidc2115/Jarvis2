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
            appendMessage(Turn.Role.USER, text)
            _state.value = _state.value.copy(isThinking = true)

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
                    _state.value = _state.value.copy(isThinking = false)
                    return@launch
                }
                is CommandResult.NeedsPermission -> {
                    appendMessage(Turn.Role.ASSISTANT, result.feedback)
                    _state.value = _state.value.copy(isThinking = false)
                    return@launch
                }
                CommandResult.NotACommand -> Unit // fall through to the cloud, then the local LLM
            }

            // 2. Si une clé Groq/Gemini est configurée (voir Réglages), on tente
            //    de faire comprendre la demande par l'IA cloud AVANT le modèle
            //    local : c'est elle qui peut créer des fiches contact, des notes,
            //    ou retenir des préférences de présentation à partir de langage
            //    naturel libre, via un bloc [JARVIS_CMD:{...}] (voir
            //    JarvisCommandParser + CommandRouter.executeAction). Best-effort :
            //    en cas d'échec (pas de clé, pas de réseau, quota dépassé), on
            //    retombe silencieusement sur le moteur local à l'étape 3, l'app
            //    reste donc pleinement utilisable hors-ligne comme avant.
            if (cloudAiClient.isConfigured()) {
                val cloudHistory = _state.value.messages.map { Turn(it.role, it.text) }
                val memoryNote = commandRouter.loadMemoryNote()
                val systemPrompt = buildCloudSystemPrompt(memoryNote)
                val cloudResult = cloudAiClient.send(systemPrompt, cloudHistory, text)
                cloudResult.onSuccess { rawReply ->
                    val (cleanText, command) = JarvisCommandParser.parse(rawReply)
                    val actionFeedback = command?.let { commandRouter.executeAction(it.action, it.params) }
                    val reply = when (actionFeedback) {
                        is CommandResult.Handled -> if (cleanText.isBlank()) actionFeedback.feedback else "$cleanText\n\n${actionFeedback.feedback}"
                        is CommandResult.NeedsPermission -> actionFeedback.feedback
                        else -> cleanText.ifBlank { "D'accord." }
                    }
                    appendMessage(Turn.Role.ASSISTANT, reply)
                    maybeSpeak(reply)
                    memoryStore.remember("$text -> $reply", source = if (command != null) "cloud_action" else "cloud_chat")
                    refreshPresentationPrefs()
                    _state.value = _state.value.copy(isThinking = false)
                    return@launch
                }
                // cloudResult.isFailure -> pas de clé valide / réseau / quota :
                // on continue vers l'étape 3 (modèle local) sans rien afficher.
            }

            // 3. Sinon (ou en repli si le cloud a échoué), conversation via le
            //    LLM local, augmentée avec la mémoire pertinente (ai/MemoryStore.kt).
            val history = _state.value.messages.map { Turn(it.role, it.text) }
            val memories = memoryStore.relevant(text)
            val augmentedPrompt = if (memories.isEmpty()) text else buildString {
                appendLine("[Contexte mémorisé pertinent]")
                memories.forEach { appendLine("- ${it.text}") }
                appendLine()
                append(text)
            }

            val result = engineManager.generate(augmentedPrompt, history)
            result.onSuccess { reply ->
                appendMessage(Turn.Role.ASSISTANT, reply)
                maybeSpeak(reply)
                memoryStore.remember("$text -> $reply", source = "chat")
                if (looksUncertain(reply)) {
                    _state.value = _state.value.copy(pendingWebSearchQuery = text)
                }
            }.onFailure { error ->
                appendMessage(
                    Turn.Role.ASSISTANT,
                    "Moteur IA indisponible (${error.message}). Vérifie Réglages : soit AICore n'est pas supporté sur cet appareil, soit aucun modèle local n'est importé.",
                )
            }
            _state.value = _state.value.copy(isThinking = false)
        }
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
            val result = webSearchTool.searchAndExtract(query)
            result.onSuccess { extracts ->
                val formatted = commandRouter.renderWebSearchResults(query, extracts)
                appendMessage(Turn.Role.ASSISTANT, formatted)
                maybeSpeak(formatted)
                memoryStore.remember("$query -> $formatted", source = "web_search")
            }.onFailure { error ->
                appendMessage(Turn.Role.ASSISTANT, "Recherche web impossible : ${error.message}")
            }
            _state.value = _state.value.copy(isThinking = false)
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
