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
