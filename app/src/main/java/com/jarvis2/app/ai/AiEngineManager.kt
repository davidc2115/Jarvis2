package com.jarvis2.app.ai

import android.content.Context
import com.jarvis2.app.ai.aicore.AiCoreEngine
import com.jarvis2.app.ai.gguf.SelectableLlmEngine
import com.jarvis2.app.ai.smolvlm.SmolVlmEngine
import com.jarvis2.app.data.SettingsDataStore
import com.jarvis2.app.ui.settings.PREFERRED_ENGINE_ID
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
 *  2. [SelectableLlmEngine] (Qwen 2.5 1.5B / Phi-3.5 mini / Dolphin 3.0,
 *     llama.cpp) — optional, chosen explicitly by the user in Settings
 *     (see ai/gguf/LocalGgufModel.kt). Placed BEFORE SmolVLM2 in the chain
 *     on purpose: if the user went out of their way to pick one of these,
 *     they expect Jarvis to actually talk to it, not silently keep using
 *     the default. When nothing is selected, `prepare()` fails instantly
 *     with no network call, so this step costs nothing in the common case.
 *     Replaces the old Gemma 3 1B slot (gated, needed a Hugging Face
 *     token) — none of these three need an account or any API key.
 *  3. [SmolVlmEngine] (SmolVLM2, llama.cpp) — the guaranteed default:
 *     works on any ARM64 phone, downloads itself automatically on first use
 *     from an ungated Hugging Face repo (no account, no license click), and
 *     is natively multimodal (text + image).
 *
 * [ensureReady] walks the chain once and settles on the first engine whose
 * [LocalAiEngine.prepare] succeeds. [generate] additionally self-heals at
 * request time: if the currently selected engine's `prepare()` lied (it
 * reported success but generation still fails — this genuinely happens with
 * AICore's experimental SDK, see task #261), it transparently moves on to
 * the next engine in the chain and retries the same request, instead of
 * surfacing the raw SDK error to the user.
 *
 * IMPORTANT — explicit user choice overrides the chain order above: if
 * Settings has `PREFERRED_ENGINE_ID` set to something other than "auto"
 * (see ui/settings/SettingsScreen.kt, "Moteur IA"), [ensureReady] tries
 * that exact engine FIRST, before falling back to the normal chain order if
 * it fails. Without this override, a user who explicitly picked Qwen/Phi/
 * Dolphin in Settings would see it silently ignored whenever AICore already
 * works on their device (AICore always wins the fixed chain above) — no
 * download would ever start for their actual choice. This was a real bug,
 * not a hypothetical.
 */
class AiEngineManager(private val context: Context, private val settings: SettingsDataStore) {

    private val aiCore = AiCoreEngine(context)
    private val selectable by lazy { SelectableLlmEngine(context, settings) }
    private val smolVlm by lazy { SmolVlmEngine(context) }

    /** Ordered from most to least preferred; see class doc. */
    private val engineChain: List<LocalAiEngine> by lazy { listOf(aiCore, selectable, smolVlm) }

    private val _activeEngine = MutableStateFlow<EngineInfo?>(null)
    val activeEngine: StateFlow<EngineInfo?> = _activeEngine.asStateFlow()

    private var current: LocalAiEngine? = null

    suspend fun ensureReady(): EngineInfo {
        current?.let { return it.info() }

        val orderedChain = preferredFirstChain()
        for (engine in orderedChain) {
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
        // la chaine (SmolVLM2, qui reussit quasiment toujours puisqu'il se
        // telecharge lui-meme) pour que l'UI affiche un message utile plutot
        // que de planter.
        val last = engineChain.last()
        current = last
        _activeEngine.value = last.info()
        return last.info()
    }

    /**
     * [engineChain] re-ordered so the user's explicit [PREFERRED_ENGINE_ID]
     * (if set to anything other than "auto") is tried first, with the rest
     * of the normal chain kept as a fallback in case that specific engine
     * can't actually get ready (e.g. no network to download a GGUF model).
     * See the "IMPORTANT" note in the class doc for why this exists.
     */
    private suspend fun preferredFirstChain(): List<LocalAiEngine> {
        val preferredId = settings.get(PREFERRED_ENGINE_ID)?.takeIf { it != "auto" } ?: return engineChain
        val preferred = engineChain.firstOrNull { it.info().id == preferredId } ?: return engineChain
        return listOf(preferred) + engineChain.filter { it !== preferred }
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
        selectable.release()
        smolVlm.release()
        current = null
    }
}
