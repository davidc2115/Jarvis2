package com.jarvis2.app.ai

import android.content.Context
import com.jarvis2.app.ai.aicore.AiCoreEngine
import com.jarvis2.app.ai.mediapipe.MediaPipeLlmEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Picks the best available [LocalAiEngine] at runtime and exposes a single
 * stable API to the rest of the app: try AICore/Gemini Nano first (native,
 * fastest, no per-app model download); if it's not available on this device
 * or Android build, fall back to the bundled MediaPipe engine automatically.
 * The UI never needs to know which one ended up running — it's shown for
 * transparency in Settings, that's all.
 */
class AiEngineManager(private val context: Context) {

    private val aiCore = AiCoreEngine(context)
    private val mediaPipe by lazy { MediaPipeLlmEngine(context) }

    private val _activeEngine = MutableStateFlow<EngineInfo?>(null)
    val activeEngine: StateFlow<EngineInfo?> = _activeEngine.asStateFlow()

    private var current: LocalAiEngine? = null

    suspend fun ensureReady(): EngineInfo {
        current?.let { return it.info() }

        val aiCoreResult = aiCore.prepare()
        if (aiCoreResult.isSuccess) {
            current = aiCore
            _activeEngine.value = aiCore.info()
            return aiCore.info()
        }

        val mediaPipeResult = mediaPipe.prepare()
        current = mediaPipe
        _activeEngine.value = mediaPipe.info()
        if (mediaPipeResult.isFailure) {
            // Neither engine is ready yet (e.g. no local model imported):
            // still return the info so the UI can prompt the user, instead
            // of throwing and breaking the chat screen.
        }
        return mediaPipe.info()
    }

    suspend fun generate(prompt: String, history: List<Turn>, systemPrompt: String = JARVIS_SYSTEM_PROMPT): Result<String> {
        val engine = current ?: run { ensureReady(); current }
            ?: return Result.failure(IllegalStateException("Aucun moteur IA disponible"))

        val result = engine.generate(prompt, history, systemPrompt)
        if (result.isSuccess || engine !== aiCore) {
            return result
        }

        // Filet de securite : AICore avait passe le test d'inference au
        // demarrage (voir AiCoreEngine.prepare) mais echoue quand meme ici
        // (ex: le service systeme AICore devient indisponible apres coup,
        // une mise a jour OTA, etc). On bascule silencieusement et
        // durablement sur MediaPipe pour ne plus jamais retenter AICore sur
        // cet appareil, et on rejoue cette meme requete au lieu d'exposer
        // l'erreur brute du SDK a l'utilisateur.
        aiCore.release()
        val mediaPipeResult = mediaPipe.prepare()
        current = mediaPipe
        _activeEngine.value = mediaPipe.info()
        if (mediaPipeResult.isFailure) {
            return result
        }
        return mediaPipe.generate(prompt, history, systemPrompt)
    }

    fun generateStreaming(prompt: String, history: List<Turn>, systemPrompt: String = JARVIS_SYSTEM_PROMPT): Flow<String> {
        val engine = current ?: aiCore
        return engine.generateStreaming(prompt, history, systemPrompt)
    }

    /** Force a re-check, e.g. after the user imports a local model file in Settings. */
    suspend fun refresh(): EngineInfo {
        current?.release()
        current = null
        return ensureReady()
    }

    fun release() {
        aiCore.release()
        mediaPipe.release()
        current = null
    }
}
