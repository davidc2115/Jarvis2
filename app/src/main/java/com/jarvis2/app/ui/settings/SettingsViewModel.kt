package com.jarvis2.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis2.app.ai.AiEngineManager
import com.jarvis2.app.ai.EngineInfo
import com.jarvis2.app.ai.GEMINI_CLOUD_API_KEY
import com.jarvis2.app.ai.GROQ_API_KEY
import com.jarvis2.app.data.SettingsDataStore
import com.jarvis2.app.integrations.GoogleAuthController
import com.jarvis2.app.integrations.GoogleAuthNeedsUserActionException
import com.jarvis2.app.proactive.PROACTIVE_BRIEFING_ENABLED
import com.jarvis2.app.proactive.PROACTIVE_REMINDERS_ENABLED
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

// Calendriers masques (task #308, signalement utilisateur : "impossible de
// choisir le calendrier a afficher, certains en double, d'autres absents").
// Liste d'id CalendarContract separes par des virgules, VIDE par defaut =
// tous les calendriers detectes sont affiches (comportement historique
// inchange). Lu par CommandRouter.visibleCalendarIdsOrNull() pour filtrer
// le planning par defaut ("mon planning", "j'ai quoi demain") sans toucher
// au filtrage explicite par nom ("planning de Thomas").
val HIDDEN_CALENDAR_IDS = stringPreferencesKey("hidden_calendar_ids")

// Lecture vocale des reponses de Jarvis (voir ai/TtsController.kt).
val TTS_ENABLED = stringPreferencesKey("tts_enabled")

// Presentation des contacts/planning/recherche web : plus de reglage binaire
// ici -- l'utilisateur decrit desormais la presentation voulue en detail
// depuis le chat ("enregistre la presentation des contacts : ..."), et
// CommandRouter la sauvegarde comme note libre dans le vault Obsidian (voir
// CommandRouter.PREF_NOTE_CONTACTS / PREF_NOTE_PLANNING / PREF_NOTE_WEBSEARCH).

