package com.jarvis2.app.ai

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jarvis2.app.data.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Ancienne clé Groq unique (retrocompat/migration -- voir [loadGroqApiKeys]). */
val GROQ_API_KEY = stringPreferencesKey("groq_api_key")

/**
 * Clés API Groq -- MULTIPLES (task : "met en place le multi cles Groq"), stockees
 * en JSON array. Plusieurs cles gratuites (comptes console.groq.com distincts)
 * permettent de dépasser le quota gratuit journalier d'un seul compte : à
 * chaque envoi, [CloudAiClient.send] tourne sur la clé suivante (round-robin,
 * voir [GROQ_KEY_INDEX]) et, si elle échoue (quota atteint, invalide...),
 * essaie automatiquement les autres avant de basculer sur Gemini cloud.
 */
val GROQ_API_KEYS = stringPreferencesKey("groq_api_keys")

/** Index de rotation round-robin entre les clés Groq (voir [GROQ_API_KEYS]). */
val GROQ_KEY_INDEX = intPreferencesKey("groq_key_index")

/** Clé API Gemini cloud (aistudio.google.com, gratuite) -- distincte de Gemini Nano/AICore (on-device). */
val GEMINI_CLOUD_API_KEY = stringPreferencesKey("gemini_cloud_api_key")

/**
 * Ordre de priorite entre l'IA cloud (Groq/Gemini, voir [send]) et le
 * modele IA local (AiEngineManager) pour une conversation libre -- voir
 * ChatViewModel.sendMessage(). Trois valeurs : "cloud_first" (par defaut,
 * comportement historique -- cloud tente en premier s'il est configure,
 * repli local silencieux en cas d'echec), "local_first" (le modele local
 * repond en premier, le cloud n'est tente qu'en repli si le local echoue),
 * ou "groq_only" (mode isolation POUR LES TESTS, task #329, demande
 * explicite "met en place pour pouvoir choisir seulement Groq pour faire
 * des tests") : n'essaie QUE Groq, sans repli Gemini ni repli local, pour
 * verifier son comportement independamment du reste de la cascade.
 * "cloud_first"/"local_first" restent reglables uniquement depuis le chat
 * (voir CommandRouter.kt, matchers "priorite ia locale"/"priorite cloud")
 * -- meme choix architectural que le masquage de calendrier (task
 * #310/#311). "groq_only", lui, est expose directement dans la liste
 * "Moteur IA" de Reglages (voir SettingsScreen.kt/SettingsViewModel.
 * setGroqOnlyTestMode) puisque c'est un mode de test explicitement demande
 * dans l'UI, pas une preference de conversation naturelle.
 */
val AI_PRIORITY_MODE = stringPreferencesKey("ai_priority_mode")

/**
 * Lit la liste des clés Groq configurées (voir [GROQ_API_KEYS]), avec migration
 * douce depuis l'ancien champ unique [GROQ_API_KEY] si la liste n'existe pas
 * encore (aucune perte de clé pour les utilisateurs ayant déjà configuré
 * Jarvis avant l'ajout du multi-clés).
 */
suspend fun loadGroqApiKeys(settings: SettingsDataStore): List<String> {
    val json = settings.get(GROQ_API_KEYS)
    if (json != null) {
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }
    val legacy = settings.get(GROQ_API_KEY)
    return if (!legacy.isNullOrBlank()) listOf(legacy) else emptyList()
}

/** Sauvegarde la liste complete des clés Groq (voir [GROQ_API_KEYS]) ; retire l'ancien champ unique. */
suspend fun saveGroqApiKeys(settings: SettingsDataStore, keys: List<String>) {
    val cleaned = keys.map { it.trim() }.filter { it.isNotBlank() }
    val arr = JSONArray()
    cleaned.forEach { arr.put(it) }
    settings.set(GROQ_API_KEYS, arr.toString())
    settings.remove(GROQ_API_KEY)
}

