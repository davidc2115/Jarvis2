package com.jarvis2.app.ai.smolvlm

import android.content.Context
import com.jarvis2.app.ai.EngineInfo
import com.jarvis2.app.ai.LocalAiEngine
import com.jarvis2.app.ai.ModelDownloader
import com.jarvis2.app.ai.recommendedInferenceThreads
import com.jarvis2.app.ai.Turn
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import com.llamatik.library.platform.MultimodalBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Moteur local par defaut de Jarvis2 : SmolVLM2-500M-Video-Instruct via
 * llama.cpp (wrapper Kotlin/AAR pre-construit "Llamatik", voir
 * https://github.com/ferranpons/llamatik). Contrairement a AICore (reserve a
 * une poignee d'appareils) et a MediaPipe/Gemma (necessite un fichier .task
 * de plusieurs centaines de Mo importe manuellement ou verrouille derriere
 * une licence Hugging Face), ce moteur :
 *  - fonctionne sur n'importe quel telephone ARM64, sans condition materielle ;
 *  - se telecharge tout seul au premier lancement depuis un depot Hugging
 *    Face NON verrouille (ggml-org/SmolVLM2-500M-Video-Instruct-GGUF,
 *    licence Apache-2.0, aucune connexion/jeton requis) -- voir
 *    [ModelDownloader] ;
 *  - est nativement multimodal (texte + image) via libmtmd/CLIP, meme si
 *    seul le chemin texte ([generate]/[generateStreaming]) est cable dans le
 *    chat pour l'instant ; [describeImage] est deja fonctionnel et pret a
 *    etre branche des qu'un point d'entree "joindre une photo" existe dans
 *    l'UI (voir ui/chat/ChatScreen.kt).
 *
 * Le fichier modele (~420 Mo) et son mmproj (~104 Mo) sont stockes dans le
 * stockage prive de l'app, jamais dans le depot Git/l'APK (voir
 * ModelDownloader pour le pourquoi).
 */