data class SettingsUiState(
    val engine: EngineInfo? = null,
    val webSearchApiKey: String = "",
    // Cascade IA cloud gratuite (task #313/#315, voir ai/CloudAiClient.kt) :
    // en renseignant l'une des deux cles ci-dessous (Groq en priorite, ou
    // Gemini cloud seul/en repli), Jarvis comprend le langage naturel libre
    // (creation de fiches contact, notes, memorisation de preferences) au
    // lieu de se limiter aux phrases reconnues par les regex locales.
    // Aucune des deux n'est requise : sans cle, l'app reste 100% locale/
    // hors-ligne comme avant.
    val groqApiKey: String = "",
    val geminiCloudApiKey: String = "",
    val preferredEngineId: String = "auto",
    val selectedLocalModel: String = "none",
    val isDownloadingLocalModel: Boolean = false,
    val localModelDownloadError: String? = null,
    val bubbleShape: String = "rounded",
    val bubbleUserColor: String = "gold",
    val bubbleAssistantColor: String = "cyan",
    val calendarGroupByDay: Boolean = true,
    val ttsEnabled: Boolean = true,
    val proactiveRemindersEnabled: Boolean = true,
    val proactiveBriefingEnabled: Boolean = true,
    val gmailConnected: Boolean = false,
    val isConnectingGmail: Boolean = false,
    val gmailConnectError: String? = null,
    /** Non-null quand Google exige une confirmation -- l'UI doit le lancer via StartActivityForResult puis rappeler connectGmail(). */
    val pendingGmailAuthIntent: Intent? = null,
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
                groqApiKey = settings.get(GROQ_API_KEY).orEmpty(),
                geminiCloudApiKey = settings.get(GEMINI_CLOUD_API_KEY).orEmpty(),
                preferredEngineId = settings.get(PREFERRED_ENGINE_ID) ?: "auto",
                selectedLocalModel = settings.get(SELECTED_LOCAL_MODEL) ?: "none",
                bubbleShape = settings.get(BUBBLE_SHAPE) ?: "rounded",
                bubbleUserColor = settings.get(BUBBLE_USER_COLOR) ?: "gold",
                bubbleAssistantColor = settings.get(BUBBLE_ASSISTANT_COLOR) ?: "cyan",
                calendarGroupByDay = (settings.get(CALENDAR_GROUP_BY_DAY) ?: "true") == "true",
                ttsEnabled = (settings.get(TTS_ENABLED) ?: "true") == "true",
                proactiveRemindersEnabled = (settings.get(PROACTIVE_REMINDERS_ENABLED) ?: "true") == "true",
                proactiveBriefingEnabled = (settings.get(PROACTIVE_BRIEFING_ENABLED) ?: "true") == "true",
            )
            _state.value = _state.value.copy(gmailConnected = googleAuth.isConnected())
        }
        // Observe la progression en direct des telechargements de modele
        // (voir AiEngineManager.activeEngine) au lieu de ne lire l'etat du
        // moteur qu'une fois avant/apres tout setPreferredEngine() -- sans
        // ca, "Actif : ..." restait fige sur l'ancien moteur pendant tout un
        // telechargement de plusieurs centaines de Mo/quelques Go (Qwen/
        // Phi/Dolphin), avec pour seul signe de vie un spinner sans texte,
        // ce qui donnait l'impression qu'aucun telechargement n'avait lieu.
        viewModelScope.launch {
            engineManager.activeEngine.collect { info ->
                if (info != null) _state.value = _state.value.copy(engine = info)
            }
        }
    }

    fun refreshEngine() = viewModelScope.launch {
        _state.value = _state.value.copy(engine = engineManager.refresh())
    }

    fun setWebSearchApiKey(key: String) = viewModelScope.launch {
        settings.set(WEB_SEARCH_API_KEY, key)
        _state.value = _state.value.copy(webSearchApiKey = key)
    }

    /** Cle Groq (console.groq.com, gratuite, sans carte bancaire) -- priorite dans CloudAiClient.send(). */
    fun setGroqApiKey(key: String) = viewModelScope.launch {
        settings.set(GROQ_API_KEY, key)
        _state.value = _state.value.copy(groqApiKey = key)
    }

    /** Cle Gemini cloud (aistudio.google.com, gratuite) -- repli si Groq absent/en echec. */
    fun setGeminiCloudApiKey(key: String) = viewModelScope.launch {
        settings.set(GEMINI_CLOUD_API_KEY, key)
        _state.value = _state.value.copy(geminiCloudApiKey = key)
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

    fun setTtsEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.set(TTS_ENABLED, enabled.toString())
        _state.value = _state.value.copy(ttsEnabled = enabled)
    }

    /** Rappels avant un evenement d'agenda (voir proactive/ProactiveReminderWorker.kt, task #242). */
    fun setProactiveRemindersEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.set(PROACTIVE_REMINDERS_ENABLED, enabled.toString())
        _state.value = _state.value.copy(proactiveRemindersEnabled = enabled)
    }

    /** Notification quotidienne resumant les evenements du jour (voir proactive/MorningBriefingWorker.kt, task #242). */
    fun setProactiveBriefingEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.set(PROACTIVE_BRIEFING_ENABLED, enabled.toString())
        _state.value = _state.value.copy(proactiveBriefingEnabled = enabled)
    }

    /**
     * Lance (ou confirme silencieusement) la connexion Gmail via
     * GoogleAuthController, en reutilisant un compte Google deja present
     * sur le telephone (pas d'ecran de connexion separe). Si Google exige
     * une confirmation ponctuelle, [SettingsUiState.pendingGmailAuthIntent]
     * se remplit et SettingsScreen.kt le lance via
     * ActivityResultContracts.StartActivityForResult ; une fois cet ecran
     * ferme, il suffit de rappeler [connectGmail] (pas de parsing de
     * resultat necessaire, contrairement a l'ancienne Authorization API).
     */
    fun connectGmail() = viewModelScope.launch {
        _state.value = _state.value.copy(isConnectingGmail = true, gmailConnectError = null, pendingGmailAuthIntent = null)
        val result = googleAuth.getAccessToken()
        result.fold(
            onSuccess = {
                _state.value = _state.value.copy(isConnectingGmail = false, gmailConnected = true)
            },
            onFailure = { e ->
                if (e is GoogleAuthNeedsUserActionException) {
                    _state.value = _state.value.copy(isConnectingGmail = false, pendingGmailAuthIntent = e.intent)
                } else {
                    _state.value = _state.value.copy(isConnectingGmail = false, gmailConnectError = e.message)
                }
            },
        )
    }

    /** Appele par SettingsScreen.kt une fois l'ecran de confirmation Google ferme (succes ou annulation) -- relance simplement connectGmail(). */
    fun onGoogleAuthResult(data: Intent?) = connectGmail()

    fun clearPendingGmailAuthIntent() {
        _state.value = _state.value.copy(pendingGmailAuthIntent = null)
    }
}