/**
 * Cascade IA cloud 100% gratuite (task #313) : suite au retour explicite de
 * l'utilisateur ("recherche et trouve une IA cloud 100% gratuite et
 * illimitée"), reprend le principe de l'ancienne Newjarvis (voir
 * ApiClient.kt/Provider.kt de ce dépôt) -- Groq d'abord (gratuit, très
 * rapide, ~100-500k tokens/jour et 14 400 requêtes/jour selon le modèle,
 * largement suffisant pour un usage assistant personnel -- voir
 * console.groq.com/docs/rate-limits), puis Gemini cloud (gratuit,
 * 1000-1500 requêtes/jour) en repli si Groq échoue (clé absente, quota,
 * panne réseau). Aucun des deux n'est *littéralement* sans aucune limite
 * (ça n'existe pas), mais les deux sont gratuits sans carte bancaire et
 * largement suffisants pour ne jamais être atteints en usage normal.
 *
 * Si aucune clé n'est configurée ou que les deux échouent, l'appelant
 * (ChatViewModel) retombe sur le moteur 100% local existant
 * (AiEngineManager) -- jamais d'échec silencieux total, et l'app reste
 * utilisable hors-ligne/sans clé comme avant.
 *
 * Contrairement au moteur local (LocalAiEngine, réponse texte pure), ce
 * client sert à piloter le langage d'action JARVIS_CMD (voir
 * JarvisCommandParser + CommandRouter.executeAction) : le texte brut
 * retourné peut contenir un bloc [JARVIS_CMD:{...}] que l'appelant doit
 * parser et exécuter.
 */
/**
 * Reponse cloud avec sa provenance ([provider] : "Groq" ou "Gemini cloud") --
 * ajoute suite au signalement utilisateur "toujours pas de choix avec Groq
 * en IA principal" : jusqu'ici, rien dans l'UI (ni le header du chat, ni
 * Reglages) ne confirmait QUAND Groq repondait vraiment, meme si le code
 * l'essaie deja en premier par defaut (voir AI_PRIORITY_MODE) -- seul
 * l'indicateur du moteur LOCAL etait visible (ChatScreen.kt), ce qui donnait
 * l'impression que Groq n'etait jamais utilise ni "choisi". Consomme par
 * ChatViewModel.tryCloud pour afficher explicitement la provenance de la
 * derniere reponse (voir ChatUiState.lastReplySource).
 */
data class CloudReply(val text: String, val provider: String)

