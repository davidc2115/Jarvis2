package com.jarvis2.app.ai

import android.content.Context
import com.jarvis2.app.ai.aicore.AiCoreEngine
import com.jarvis2.app.ai.mediapipe.MediaPipeLlmEngine
import com.jarvis2.app.ai.smolvlm.SmolVlmEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Picks the best available [LocalAiEngine] at runtime and exposes a single
 * stable API to the rest of the app. Ordered fallback chain, from most to
 * least preferred:
 *
 *  1. [AiCoreEngine] (Gemini Nano / AICore) — native, fastest, no per-app
 *     model download, but only present on a handful of recent flagship
 *     devices. Tried first because it's essentially free to probe.
 *  2. [SmolVlmEngine] (SmolVLM2, llama.cpp) — the real default in practice:
 *     works on any ARM64 phone, downloads itself automatically on first use
 *     from an ungated Hugging Face repo (no account, no license click), and
 *     is natively multimodal (text + image).
 *  3. [MediaPipeLlmEngine] (Gemma 3 1B or any other imported `.task` model)
 *     — optional/manual: either imported by the user via Settings, or
 *     downloaded from Hugging Face using a personal access token the user
 *     pastes in Settings (Gemma is gated upstream, unlike SmolVLM2).
 *
 * [ensureReady] walks the chain once and settles on the first engine whose
 * [LocalAiEngine.prepare] succeeds. [generate] additionally self-heals at
 * request time: if the currently selected engine's `prepare()` lied (it
 * reported success but generation still fails — this genuinely happens with
 * AICore's experimental SDK, see task #261), it transparently moves on to
 * the next engine in the chain and retries the same request, instead of
 * surfacing the raw SDK error to the user.
 */
class AiEngineManager(private val context: Context) {

    private val aiCore = AiCoreEngine(context)
    private val smolVlm by lazy { SmolVlmEngine(context) }
    private val mediaPipe by lazy { MediaPipeLlmEngine(context) }

    /** Ordered from most to least preferred; see class doc. */
    private val engineChain: List<LocalAiEngine> by lazy { listOf(aiCore, smolVlm, mediaPipe) }

    private val _activeEngine = MutableStateFlow<EngineInfo?>(null)
    val activeEngine: StateFlow<EngineInfo?> = _activeEngine.asStateFlow()

    private var current: LocalAiEngine? = null

    suspend fun ensureReady(): EngineInfo {
        current?.let { return it.info() }

        for (engine in engineChain) {
            // SmolVLM2's prepare() can take a while on first run (model
            // download) -- surface an interim "downloading" status right
            // away so the UI (which reads activeEngine.notes) doesn't sit on
            // a stale "Initialisation…" the whole time.
            _activeEngine.value = engine.info()
            val result = engine.prepare()
            if (result.isSuccess) {
                current = engine
                _activeEngine.value = engine.info()
                return engine.info()
            }
        }

        // Aucun moteur n'a reussi son prepare(): on retombe sur le dernier de
        // la chaine (MediaPipe) pour que l'UI affiche un message utile
        // ("aucun modele importe...") plutot que de planter.
        val last = engineChain.last()
        current = last
        _activeEngine.value = last.info()
        return last.info()
    }

    suspend fun generate(prompt: String, history: List<Turn>, systemPrompt: String = JARVIS_SYSTEM_PROMPT): Result<String> {
        if (current == null) ensureReady()
        val startIndex = current?.let { engineChain.indexOf(it) }?.coerceAtLeast(0) ?: 0

        var lastResult: Result<String>? = null
        for (i in startIndex until engineChain.size) {
            val engine = engineChain[i]
            if (engine !== current) {
                _activeEngine.value = engine.info()
                val prep = engine.prepare()
                if (prep.isFailure) {
                    lastResult = Result.failure(prep.exceptionOrNull() ?: IllegalStateException("Moteur indisponible"))
                    continue
                }
                current = engine
                _activeEngine.value = engine.info()
            }
            val result = engine.generate(prompt, history, systemPrompt)
            if (result.isSuccess) return result.mapCatching { deduplicateRepeatedSentences(it) }
            lastResult = result
            // echec a l'execution malgre prepare() reussi (voir doc de
            // classe) -- on essaie le moteur suivant de la chaine.
        }
        return lastResult ?: Result.failure(IllegalStateException("Aucun moteur IA disponible"))
    }

    fun generateStreaming(prompt: String, history: List<Turn>, systemPrompt: String = JARVIS_SYSTEM_PROMPT): Flow<String> {
        val engine = current ?: aiCore
        return engine.generateStreaming(prompt, history, systemPrompt).map { deduplicateRepeatedSentences(it) }
    }

    /** Force a re-check, e.g. after the user imports/downloads a local model file in Settings. */
    suspend fun refresh(): EngineInfo {
        current?.release()
        current = null
        return ensureReady()
    }

    fun release() {
        aiCore.release()
        smolVlm.release()
        mediaPipe.release()
        current = null
    }
}