class SmolVlmEngine(
    private val context: Context,
    /** Voir la doc du meme parametre sur SelectableLlmEngine -- meme raison d'etre. */
    private val onStatusChanged: (EngineInfo) -> Unit = {},
) : LocalAiEngine {

    private var ready = false
    private var lastError: String? = null
    private var downloadStatus: String? = null
    private var multimodalReady = false

    private val modelsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/smolvlm2")

    private val modelFile get() = File(modelsDir, MODEL_FILENAME)
    private val mmprojFile get() = File(modelsDir, MMPROJ_FILENAME)

    override suspend fun prepare(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            downloadStatus = "Téléchargement du modèle IA local SmolVLM2 : 0 / ${MODEL_SIZE_BYTES / 1_000_000} Mo (0 %)…"
            onStatusChanged(info())
            downloadWithProgress("modèle IA local SmolVLM2", MODEL_URL, modelFile, MODEL_SIZE_BYTES)

            downloadStatus = "Téléchargement du module vision SmolVLM2 : 0 / ${MMPROJ_SIZE_BYTES / 1_000_000} Mo (0 %)…"
            onStatusChanged(info())
            downloadWithProgress("module vision SmolVLM2", MMPROJ_URL, mmprojFile, MMPROJ_SIZE_BYTES)
            downloadStatus = "Chargement de SmolVLM2 en mémoire…"
            onStatusChanged(info())

            // Reglages releves par rapport aux defauts de Llamatik (temperature 0.7,
            // repeatPenalty 1.1) : un modele aussi petit que SmolVLM2-500M part plus
            // facilement en boucle de repetition ("la meme phrase se repete plusieurs
            // fois", signale par l'utilisateur) faute de detecter proprement le token
            // de fin de generation. Un repeatPenalty plus marque et une temperature/
            // topP legerement resserres reduisent nettement ce risque sans degrader la
            // qualite pour un usage chat conversationnel. Doivent etre appliques AVANT
            // initGenerateModel : contextLength/useMmap/flashAttention/numThreads/
            // batchSize ne prennent effet qu'au chargement du modele (voir doc
            // Llamatik). Un filet de securite complementaire existe aussi cote
            // AiEngineManager (voir ai/TextDedup.kt) au cas ou une boucle survienne
            // malgre tout.
            LlamaBridge.updateGenerateParams(
                temperature = 0.6f,
                maxTokens = 400,
                topP = 0.9f,
                topK = 40,
                repeatPenalty = 1.3f,
                contextLength = 4096,
                numThreads = recommendedInferenceThreads(),
                useMmap = true,
                flashAttention = true,
                batchSize = 512,
                gpuLayers = 0,
            )

            val loaded = LlamaBridge.initGenerateModel(modelFile.absolutePath)
            if (!loaded) {
                throw IllegalStateException("LlamaBridge.initGenerateModel a échoué pour ${modelFile.absolutePath}")
            }
            ready = true
            downloadStatus = null
            onStatusChanged(info())
        }.onFailure {
            lastError = it.message
            downloadStatus = null
            ready = false
            onStatusChanged(info())
        }
    }

    /**
     * Enveloppe ModelDownloader.downloadIfMissing avec un callback de
     * progression qui met a jour [downloadStatus] et notifie
     * [onStatusChanged] a chaque pourcentage entier franchi (pas a chaque
     * chunk de 64 Ko, pour ne pas noyer l'UI de mises a jour inutiles).
     */
    private suspend fun downloadWithProgress(label: String, url: String, destFile: File, expectedSizeBytes: Long) {
        var lastReportedPercent = -1
        ModelDownloader.downloadIfMissing(
            url = url,
            destFile = destFile,
            expectedSizeBytes = expectedSizeBytes,
            onProgress = { done, total ->
                val totalKnown = total.takeIf { it > 0 } ?: expectedSizeBytes
                val percent = if (totalKnown > 0) ((done * 100) / totalKnown).toInt() else -1
                if (percent != lastReportedPercent) {
                    lastReportedPercent = percent
                    downloadStatus = "Téléchargement du $label : ${done / 1_000_000} / ${totalKnown / 1_000_000} Mo" +
                        (if (percent >= 0) " ($percent %)" else "") + "…"
                    onStatusChanged(info())
                }
            },
        ).getOrThrow()
    }

    override fun info() = EngineInfo(
        id = "smolvlm2-llamacpp",
        displayName = "SmolVLM2 500M (embarqué, texte + image)",
        isFullyLocal = true,
        isReady = ready,
        notes = downloadStatus ?: lastError ?: "Modèle par défaut, téléchargé une seule fois puis 100% hors-ligne.",
    )

    private fun renderPrompt(prompt: String, history: List<Turn>, systemPrompt: String): String {
        val messages = buildList {
            add("system" to systemPrompt.trim())
            history.takeLast(8).forEach { turn ->
                val role = when (turn.role) {
                    Turn.Role.USER -> "user"
                    Turn.Role.ASSISTANT -> "assistant"
                    Turn.Role.SYSTEM -> "system"
                }
                add(role to turn.text)
            }
            add("user" to prompt)
        }
        return LlamaBridge.applyChatTemplate(messages, addAssistantPrefix = true)
            ?: buildString {
                appendLine(systemPrompt.trim())
                history.takeLast(8).forEach { appendLine("${it.role}: ${it.text}") }
                appendLine("USER: $prompt")
                append("ASSISTANT:")
            }
    }

    override suspend fun generate(prompt: String, history: List<Turn>, systemPrompt: String): Result<String> =
        withContext(Dispatchers.Default) {
            if (!ready) return@withContext Result.failure(IllegalStateException("SmolVLM2 non prêt"))
            runCatching { LlamaBridge.generate(renderPrompt(prompt, history, systemPrompt)) }
        }

    override fun generateStreaming(prompt: String, history: List<Turn>, systemPrompt: String): Flow<String> =
        callbackFlow {
            if (!ready) {
                close(IllegalStateException("SmolVLM2 non prêt"))
                return@callbackFlow
            }
            val built = StringBuilder()
            LlamaBridge.generateStream(
                renderPrompt(prompt, history, systemPrompt),
                object : GenStream {
                    override fun onDelta(text: String) {
                        built.append(text)
                        trySend(built.toString())
                    }
                    override fun onComplete() { close() }
                    override fun onError(message: String) { close(IllegalStateException(message)) }
                },
            )
            awaitClose { /* pas d'annulation cote natif exposee pour ce chemin */ }
        }

    /**
     * Analyse une image (JPEG/PNG/BMP) avec [prompt] comme question, via le
     * pipeline vision natif (libmtmd/CLIP). Initialise MultimodalBridge au
     * premier appel seulement (evite de charger le contexte vision pour les
     * utilisateurs qui n'envoient jamais de photo). Pas encore appelee
     * depuis le chat (aucun bouton "joindre une image" dans ChatScreen.kt
     * pour l'instant) -- prete a l'emploi pour une future integration.
     */
    suspend fun describeImage(imageBytes: ByteArray, prompt: String): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            if (!multimodalReady) {
                if (!modelFile.exists() || !mmprojFile.exists()) {
                    throw IllegalStateException("Modèle vision SmolVLM2 pas encore téléchargé")
                }
                val loaded = MultimodalBridge.initModel(modelFile.absolutePath, mmprojFile.absolutePath)
                if (!loaded) throw IllegalStateException("MultimodalBridge.initModel a échoué")
                multimodalReady = true
            }
            val deferred = CompletableDeferred<String>()
            val built = StringBuilder()
            MultimodalBridge.analyzeImageBytesStream(
                imageBytes,
                prompt,
                object : GenStream {
                    override fun onDelta(text: String) { built.append(text) }
                    override fun onComplete() { deferred.complete(built.toString()) }
                    override fun onError(message: String) { deferred.completeExceptionally(IllegalStateException(message)) }
                },
            )
            deferred.await()
        }
    }

    override fun release() {
        if (multimodalReady) {
            MultimodalBridge.release()
            multimodalReady = false
        }
        LlamaBridge.shutdown()
        ready = false
    }

    private companion object {
        // ggml-org/SmolVLM2-500M-Video-Instruct-GGUF -- licence Apache-2.0,
        // depot public NON verrouille (verifie via l'API HF : "gated": false),
        // donc telechargeable par n'importe quel utilisateur sans compte ni
        // jeton. Quantification Q8_0 choisie pour la qualite/taille (le
        // fichier f16 existe aussi mais fait le double pour un gain de
        // qualite marginal sur un modele deja tres petit).
        const val MODEL_FILENAME = "SmolVLM2-500M-Video-Instruct-Q8_0.gguf"
        const val MMPROJ_FILENAME = "mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf"
        const val MODEL_URL =
            "https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/main/$MODEL_FILENAME"
        const val MMPROJ_URL =
            "https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/main/$MMPROJ_FILENAME"
        const val MODEL_SIZE_BYTES = 436_808_704L
        const val MMPROJ_SIZE_BYTES = 108_785_184L
    }
}
