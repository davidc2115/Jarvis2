package com.jarvis2.app.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis2.app.ai.AiEngineManager
import com.jarvis2.app.ai.EngineInfo
import com.jarvis2.app.ai.ModelDownloader
import com.jarvis2.app.data.SettingsDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

private val WEB_SEARCH_API_KEY = stringPreferencesKey("web_search_api_key")
private val WEB_SEARCH_ENDPOINT = stringPreferencesKey("web_search_endpoint")
private val HUGGING_FACE_TOKEN = stringPreferencesKey("hugging_face_token")

// Apparence des bulles de chat (voir ui/chat/ChatScreen.kt) : forme et couleur
// d'accent choisies independamment pour les messages utilisateur/assistant.
// Stockees comme identifiants de preset ("rounded"/"square"/"pill" et
// "cyan"/"gold"/"red"/"violet"/"green") plutot que des couleurs libres, pour
// rester coherent avec la palette HUD existante (ui/theme/Color.kt) sans
// avoir a batir un vrai color picker.
val BUBBLE_SHAPE = stringPreferencesKey("bubble_shape")
val BUBBLE_USER_COLOR = stringPreferencesKey("bubble_user_color")
val BUBBLE_ASSISTANT_COLOR = stringPreferencesKey("bubble_assistant_color")

// Presentation du planning/agenda (lu par le CommandRouter -- voir
// ai/CommandRouter.kt) : regroupement par jour ou liste simple.
val CALENDAR_GROUP_BY_DAY = stringPreferencesKey("calendar_group_by_day")

// Presentation des contacts (lu par CommandRouter.formatContacts) : "compact"
// (juste les noms, comportement historique) ou "detailed" (nom + numero).
val CONTACT_PRESENTATION_STYLE = stringPreferencesKey("contact_presentation_style")

// Presentation des resultats de recherche web (lu par ChatViewModel.searchWeb) :
// "detailed" (titre + extrait + URL, comportement historique) ou "compact"
// (juste les titres, une ligne).
val WEB_SEARCH_PRESENTATION_STYLE = stringPreferencesKey("web_search_presentation_style")

// litert-community/Gemma3-1B-IT -- verifie via l'API HF : depot GATED (licence
// Gemma a accepter une fois sur huggingface.co avant qu'un jeton d'acces
// personnel puisse le telecharger). Fichier .task le plus adapte a
// MediaPipeLlmEngine parmi toutes les variantes du depot (int4, ~529 Mo) --
// ne pas remplacer par un autre fichier sans revérifier sa taille.
private const val GEMMA_MODEL_URL =
    "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"
private const val GEMMA_MODEL_SIZE_BYTES = 554_661_243L

data class SettingsUiState(
    val engine: EngineInfo? = null,
    val webSearchApiKey: String = "",
    val huggingFaceToken: String = "",
    val isDownloadingGemma: Boolean = false,
    val gemmaDownloadError: String? = null,
    val bubbleShape: String = "rounded",
    val bubbleUserColor: String = "gold",
    val bubbleAssistantColor: String = "cyan",
    val calendarGroupByDay: Boolean = true,
    val contactPresentationStyle: String = "compact",
    val webSearchPresentationStyle: String = "detailed",
)

