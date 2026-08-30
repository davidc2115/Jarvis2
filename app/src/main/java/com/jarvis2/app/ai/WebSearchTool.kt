package com.jarvis2.app.ai

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jarvis2.app.data.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class WebSearchResult(val title: String, val snippet: String, val url: String)

/**
 * The one deliberate, user-visible exception to "100% local": when the
 * on-device model doesn't know an answer (it says so explicitly, per the
 * system prompt in [LocalAiEngine]), the UI offers a one-tap web search
 * instead of letting Jarvis hallucinate. This never fires silently — see
 * ui/chat/ChatViewModel.kt for the confirmation step.
 *
 * Requires a search API key configured in Settings (e.g. a Search-provider
 * key of the user's choosing). Without one, [search] returns an empty list
 * and the UI explains why rather than failing silently.
 */
class WebSearchTool(
    private val context: Context,
    private val settings: SettingsDataStore,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun search(query: String, maxResults: Int = 5): Result<List<WebSearchResult>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = settings.get(stringPreferencesKey("web_search_api_key"))
                    ?: return@withContext Result.failure(
                        IllegalStateException("Aucune clé de recherche web configurée dans Réglages.")
                    )
                val endpoint = settings.get(stringPreferencesKey("web_search_endpoint"))
                    ?: "https://serpapi.com/search.json"

                val url = "$endpoint?q=${java.net.URLEncoder.encode(query, "UTF-8")}&api_key=$apiKey&num=$maxResults"
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Recherche web: HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    parseResults(body)
                }
            }
        }

    private fun parseResults(body: String): List<WebSearchResult> {
        val root = JSONObject(body)
        val organic = root.optJSONArray("organic_results") ?: return emptyList()
        return buildList {
            for (i in 0 until organic.length()) {
                val item = organic.getJSONObject(i)
                add(
                    WebSearchResult(
                        title = item.optString("title"),
                        snippet = item.optString("snippet"),
                        url = item.optString("link"),
                    )
                )
            }
        }
    }
}
