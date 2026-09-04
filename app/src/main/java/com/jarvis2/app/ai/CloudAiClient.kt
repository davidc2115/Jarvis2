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
 * essaie automatiquement les autres avant de passer au fournisseur suivant.
 */
val GROQ_API_KEYS = stringPreferencesKey("groq_api_keys")

/** Index de rotation round-robin entre les clés Groq (voir [GROQ_API_KEYS]). */
val GROQ_KEY_INDEX = intPreferencesKey("groq_key_index")

/** Clé API Gemini cloud (aistudio.google.com, gratuite) -- distincte de Gemini Nano/AICore (on-device). */
val GEMINI_CLOUD_API_KEY = stringPreferencesKey("gemini_cloud_api_key")

/**
 * Ordre de priorite entre l'IA cloud (cascade multi-fournisseurs, voir
 * [send]) et le modele IA local (AiEngineManager) pour une conversation
 * libre -- voir ChatViewModel.sendMessage(). Trois valeurs : "cloud_first"
 * (par defaut, comportement historique -- cloud tente en premier s'il est
 * configure, repli local silencieux en cas d'echec), "local_first" (le
 * modele local repond en premier, le cloud n'est tente qu'en repli si le
 * local echoue), ou "groq_only" (mode isolation POUR LES TESTS, task #329,
 * demande explicite "met en place pour pouvoir choisir seulement Groq pour
 * faire des tests") : n'essaie QUE Groq, sans repli vers le reste de la
 * cascade ni vers le local, pour verifier son comportement independamment
 * du reste. "cloud_first"/"local_first" restent reglables uniquement
 * depuis le chat (voir CommandRouter.kt, matchers "priorite ia locale"/
 * "priorite cloud") -- meme choix architectural que le masquage de
 * calendrier (task #310/#311). "groq_only", lui, est expose directement
 * dans la liste "Moteur IA" de Reglages (voir SettingsScreen.kt/
 * SettingsViewModel.setGroqOnlyTestMode) puisque c'est un mode de test
 * explicitement demande dans l'UI, pas une preference de conversation
 * naturelle.
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
 * Fournisseurs IA cloud additionnels (portage Newjarvis, davidc2115/Newjarvis
 * -- Provider.kt/ApiClient.kt -- fusion task #4, "que Groq reponde
 * parfaitement" + comprehension). Newjarvis tourne deja sur 10+ fournisseurs
 * en cascade depuis longtemps ; Jarvis2 n'avait que Groq+Gemini en dur.
 * [POLLINATIONS] est le filet de secours GRATUIT et SANS AUCUNE CLE (acces
 * anonyme officiel, endpoint compatible OpenAI) place en tout dernier
 * recours -- garantit une reponse cloud meme si l'utilisateur n'a configure
 * AUCUNE clé nulle part (avant : silence total ou repli local uniquement).
 * GROQ n'apparait pas dans [FALLBACK_ORDER] : sa rotation multi-cles est
 * geree separement en tete de [CloudAiClient.send] (voir sa doc).
 */