class CloudAiClient(
    @Suppress("unused") private val context: Context,
    private val settings: SettingsDataStore,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun isConfigured(): Boolean =
        loadGroqApiKeys(settings).isNotEmpty() || !settings.get(GEMINI_CLOUD_API_KEY).isNullOrBlank()

    /** Comme [isConfigured] mais uniquement pour Groq -- utilise par le mode isolation "Groq uniquement" (task #329). */
    suspend fun isGroqConfigured(): Boolean = loadGroqApiKeys(settings).isNotEmpty()

    /**
     * Envoie [systemPrompt] + [history] + [userText] à Groq (en tournant sur
     * toutes les clés configurées -- voir [loadGroqApiKeys] -- round-robin +
     * failover automatique si l'une d'elles est en échec/quota atteint),
     * puis à Gemini cloud en repli si aucune clé Groq n'a fonctionné ou n'est
     * configurée. [history] est volontairement borné (voir takeLast) : pas
     * besoin d'un historique complet pour comprendre une demande d'action,
     * et ça limite la consommation de tokens côté free tier.
     */
    // [allowGeminiFallback] = false pour le mode isolation "Groq uniquement"
    // (task #329) : le repli Gemini est alors saute, meme si une cle Gemini
    // est configuree, pour tester Groq seul et rien d'autre.
    suspend fun send(
        systemPrompt: String,
        history: List<Turn>,
        userText: String,
        allowGeminiFallback: Boolean = true,
    ): Result<CloudReply> =
        withContext(Dispatchers.IO) {
            val groqKeys = loadGroqApiKeys(settings)
            var lastError: Throwable? = null
            if (groqKeys.isNotEmpty()) {
                // Round-robin : commence a la clé suivant celle utilisee au
                // dernier appel, pour repartir la charge/quota entre toutes
                // les clés au fil des conversations plutot que de toujours
                // taper la premiere jusqu'a epuisement de son quota.
                val startIndex = (settings.get(GROQ_KEY_INDEX) ?: 0).mod(groqKeys.size)
                for (offset in groqKeys.indices) {
                    val i = (startIndex + offset) % groqKeys.size
                    val r = runCatching { sendGroq(groqKeys[i], systemPrompt, history, userText) }
                    if (r.isSuccess) {
                        settings.set(GROQ_KEY_INDEX, (i + 1) % groqKeys.size)
                        return@withContext r.map { CloudReply(it, "Groq") }
                    }
                    lastError = r.exceptionOrNull()
                }
                // Toutes les clés Groq configurees ont échoué (quota, clé
                // invalide, panne réseau...) -- on avance quand meme l'index
                // pour ne pas retenter systematiquement la meme clé en tete
                // au prochain message, puis on tente Gemini en repli.
                settings.set(GROQ_KEY_INDEX, (startIndex + 1) % groqKeys.size)
            }
            val geminiKey = settings.get(GEMINI_CLOUD_API_KEY)
            if (allowGeminiFallback && !geminiKey.isNullOrBlank()) {
                val r = runCatching { sendGemini(geminiKey, systemPrompt, history, userText) }
                if (r.isSuccess) return@withContext r.map { CloudReply(it, "Gemini cloud") }
            }
            Result.failure(
                lastError?.let { IllegalStateException("Aucune IA cloud disponible -- dernière erreur Groq : ${it.message}", it) }
                    ?: IllegalStateException("Aucune IA cloud disponible (Groq/Gemini absents ou en échec) -- vérifie les clés dans Réglages."),
            )
        }

    private fun sendGroq(apiKey: String, systemPrompt: String, history: List<Turn>, userText: String): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.takeLast(8).forEach { turn ->
            messages.put(
                JSONObject()
                    .put("role", if (turn.role == Turn.Role.USER) "user" else "assistant")
                    .put("content", turn.text),
            )
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))
        val body = JSONObject().apply {
            // "llama-3.3-70b-versatile" a ete decommissionne cote Groq le
            // 16/08/2026 (voir console.groq.com/docs/deprecations) -- toutes
            // les requetes lui repondaient HTTP 400/404 "model_decommissioned",
            // ce qui faisait echouer CHAQUE appel Groq (peu importe le nombre
            // de cles configurees) et retombait donc systematiquement sur
            // l'IA locale malgre des cles valides. Remplace par
            // "openai/gpt-oss-120b" (modele de production recommande par
            // Groq en remplacement, ~500 tok/s, gratuit sur le tier
            // developpeur sans carte bancaire, voir console.groq.com/docs/models).
            put("model", "openai/gpt-oss-120b")
            put("messages", messages)
            put("temperature", 0.4)
            put("max_tokens", 1024)
        }
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Groq: HTTP ${response.code} — $text")
            val json = JSONObject(text)
            return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    private fun sendGemini(apiKey: String, systemPrompt: String, history: List<Turn>, userText: String): String {
        val contents = JSONArray()
        history.takeLast(8).forEach { turn ->
            contents.put(
                JSONObject()
                    .put("role", if (turn.role == Turn.Role.USER) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text))),
            )
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userText))),
        )
        val body = JSONObject().apply {
            put("contents", contents)
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val request = Request.Builder().url(url).post(body.toString().toRequestBody(jsonMedia)).build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Gemini: HTTP ${response.code} — $text")
            val json = JSONObject(text)
            return json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getString("text")
        }
    }
}
