package com.jarvis2.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis2.app.ai.AiEngineManager
import com.jarvis2.app.ai.CommandRouter
import com.jarvis2.app.ai.CommandResult
import com.jarvis2.app.ai.EngineInfo
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
                CommandResult.NotACommand -> Unit // fall through to the LLM
            }

            // 2. Otherwise, ordinary conversation via the local LLM, augmented
            //    with anything relevant from memory (see ai/MemoryStore.kt).
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
