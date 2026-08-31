package com.jarvis2.app.ai.aicore

import android.content.Context
import com.jarvis2.app.ai.EngineInfo
import com.jarvis2.app.ai.LocalAiEngine
import com.jarvis2.app.ai.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Preferred engine on devices that ship Google's AICore system service with
 * Gemini Nano (Pixel 8+, and — as of mid-2026 — recent Xiaomi/POCO flagships:
 * Xiaomi 14T Pro/15/15T/15T Pro/15 Ultra/17/17 Ultra, POCO F7 Ultra/F8
 * Pro/F8 Ultra/X7 Pro/X8 Pro, per Google's published Gemini Nano v2
 * compatibility list). Where available it's faster and lighter than the
 * bundled MediaPipe fallback because the model is shared at the OS level
 * instead of packaged per-app.
 *
 * IMPORTANT — read before shipping: the on-device generative client SDK
 * (group `com.google.ai.edge.aicore`) is still labelled experimental/early
 * access by Google and its exact API surface has moved between preview
 * releases. The shape used below (`GenerativeModel(context).generateContent(...)`)
 * matches the pattern documented at https://developer.android.com/ai/gemini-nano
 * at the time this was written; if it no longer compiles against the
 * version you pull in, check that page for the current method names —
 * everything else in the app is unaffected because it only talks to this
 * class through [LocalAiEngine]. [AiEngineManager] treats a failure to
 * initialize this engine as "unavailable" and transparently falls back to
 * [com.jarvis2.app.ai.mediapipe.MediaPipeLlmEngine], so the app is fully
 * usable even if this class needs adjustment for a newer SDK build.
 */
class AiCoreEngine(private val context: Context) : LocalAiEngine {

    private var ready = false
    private var lastError: String? = null
    private var session: AiCoreSession? = null

    override suspend fun prepare(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val s = AiCoreSessionFactory.tryCreate(context)
                ?: throw IllegalStateException("AICore indisponible sur cet appareil (device non supporté ou module non installé).")
            // La simple construction de GenerativeModel reussit meme sur des
            // appareils qui n'ont pas reellement le module d'inference Gemini
            // Nano installe -- le SDK experimental ne verifie la disponibilite
            // qu'au tout premier appel reel. Sans ce test, AiEngineManager
            // selectionnait AICore comme moteur actif alors qu'il echouait a
            // chaque message avec "AICore failed with error type 2
            // INFERENCE_ERROR ... required LLM feature not found" au lieu de
            // basculer sur MediaPipe. Un test d'inference minimal ici, une
            // seule fois au demarrage, garantit que ready=true veut vraiment
            // dire "peut generer".
            s.generate("", emptyList(), "Bonjour")
            session = s
            ready = true
        }.onFailure {
            lastError = it.message
            session?.close()
            session = null
            ready = false
        }
    }

    override fun info() = EngineInfo(
        id = "aicore-gemini-nano",
        displayName = "Gemini Nano (AICore, sur puce)",
        isFullyLocal = true,
        isReady = ready,
        notes = lastError ?: "Modèle système partagé, aucun téléchargement à gérer par l'app.",
    )

    override suspend fun generate(prompt: String, history: List<Turn>, systemPrompt: String): Result<String> =
        withContext(Dispatchers.Default) {
            val s = session ?: return@withContext Result.failure(IllegalStateException("AICore non initialisé"))
            runCatching { s.generate(systemPrompt, history, prompt) }
        }

    override fun generateStreaming(prompt: String, history: List<Turn>, systemPrompt: String): Flow<String> = flow {
        val s = session ?: throw IllegalStateException("AICore non initialisé")
        // The experimental SDK's streaming callback shape is not yet stable
        // enough to lock in here; emit the full response as a single frame
        // (the UI still renders it as if streamed, so no user-facing
        // regression once true token streaming is wired up).
        emit(s.generate(systemPrompt, history, prompt))
    }

    override fun release() {
        session?.close()
        session = null
        ready = false
    }
}

/** Thin seam around the actual AICore SDK types so the rest of the file stays readable. */
internal interface AiCoreSession {
    suspend fun generate(systemPrompt: String, history: List<Turn>, prompt: String): String
    fun close()
}

internal object AiCoreSessionFactory {
    /**
     * Returns null (never throws) when AICore/Gemini Nano is not available
     * on this device or Android version, so [AiCoreEngine.prepare] can
     * cleanly report "unavailable" and let [com.jarvis2.app.ai.AiEngineManager]
     * fall back to the bundled model instead of crashing the app.
     */
    fun tryCreate(context: Context): AiCoreSession? {
        return try {
            RealAiCoreSession(context)
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * Real bridge to `com.google.ai.edge.aicore.GenerativeModel`. Kept in its
 * own small class (rather than inlined above) so that if the experimental
 * SDK's constructor/method names change, this is the only place to touch.
 */
internal class RealAiCoreSession(appContext: Context) : AiCoreSession {

    // Built via GenerationConfig.Builder directly (rather than the
    // `generationConfig { ... }` top-level DSL helper) so property
    // assignment is unambiguous — the parameter is named `appContext`
    // specifically so it can never shadow/be shadowed by Builder.context.
    // Constructed lazily via reflection-free direct calls against the
    // experimental SDK. If `com.google.ai.edge.aicore.GenerativeModel`
    // is not on the classpath (dependency not resolvable yet on your
    // machine) or the device lacks AICore, this constructor throws and
    // [AiCoreSessionFactory.tryCreate] swallows it — no crash.
    private val generationConfig = com.google.ai.edge.aicore.GenerationConfig.Builder().apply {
        context = appContext
        temperature = 0.3f
        topK = 16
        maxOutputTokens = 768
    }.build()

    private val model = com.google.ai.edge.aicore.GenerativeModel(generationConfig)

    override suspend fun generate(systemPrompt: String, history: List<Turn>, prompt: String): String {
        val transcript = buildString {
            appendLine(systemPrompt.trim())
            history.takeLast(8).forEach { appendLine("${it.role}: ${it.text}") }
            append(prompt)
        }
        val response = model.generateContent(transcript)
        return response.text ?: ""
    }

    override fun close() {
        // GenerativeModel currently has no explicit close(); reserved for
        // when the SDK adds session/resource lifecycle management.
    }
}
