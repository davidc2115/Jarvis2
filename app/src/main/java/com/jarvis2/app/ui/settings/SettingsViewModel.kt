package com.jarvis2.app.ui.settings

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis2.app.ai.AiEngineManager
import com.jarvis2.app.ai.EngineInfo
import com.jarvis2.app.data.SettingsDataStore
import com.jarvis2.app.integrations.GoogleAuthController
import com.jarvis2.app.integrations.GoogleAuthNeedsUserActionException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val WEB_SEARCH_API_KEY = stringPreferencesKey("web_search_api_key")
private val WEB_SEARCH_ENDPOINT = stringPreferencesKey("web_search_endpoint")

// Modele local optionnel choisi par l'utilisateur (voir ai/gguf/LocalGgufModel
// et ai/gguf/SelectableLlmEngine.kt) : "qwen2.5-1.5b" / "phi-3.5-mini" /
// "dolphin3-qwen2.5-1.5b", ou absent/"none" pour ne rien telecharger de plus
// que SmolVLM2 (moteur par defaut). Remplace l'ancien telechargement Gemma 3
// gated -- aucun des trois choix ici ne demande de compte ni de jeton.
val SELECTED_LOCAL_MODEL = stringPreferencesKey("selected_local_model")

// Moteur IA prefere explicitement par l'utilisateur : "auto" (defaut,
// laisse AiEngineManager choisir -- AICore puis le modele optionnel puis
// SmolVLM2, voir sa doc de classe) ou l'un des EngineInfo.id exacts :
// "aicore-gemini-nano" / "smolvlm2-llamacpp" / "selectable-gguf" (dans ce
// dernier cas, SELECTED_LOCAL_MODEL determine LEQUEL des trois modeles GGUF
// charger). Corrige un bug reel : sans ce reglage, AiEngineManager s'arrete
// toujours au premier moteur qui reussit dans l'ordre fixe -- si AICore
// fonctionne sur l'appareil, le choix explicite de Qwen/Phi/Dolphin dans
// Reglages n'etait alors JAMAIS tente (aucun telechargement ne demarrait).
val PREFERRED_ENGINE_ID = stringPreferencesKey("preferred_engine_id")

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

// Presentation des contacts/planning/recherche web : plus de reglage binaire
// ici -- l'utilisateur decrit desormais la presentation voulue en detail
// depuis le chat ("enregistre la presentation des contacts : ..."), et
// CommandRouter la sauvegarde comme note libre dans le vault Obsidian (voir
// CommandRouter.PREF_NOTE_CONTACTS / PREF_NOTE_PLANNING / PREF_NOTE_WEBSEARCH).

data class SettingsUiState(
    val engine: EngineInfo? = null,
    val webSearchApiKey: String = "",
    val preferredEngineId: String = "auto",
    val selectedLocalModel: String = "none",
    val isDownloadingLocalModel: Boolean = false,
    val localModelDownloadError: String? = null,
    val bubbleShape: String = "rounded",
    val bubbleUserColor: String = "gold",
    val bubbleAssistantColor: String = "cyan",
    val calendarGroupByDay: Boolean = true,
    val gmailConnected: Boolean = false,
    val isConnectingGmail: Boolean = false,
    val gmailConnectError: String? = null,
    /** Non-null quand Google exige un ecran de consentement -- l'UI doit le lancer via StartIntentSenderForResult puis appeler onGoogleAuthResult(). */
    val pendingGmailAuthIntent: IntentSender? = null,
)

