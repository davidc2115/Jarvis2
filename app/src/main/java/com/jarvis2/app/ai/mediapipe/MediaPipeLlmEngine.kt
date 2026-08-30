package com.jarvis2.app.ai.mediapipe

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.jarvis2.app.ai.EngineInfo
import com.jarvis2.app.ai.LocalAiEngine
import com.jarvis2.app.ai.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Universal fallback engine, used on every device that doesn't expose
 * AICore/Gemini Nano (i.e. most non-flagship or non-Pixel/non-recent-Xiaomi
 * hardware), via the MediaPipe LLM Inference API (`tasks-genai`), which runs
 * a bundled `.task` model (e.g. Gemma 2B/3 1B int4) fully offline via
 * llama.cpp/XNNPACK under the hood.
 *
 * The model file itself (1.5-3 GB depending on quantization) is **not**
 * bundled in the APK — that would blow past Play Store size limits and this
 * repo's size. On first run [prepare] looks for it at [modelFile]; if
 * missing, the Settings screen lets the user import a `.task` model from
 * storage (see ui/settings/SettingsScreen.kt) or, once network access is
 * configured for this build, download one from a URL you provide.
 *
 * Recommended starter model: Gemma 3 1B IT (int4), converted to `.task`
 * format per https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference
 */
class MediaPipeLlmEngine(private val context: Context) : LocalAiEngine {

    private var llmInference: LlmInference? = null
    private var lastError: String? = null

    private val modelFile: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/local-llm.task")

    override suspend fun prepare(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!modelFile.exists()) {
            lastError = "Aucun modèle local trouvé dans ${modelFile.path}. Importe un fichier .task depuis Réglages."
            return@withContext Result.failure(IllegalStateException(lastError))
        }
        runCatching {
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
        }.onFailure { lastError = it.message }
    }

    override fun info() = EngineInfo(
        id = "mediapipe-local-llm",
        displayName = "Modèle embarqué (MediaPipe)",
        isFullyLocal = true,
        isReady = llmInference != null,
        notes = lastError ?: "Fonctionne sur tous les appareils, sans dépendre d'AICore.",
    )

    private fun buildPrompt(prompt: String, history: List<Turn>, systemPrompt: String): String {
        val sb = StringBuilder()
        sb.appendLine(systemPrompt.trim())
        sb.appendLine()
        // Keep only the last few turns: small on-device models have short context windows.
        history.takeLast(8).forEach { turn ->
            val label = when (turn.role) {
                Turn.Role.USER -> "Utilisateur"
                Turn.Role.ASSISTANT -> "Jarvis"
                Turn.Role.SYSTEM -> "Système"
            }
            sb.appendLine("$label: ${turn.text}")
        }
        sb.appendLine("Utilisateur: $prompt")
        sb.append("Jarvis:")
        return sb.toString()
    }

    override suspend fun generate(prompt: String, history: List<Turn>, systemPrompt: String): Result<String> =
        withContext(Dispatchers.Default) {
            val engine = llmInference ?: return@withContext Result.failure(IllegalStateException("Moteur non prêt"))
            runCatching { engine.generateResponse(buildPrompt(prompt, history, systemPrompt)) }
        }

    override fun generateStreaming(prompt: String, history: List<Turn>, systemPrompt: String): Flow<String> =
        callbackFlow {
            val engine = llmInference
            if (engine == null) {
                close(IllegalStateException("Moteur non prêt"))
                return@callbackFlow
            }
            val built = StringBuilder()
            // LlmInference exposes an async streaming variant taking a
            // (partialResult, done) callback; wrapped into a Flow here.
            engine.generateResponseAsync(buildPrompt(prompt, history, systemPrompt)) { partial, done ->
                built.append(partial)
                trySend(built.toString())
                if (done) close()
            }
            awaitClose { /* MediaPipe session cleanup happens in release() */ }
        }

    override fun release() {
        llmInference?.close()
        llmInference = null
    }
}