enum class CloudProvider(
    val displayName: String,
    val baseUrl: String,
    val model: String,
    val needsApiKey: Boolean = true,
) {
    GROQ("Groq", "https://api.groq.com/openai/v1/chat/completions", "openai/gpt-oss-120b"),

    // gemini-3.7-flash : modele STABLE le plus recent au moment de ce portage
    // (verifie aupres de la doc/pricing Google, septembre 2026) -- remplace
    // gemini-2.5-flash, plus ancien. Newjarvis avait deja teste et ecarte
    // gemini-3.1-pro-preview (quota gratuit HTTP 429 systematique sur toutes
    // les cles testees : les modeles "Preview" ont un quota gratuit bien
    // plus restrictif qu'un modele "Stable", independamment de tout
    // abonnement Gemini payant).
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent", "gemini-3.7-flash"),
    OPENAI("ChatGPT (OpenAI)", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
    CLAUDE("Claude (Anthropic)", "https://api.anthropic.com/v1/messages", "claude-sonnet-4-5"),
    MISTRAL("Mistral AI", "https://api.mistral.ai/v1/chat/completions", "mistral-large-latest"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat"),
    PERPLEXITY("Perplexity AI", "https://api.perplexity.ai/chat/completions", "sonar"),
    TOGETHER("Together AI", "https://api.together.xyz/v1/chat/completions", "mistralai/Mixtral-8x7B-Instruct-v0.1"),
    OPENROUTER("OpenRouter (multi-modeles)", "https://openrouter.ai/api/v1/chat/completions", "openai/gpt-4o-mini"),
    POLLINATIONS("Pollinations (gratuit, sans clé, dernier recours)", "https://text.pollinations.ai/openai", "openai", needsApiKey = false);

    companion object {
        /** Ordre de repli du reste de la cascade -- GROQ deja tente separement (rotation multi-cles), voir [CloudAiClient.send]. */
        val FALLBACK_ORDER = listOf(GEMINI, CLAUDE, OPENAI, MISTRAL, DEEPSEEK, PERPLEXITY, TOGETHER, OPENROUTER, POLLINATIONS)
    }
}

/** Clé de préférence pour la clé API d'un fournisseur additionnel (voir [CloudProvider]) -- une seule clé par fournisseur (pas de rotation, contrairement à Groq). */
private fun cloudApiKeyPrefKey(provider: CloudProvider) = stringPreferencesKey("cloud_api_key_${provider.name.lowercase()}")

/**
 * Lit la clé configurée pour [provider]. GEMINI reste un cas particulier :
 * réutilise le champ existant [GEMINI_CLOUD_API_KEY] (Réglages ne change
 * pas pour lui) plutôt qu'une nouvelle clé de préférence dédiée, pour ne
 * perdre aucune clé déjà configurée par un utilisateur existant.
 */
suspend fun loadCloudApiKey(settings: SettingsDataStore, provider: CloudProvider): String =
    when (provider) {
        CloudProvider.GEMINI -> settings.get(GEMINI_CLOUD_API_KEY)
        CloudProvider.POLLINATIONS -> ""
        else -> settings.get(cloudApiKeyPrefKey(provider))
    }.orEmpty()

/** Sauvegarde la clé de [provider] (voir [loadCloudApiKey]) -- vide = supprime le réglage. */
suspend fun saveCloudApiKey(settings: SettingsDataStore, provider: CloudProvider, key: String) {
    val trimmed = key.trim()
    when (provider) {
        CloudProvider.GEMINI -> if (trimmed.isBlank()) settings.remove(GEMINI_CLOUD_API_KEY) else settings.set(GEMINI_CLOUD_API_KEY, trimmed)
        CloudProvider.POLLINATIONS -> Unit // pas de clé, no-op
        else -> if (trimmed.isBlank()) settings.remove(cloudApiKeyPrefKey(provider)) else settings.set(cloudApiKeyPrefKey(provider), trimmed)
    }
}

/**
 * Cascade IA cloud multi-fournisseurs (task #313, elargie task #4 --
 * portage Newjarvis, voir doc de [CloudProvider]) : Groq d'abord (gratuit,
 * tres rapide, multi-cles avec rotation), puis le reste de
 * [CloudProvider.FALLBACK_ORDER] dans l'ordre (Gemini, Claude, OpenAI,
 * Mistral, DeepSeek, Perplexity, Together, OpenRouter), et enfin
 * Pollinations (gratuit, sans clé, garantit une reponse cloud meme sans
 * configuration). Chaque fournisseur n'est tente QUE s'il a une clé
 * configuree (sauf Pollinations) -- ceux sans clé sont silencieusement
 * ignores plutot que de faire echouer la cascade.
 *
 * Si aucune clé n'est configurée ou que tout échoue, l'appelant
 * (ChatViewModel) retombe sur le moteur 100% local existant
 * (AiEngineManager) -- jamais d'échec silencieux total, et l'app reste
 * utilisable hors-ligne/sans clé comme avant.
 *
 * Contrairement au moteur local (LocalAiEngine, réponse texte pure), ce
 * client sert à piloter le langage d'action JARVIS_CMD (voir
 * JarvisCommandParser + CommandRouter.executeAction) : le texte brut
 * retourné peut contenir un ou plusieurs blocs [JARVIS_CMD:{...}] que
 * l'appelant doit parser et exécuter.
 */
/**
 * Reponse cloud avec sa provenance ([provider] : nom d'affichage du
 * fournisseur qui a repondu, ex "Groq"/"Google Gemini"/"Pollinations...")
 * -- ajoute suite au signalement utilisateur "toujours pas de choix avec
 * Groq en IA principal" : jusqu'ici, rien dans l'UI (ni le header du chat,
 * ni Reglages) ne confirmait QUAND Groq repondait vraiment, meme si le
 * code l'essaie deja en premier par defaut (voir AI_PRIORITY_MODE) -- seul
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

    /**
     * Vrai si au moins UNE vraie clé est configuree quelque part (Groq ou
     * un des fournisseurs de [CloudProvider.FALLBACK_ORDER] qui en exige
     * une). Volontairement FAUX si rien n'est configure, meme si
     * Pollinations (gratuit, sans clé) resterait techniquement utilisable
     * -- preserve la promesse existante "sans clé, Jarvis reste 100% local/
     * hors-ligne" (voir Settings/ChatViewModel) pour un utilisateur qui n'a
     * VOLONTAIREMENT rien configure. Pollinations sert uniquement de DERNIER
     * FILET dans [send] une fois qu'au moins un fournisseur a deja ete tente
     * et a echoue -- jamais comme unique raison de partir en reseau.
     */
    suspend fun isConfigured(): Boolean =
        loadGroqApiKeys(settings).isNotEmpty() ||
            CloudProvider.FALLBACK_ORDER.filter { it.needsApiKey }.any { loadCloudApiKey(settings, it).isNotBlank() }

    /** Comme [isConfigured] mais uniquement pour Groq -- utilise par le mode isolation "Groq uniquement" (task #329). */
    suspend fun isGroqConfigured(): Boolean = loadGroqApiKeys(settings).isNotEmpty()

    /**
     * Envoie [systemPrompt] + [history] + [userText] à Groq (en tournant sur
     * toutes les clés configurées -- voir [loadGroqApiKeys] -- round-robin +
     * failover automatique si l'une d'elles est en échec/quota atteint),
     * puis au reste de [CloudProvider.FALLBACK_ORDER] si Groq n'a pas
     * fonctionné ou n'est pas configuré. [history] est volontairement borné
     * (voir takeLast) : pas besoin d'un historique complet pour comprendre
     * une demande d'action, et ça limite la consommation de tokens côté
     * free tier.
     */
    // [allowFallbackChain] = false pour le mode isolation "Groq uniquement"
    // (task #329) : tout le reste de la cascade (Gemini, Claude, OpenAI...,
    // Pollinations) est alors saute, meme si des clés sont configurées,
    // pour tester Groq seul et rien d'autre.
    suspend fun send(
        systemPrompt: String,
        history: List<Turn>,
        userText: String,
        allowFallbackChain: Boolean = true,
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
                    val r = runCatching { sendCloudProvider(CloudProvider.GROQ, groqKeys[i], systemPrompt, history, userText) }
                    if (r.isSuccess) {
                        settings.set(GROQ_KEY_INDEX, (i + 1) % groqKeys.size)
                        return@withContext r.map { CloudReply(it, CloudProvider.GROQ.displayName) }
                    }
                    lastError = r.exceptionOrNull()
                }
                // Toutes les clés Groq configurees ont échoué (quota, clé
                // invalide, panne réseau...) -- on avance quand meme l'index
                // pour ne pas retenter systematiquement la meme clé en tete
                // au prochain message, puis on tente le reste de la cascade.
                settings.set(GROQ_KEY_INDEX, (startIndex + 1) % groqKeys.size)
            }
            if (allowFallbackChain) {
                for (provider in CloudProvider.FALLBACK_ORDER) {
                    val apiKey = loadCloudApiKey(settings, provider)
                    if (provider.needsApiKey && apiKey.isBlank()) continue // pas configure -- ignore silencieusement, essaie le suivant
                    val r = runCatching { sendCloudProvider(provider, apiKey, systemPrompt, history, userText) }
                    if (r.isSuccess) return@withContext r.map { CloudReply(it, provider.displayName) }
                    lastError = r.exceptionOrNull()
                }
            }
            Result.failure(
                lastError?.let { IllegalStateException("Aucune IA cloud disponible -- dernière erreur : ${it.message}", it) }
                    ?: IllegalStateException("Aucune IA cloud disponible -- vérifie les clés dans Réglages."),
            )
        }

    /** Envoie a [provider] avec la [apiKey] fournie -- dispatche vers le bon format de requete HTTP selon le fournisseur (Claude et Gemini ont un format different d'OpenAI). */
    private fun sendCloudProvider(provider: CloudProvider, apiKey: String, systemPrompt: String, history: List<Turn>, userText: String): String =
        when (provider) {
            CloudProvider.CLAUDE -> sendClaude(apiKey, systemPrompt, history, userText)
            CloudProvider.GEMINI -> sendGemini(apiKey, systemPrompt, history, userText)
            else -> sendOpenAiCompatible(provider, apiKey, systemPrompt, history, userText)
        }

    /**
     * Format "OpenAI-compatible chat/completions", partage par Groq, OpenAI,
     * Mistral, DeepSeek, Perplexity, Together, OpenRouter et Pollinations
     * (endpoint gratuit sans clé) -- seuls l'URL/le modele/la presence d'une
     * clé changent d'un fournisseur a l'autre (voir [CloudProvider]).
     */
    private fun sendOpenAiCompatible(provider: CloudProvider, apiKey: String, systemPrompt: String, history: List<Turn>, userText: String): String {
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
            put("model", provider.model)
            put("messages", messages)
            put("temperature", 0.4)
            // 2048 (pas 1024) : une reponse detaillee (fiche contact complete,
            // note longue, JARVIS_CMD avec plusieurs parametres) peut etre
            // coupee net en plein milieu avec une limite trop basse.
            put("max_tokens", 2048)
        }
        val requestBuilder = Request.Builder()
            .url(provider.baseUrl)
            .post(body.toString().toRequestBody(jsonMedia))
        if (apiKey.isNotBlank()) requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        if (provider == CloudProvider.OPENROUTER) {
            // OpenRouter exige/recommande ces deux en-tetes pour identifier l'app appelante.
            requestBuilder
                .addHeader("HTTP-Referer", "https://github.com/davidc2115/Jarvis2")
                .addHeader("X-Title", "Jarvis2 Android")
        }
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("${provider.displayName}: HTTP ${response.code} — $text")
            val json = JSONObject(text)
            return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    /** Format Anthropic (Claude) -- distinct du format OpenAI (system a part, pas de role "system" dans messages, en-tetes different). Portage Newjarvis/sendClaude. */
    private fun sendClaude(apiKey: String, systemPrompt: String, history: List<Turn>, userText: String): String {
        val messages = JSONArray()
        history.takeLast(8).forEach { turn ->
            messages.put(
                JSONObject()
                    .put("role", if (turn.role == Turn.Role.USER) "user" else "assistant")
                    .put("content", turn.text),
            )
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))
        val body = JSONObject()
            .put("model", CloudProvider.CLAUDE.model)
            .put("max_tokens", 2048)
            .put("system", systemPrompt)
            .put("messages", messages)
            .toString()
            .toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url(CloudProvider.CLAUDE.baseUrl)
            .post(body)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Claude: HTTP ${response.code} — $text")
            val json = JSONObject(text)
            val content = json.optJSONArray("content")
            if (content != null && content.length() > 0) return content.getJSONObject(0).optString("text", "")
            error("Claude: format de reponse inattendu — $text")
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
        val separator = if (CloudProvider.GEMINI.baseUrl.contains("?")) "&" else "?"
        val url = "${CloudProvider.GEMINI.baseUrl}${separator}key=$apiKey"
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