class SettingsViewModel(
    private val engineManager: AiEngineManager,
    private val settings: SettingsDataStore,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                engine = engineManager.ensureReady(),
                webSearchApiKey = settings.get(WEB_SEARCH_API_KEY).orEmpty(),
                huggingFaceToken = settings.get(HUGGING_FACE_TOKEN).orEmpty(),
                bubbleShape = settings.get(BUBBLE_SHAPE) ?: "rounded",
                bubbleUserColor = settings.get(BUBBLE_USER_COLOR) ?: "gold",
                bubbleAssistantColor = settings.get(BUBBLE_ASSISTANT_COLOR) ?: "cyan",
                calendarGroupByDay = (settings.get(CALENDAR_GROUP_BY_DAY) ?: "true") == "true",
                contactPresentationStyle = settings.get(CONTACT_PRESENTATION_STYLE) ?: "compact",
                webSearchPresentationStyle = settings.get(WEB_SEARCH_PRESENTATION_STYLE) ?: "detailed",
            )
        }
    }

    fun refreshEngine() = viewModelScope.launch {
        _state.value = _state.value.copy(engine = engineManager.refresh())
    }

    fun setWebSearchApiKey(key: String) = viewModelScope.launch {
        settings.set(WEB_SEARCH_API_KEY, key)
        _state.value = _state.value.copy(webSearchApiKey = key)
    }

    fun setHuggingFaceToken(token: String) = viewModelScope.launch {
        settings.set(HUGGING_FACE_TOKEN, token)
        _state.value = _state.value.copy(huggingFaceToken = token)
    }

    fun setBubbleShape(shape: String) = viewModelScope.launch {
        settings.set(BUBBLE_SHAPE, shape)
        _state.value = _state.value.copy(bubbleShape = shape)
    }

    fun setBubbleUserColor(color: String) = viewModelScope.launch {
        settings.set(BUBBLE_USER_COLOR, color)
        _state.value = _state.value.copy(bubbleUserColor = color)
    }

    fun setBubbleAssistantColor(color: String) = viewModelScope.launch {
        settings.set(BUBBLE_ASSISTANT_COLOR, color)
        _state.value = _state.value.copy(bubbleAssistantColor = color)
    }

    fun setCalendarGroupByDay(groupByDay: Boolean) = viewModelScope.launch {
        settings.set(CALENDAR_GROUP_BY_DAY, groupByDay.toString())
        _state.value = _state.value.copy(calendarGroupByDay = groupByDay)
    }

    fun setContactPresentationStyle(style: String) = viewModelScope.launch {
        settings.set(CONTACT_PRESENTATION_STYLE, style)
        _state.value = _state.value.copy(contactPresentationStyle = style)
    }

    fun setWebSearchPresentationStyle(style: String) = viewModelScope.launch {
        settings.set(WEB_SEARCH_PRESENTATION_STYLE, style)
        _state.value = _state.value.copy(webSearchPresentationStyle = style)
    }

    /**
     * Telecharge Gemma 3 1B (MediaPipe .task, ~529 Mo) directement vers le
     * chemin que MediaPipeLlmEngine attend deja (models/local-llm.task) --
     * aucune modification necessaire cote moteur, l'import manuel via SAF
     * reste egalement disponible dans l'UI pour qui prefere recuperer le
     * fichier autrement. Necessite un jeton Hugging Face personnel (gratuit)
     * genere APRES avoir accepte la licence Gemma sur huggingface.co, car ce
     * depot est verrouille contrairement a SmolVLM2 (moteur par defaut,
     * telecharge automatiquement sans rien demander a l'utilisateur).
     */
    fun downloadGemma() = viewModelScope.launch {
        val token = _state.value.huggingFaceToken.trim()
        if (token.isEmpty()) {
            _state.value = _state.value.copy(
                gemmaDownloadError = "Colle d'abord ton jeton Hugging Face ci-dessus (voir l'explication).",
            )
            return@launch
        }
        _state.value = _state.value.copy(isDownloadingGemma = true, gemmaDownloadError = null)
        val destFile = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "models/local-llm.task")
        val result = ModelDownloader.downloadIfMissing(
            url = GEMMA_MODEL_URL,
            destFile = destFile,
            headers = mapOf("Authorization" to "Bearer $token"),
            expectedSizeBytes = GEMMA_MODEL_SIZE_BYTES,
        )
        _state.value = _state.value.copy(
            isDownloadingGemma = false,
            gemmaDownloadError = result.exceptionOrNull()?.message,
        )
        if (result.isSuccess) {
            _state.value = _state.value.copy(engine = engineManager.refresh())
        }
    }
}