class SettingsViewModel(
    private val engineManager: AiEngineManager,
    private val settings: SettingsDataStore,
    private val googleAuth: GoogleAuthController,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                engine = engineManager.ensureReady(),
                webSearchApiKey = settings.get(WEB_SEARCH_API_KEY).orEmpty(),
                preferredEngineId = settings.get(PREFERRED_ENGINE_ID) ?: "auto",
                selectedLocalModel = settings.get(SELECTED_LOCAL_MODEL) ?: "none",
                bubbleShape = settings.get(BUBBLE_SHAPE) ?: "rounded",
                bubbleUserColor = settings.get(BUBBLE_USER_COLOR) ?: "gold",
                bubbleAssistantColor = settings.get(BUBBLE_ASSISTANT_COLOR) ?: "cyan",
                calendarGroupByDay = (settings.get(CALENDAR_GROUP_BY_DAY) ?: "true") == "true",
            )
            _state.value = _state.value.copy(gmailConnected = googleAuth.isConnected())
        }
    }

    fun refreshEngine() = viewModelScope.launch {
        _state.value = _state.value.copy(engine = engineManager.refresh())
    }

    fun setWebSearchApiKey(key: String) = viewModelScope.launch {
        settings.set(WEB_SEARCH_API_KEY, key)
        _state.value = _state.value.copy(webSearchApiKey = key)
    }

    /**
     * Choisit explicitement quel moteur IA utiliser -- "auto" pour laisser
     * AiEngineManager decider (comportement historique), ou l'id exact d'un
     * moteur ("aicore-gemini-nano" / "smolvlm2-llamacpp" / "selectable-gguf").
     * [ggufModelId] n'est fourni que pour "selectable-gguf", pour preciser
     * LEQUEL des trois modeles du catalogue (voir ai/gguf/LocalGgufModel.kt).
     * Le telechargement (si necessaire) demarre automatiquement lors du
     * refresh() qui suit, exactement comme SmolVLM2 le fait deja pour son
     * propre modele : pas besoin d'un bouton "telecharger" separe.
     */
    fun setPreferredEngine(engineId: String, ggufModelId: String? = null) = viewModelScope.launch {
        settings.set(PREFERRED_ENGINE_ID, engineId)
        if (ggufModelId != null) settings.set(SELECTED_LOCAL_MODEL, ggufModelId)
        _state.value = _state.value.copy(
            preferredEngineId = engineId,
            selectedLocalModel = ggufModelId ?: _state.value.selectedLocalModel,
            isDownloadingLocalModel = true,
            localModelDownloadError = null,
        )
        val info = engineManager.refresh()
        _state.value = _state.value.copy(
            engine = info,
            isDownloadingLocalModel = false,
            localModelDownloadError = if (engineId != "auto" && info.id != engineId) info.notes else null,
        )
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

    /**
     * Lance (ou confirme silencieusement) la connexion Gmail via
     * GoogleAuthController. Si Google exige un ecran de consentement,
     * [SettingsUiState.pendingGmailAuthIntent] se remplit et
     * SettingsScreen.kt est charge de le lancer via
     * ActivityResultContracts.StartIntentSenderForResult, puis d'appeler
     * [onGoogleAuthResult] avec l'Intent recu.
     */
    fun connectGmail() = viewModelScope.launch {
        _state.value = _state.value.copy(isConnectingGmail = true, gmailConnectError = null)
        val result = googleAuth.getAccessToken()
        result.fold(
            onSuccess = {
                _state.value = _state.value.copy(isConnectingGmail = false, gmailConnected = true)
            },
            onFailure = { e ->
                if (e is GoogleAuthNeedsUserActionException) {
                    _state.value = _state.value.copy(isConnectingGmail = false, pendingGmailAuthIntent = e.intentSender)
                } else {
                    _state.value = _state.value.copy(isConnectingGmail = false, gmailConnectError = e.message)
                }
            },
        )
    }

    /** Appele par SettingsScreen.kt une fois l'ecran de consentement Google ferme (succes ou annulation). */
    fun onGoogleAuthResult(data: Intent?) = viewModelScope.launch {
        _state.value = _state.value.copy(pendingGmailAuthIntent = null, isConnectingGmail = true)
        val result = googleAuth.parseAuthorizationResult(data)
        _state.value = _state.value.copy(
            isConnectingGmail = false,
            gmailConnected = result.isSuccess,
            gmailConnectError = result.exceptionOrNull()?.message,
        )
    }

    fun clearPendingGmailAuthIntent() {
        _state.value = _state.value.copy(pendingGmailAuthIntent = null)
    }
}
