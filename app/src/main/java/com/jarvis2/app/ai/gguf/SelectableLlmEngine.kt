package com.jarvis2.app.ai.gguf

import android.content.Context
import com.jarvis2.app.ai.EngineInfo
import com.jarvis2.app.ai.LocalAiEngine
import com.jarvis2.app.ai.ModelDownloader
import com.jarvis2.app.ai.recommendedInferenceThreads
import com.jarvis2.app.ai.Turn
import com.jarvis2.app.data.SettingsDataStore
import com.jarvis2.app.ui.settings.SELECTED_LOCAL_MODEL
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Moteur local optionnel, choisi par l'utilisateur dans Réglages parmi
 * [LocalGgufModel] (Qwen 2.5 1.5B, Phi-3.5 mini, Dolphin 3.0) -- remplace
 * l'ancien telechargement Gemma 3 1B (gated, jeton Hugging Face requis).
 * Contrairement a Gemma, aucun des trois modeles du catalogue n'exige de
 * compte ni de jeton : le telechargement demarre directement des que
 * l'utilisateur choisit un modele, exactement comme SmolVlmEngine le fait
 * deja pour son propre modele par defaut (voir ai/smolvlm/SmolVlmEngine.kt).
 *
 * Tant qu'aucun modele n'est selectionne (reglage vide/"none"), [prepare]
 * echoue immediatement sans la moindre requete reseau -- c'est ce qui
 * permet a AiEngineManager de passer directement a SmolVlmEngine sans
 * latence quand l'utilisateur n'a rien choisi ici.
 *
 * Partage le meme pont natif Llamatik/llama.cpp que SmolVlmEngine (un seul
 * contexte natif global cote SDK) : seul un des deux peut avoir un modele
 * reellement charge a un instant donne, ce qui correspond exactement a la
 * facon dont AiEngineManager choisit un unique moteur "current" a la fois.
 */
class SelectableLlmEngine(
    private val context: Context,
    private val settings: SettingsDataStore,
    /**
     * Rappelee a chaque changement d'etat significatif (debut/avancement/fin
     * de telechargement) -- permet a AiEngineManager de relayer une
     * progression en direct (voir son StateFlow activeEngine) au lieu de ne
     * publier qu'un instantane avant/apres tout le prepare(). Sans ca, un
     * telechargement de plusieurs centaines de Mo (Phi-3.5 mini fait 2.4 Go)
     * pouvait sembler bloque/inexistant a l'ecran Reglages : seul un
     * spinner generique s'affichait, sans aucun texte de progression.
     */
    private val onStatusChanged: (EngineInfo) -> Unit = {},
) : LocalAiEngine {

    private var ready = false
    private var lastError: String? = null
    private var downloadStatus: String? = null
    private var loadedModel: LocalGgufModel? = null

    private val modelsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/selectable")

    override suspend fun prepare(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val selectedId = settings.get(SELECTED_LOCAL_MODEL)
            val model = LocalGgufModel.byId(selectedId)
                ?: run {
                    ready = false
                    lastError = null
                    downloadStatus = null
                    throw NoOptionalModelSelected()
                }

            if (ready && loadedModel == model) return@runCatching

            val modelFile = File(modelsDir, model.filename)
            loadedModel = null
            downloadStatus = "Téléchargement de ${model.displayName} : 0 / ${model.sizeBytes / 1_000_000} Mo (0 %)…"
            onStatusChanged(info())
            var lastReportedPercent = -1
            ModelDownloader.downloadIfMissing(
                url = model.downloadUrl,
                destFile = modelFile,
                expectedSizeBytes = model.sizeBytes,
                onProgress = { done, total ->
                    val totalKnown = total.takeIf { it > 0 } ?: model.sizeBytes
                    val percent = if (totalKnown > 0) ((done * 100) / totalKnown).toInt() else -1
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        downloadStatus = "Téléchargement de ${model.displayName} : ${done / 1_000_000} / ${totalKnown / 1_000_000} Mo" +
                            (if (percent >= 0) " ($percent %)" else "") + "…"
                        onStatusChanged(info())
                    }
                },
            ).getOrThrow()
            downloadStatus = "Chargement de ${model.displayName} en mémoire…"
            onStatusChanged(info())

            // Meme reglage anti-repetition que SmolVlmEngine (voir sa doc) --
            // pertinent pour n'importe quel petit modele quantifie, pas
            // seulement SmolVLM2. Doit etre appele avant initGenerateModel.
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
            loadedModel = model
            downloadStatus = null
            onStatusChanged(info())
        }.onFailure {
            if (it !is NoOptionalModelSelected) {
                lastError = it.message
                downloadStatus = null
                onStatusChanged(info())
            }
            ready = false
        }
    }

    override fun info() = EngineInfo(
        id = "selectable-gguf",
        displayName = loadedModel?.displayName ?: "Modèle local optionnel",
        isFullyLocal = true,
        isReady = ready,
        notes = downloadStatus
            ?: lastError
            ?: if (ready) "Aucun compte ni jeton requis (${loadedModel?.license})." else "Aucun modèle optionnel sélectionné (voir Réglages).",
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
            if (!ready) return@withContext Result.failure(IllegalStateException("Modèle local optionnel non prêt"))
            runCatching { LlamaBridge.generate(renderPrompt(prompt, history, systemPrompt)) }
        }

    override fun generateStreaming(prompt: String, history: List<Turn>, systemPrompt: String): Flow<String> =
        callbackFlow {
            if (!ready) {
                close(IllegalStateException("Modèle local optionnel non prêt"))
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

    override fun release() {
        if (ready) {
            LlamaBridge.shutdown()
            ready = false
        }
        // Sans ca, apres un release() le champ restait sur l'ancien modele
        // charge (ex: Qwen) alors que le contexte natif est deja libere --
        // inoffensif tant que [ready] gate bien tous les appels, mais
        // trompeur pour un futur lecteur/debug (voir task #321 : crash au
        // changement de modele, cause reelle = AiEngineManager.refresh()
        // sans verrou -- voir sa doc -- pas ce champ, mais autant le garder
        // propre puisqu'on est dans ce fichier).
        loadedModel = null
    }

    /** Signal interne "rien a faire" -- pas une vraie erreur, ne doit pas remplir [lastError]. */
    private class NoOptionalModelSelected : Exception()
}
