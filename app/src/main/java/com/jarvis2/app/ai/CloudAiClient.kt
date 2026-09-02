package com.jarvis2.app.ai

import android.content.Context
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

/** Clé API Groq (console.groq.com, gratuite, sans carte bancaire). */
val GROQ_API_KEY = stringPreferencesKey("groq_api_key")

/** Clé API Gemini cloud (aistudio.google.com, gratuite) -- distincte de Gemini Nano/AICore (on-device). */
val GEMINI_CLOUD_API_KEY = stringPreferencesKey("gemini_cloud_api_key")

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
        !settings.get(GROQ_API_KEY).isNullOrBlank() || !settings.get(GEMINI_CLOUD_API_KEY).isNullOrBlank()

    /**
     * Envoie [systemPrompt] + [history] + [userText] à Groq, puis à Gemini
     * cloud en repli si Groq échoue ou n'est pas configuré. [history] est
     * volontairement borné (voir takeLast) : pas besoin d'un historique
     * complet pour comprendre une demande d'action, et ça limite la
     * consommation de tokens côté free tier.
     */
    suspend fun send(systemPrompt: String, history: List<Turn>, userText: String): Result<String> =
        withContext(Dispatchers.IO) {
            val groqKey = settings.get(GROQ_API_KEY)
            if (!groqKey.isNullOrBlank()) {
                val r = runCatching { sendGroq(groqKey, systemPrompt, history, userText) }
                if (r.isSuccess) return@withContext r
            }
            val geminiKey = settings.get(GEMINI_CLOUD_API_KEY)
            if (!geminiKey.isNullOrBlank()) {
                val r = runCatching { sendGemini(geminiKey, systemPrompt, history, userText) }
                if (r.isSuccess) return@withContext r
            }
            Result.failure(IllegalStateException("Aucune IA cloud disponible (Groq/Gemini absents ou en échec) -- vérifie les clés dans Réglages."))
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
            put("model", "llama-3.3-70b-versatile")
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
