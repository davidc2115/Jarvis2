package com.jarvis2.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis2.app.ai.AiEngineManager
import com.jarvis2.app.ai.CommandRouter
import com.jarvis2.app.ai.CommandResult
import com.jarvis2.app.ai.EngineInfo
import com.jarvis2.app.ai.MemoryStore
import com.jarvis2.app.ai.Turn
import com.jarvis2.app.ai.WebSearchTool
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
)

class ChatViewModel(
    private val engineManager: AiEngineManager,
    private val commandRouter: CommandRouter,
    private val memoryStore: MemoryStore,
    private val chatDao: ChatDao,
    private val webSearchTool: WebSearchTool,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val info = engineManager.ensureReady()
            _state.value = _state.value.copy(engine = info)
            val recent = chatDao.recent(50).reversed()
            _state.value = _state.value.copy(
                messages = recent.map { it.toUi() }
            )
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
                    memoryStore.remember("$text -> ${result.feedback}", source = "command")
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
     * L'unique exception explicite et opt-in a "100% local" (voir WebSearchTool) :
     * declenchee seulement ici, sur confirmation explicite de l'utilisateur dans le
     * chat (bouton "Rechercher" apres que le modele ait admis ne pas savoir) --
     * jamais automatiquement. Le resultat est reinjecte comme un message normal de
     * Jarvis, et memorise comme n'importe quel autre echange.
     */
    fun searchWeb(query: String) {
        _state.value = _state.value.copy(pendingWebSearchQuery = null, isThinking = true)
        viewModelScope.launch {
            val result = webSearchTool.search(query)
            result.onSuccess { results ->
                val formatted = if (results.isEmpty()) {
                    "Aucun resultat web trouve pour « $query »."
                } else {
                    buildString {
                        appendLine("Resultats web pour « $query » :")
                        results.forEach { r -> appendLine("• ${r.title} — ${r.snippet} (${r.url})") }
                    }.trim()
                }
                appendMessage(Turn.Role.ASSISTANT, formatted)
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

    private suspend fun appendMessage(role: Turn.Role, text: String) {
        val entity = ChatMessageEntity(role = role.name, text = text, timestamp = System.currentTimeMillis())
        val id = chatDao.insert(entity)
        _state.value = _state.value.copy(messages = _state.value.messages + entity.copy(id = id).toUi())
    }

    private fun ChatMessageEntity.toUi() = ChatUiMessage(id, Turn.Role.valueOf(role), text, timestamp)
}
