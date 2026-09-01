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

/** Un resultat de recherche enrichi du texte reellement extrait de la page. */
data class WebSearchExtract(val title: String, val url: String, val extractedText: String)

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

    /**
     * Va plus loin que [search] : recupere en plus le texte reel de chaque
     * page (pas juste le titre/extrait renvoyes par le moteur de recherche),
     * pour que Jarvis puisse en extraire l'info utile et repondre directement
     * dans le chat plutot que de se contenter d'afficher une liste de liens.
     * Chaque page qui echoue a se charger est simplement omise (best-effort) :
     * on garde ce qu'on a pu recuperer plutot que de tout faire echouer.
     */
    suspend fun searchAndExtract(query: String, maxResults: Int = 4): Result<List<WebSearchExtract>> =
        withContext(Dispatchers.IO) {
            search(query, maxResults).map { results ->
                results.mapNotNull { r ->
                    val pageText = fetchPageText(r.url)
                    val text = when {
                        !pageText.isNullOrBlank() -> pageText
                        r.snippet.isNotBlank() -> r.snippet // repli : au moins l'extrait du moteur de recherche
                        else -> null
                    }
                    text?.let { WebSearchExtract(title = r.title.ifBlank { r.url }, url = r.url, extractedText = it) }
                }
            }
        }

    /**
     * Recupere une page et en extrait le texte lisible en retirant
     * balises/scripts/styles -- extraction volontairement simple (regex),
     * suffisante pour donner a l'IA locale de quoi resumer sans ajouter de
     * dependance de parsing HTML lourde. Retourne null en cas d'echec
     * (page injoignable, non-HTML, etc.) plutot que de lever une exception.
     */
    private fun fetchPageText(url: String): String? = runCatching {
        val request = Request.Builder().url(url).get().header("User-Agent", "Mozilla/5.0 (Jarvis)").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.isNotBlank() && !contentType.contains("html", ignoreCase = true) && !contentType.contains("text", ignoreCase = true)) {
                return null
            }
            val html = response.body?.string().orEmpty()
            extractReadableText(html).takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /**
     * Extraction du texte lisible d'une page HTML -- volontairement basee sur des
     * regex (pas de dependance de parsing HTML lourde), mais avec deux ameliorations
     * cruciales par rapport a la version initiale (cause racine probable des "infos
     * incorrectes" signalees par l'utilisateur) :
     *
     * 1. Les blocs de PUR BRUIT (nav/header/footer/aside/menus/formulaires/boutons)
     *    sont retires AVANT le reste -- sans ca, les 4000 premiers caracteres d'une
     *    page (menu, bandeau cookies, liens "articles similaires"...) contiennent
     *    tres souvent ZERO contenu utile, et le petit modele local, oblige de
     *    repondre quand meme, invente une reponse plausible a partir de ce bruit.
     * 2. Si la page contient une balise <article> ou <main> (quasi systematique sur
     *    les sites d'actualite/blogs/wikis), on isole SON contenu au lieu de garder
     *    tout le <body> -- c'est la ou se trouve la vraie reponse.
     */
    private fun extractReadableText(html: String): String {
        var text = html
        text = Regex("(?is)<script.*?</script>").replace(text, " ")
        text = Regex("(?is)<style.*?</style>").replace(text, " ")
        text = Regex("(?is)<noscript.*?</noscript>").replace(text, " ")
        text = Regex("(?is)<!--.*?-->").replace(text, " ")
        // Bruit structurel : jamais la reponse, souvent la majorite des premiers
        // caracteres de la page (menu de navigation, pied de page, barre laterale,
        // formulaires de newsletter/recherche, boutons de partage).
        text = Regex("(?is)<nav.*?</nav>").replace(text, " ")
        text = Regex("(?is)<header.*?</header>").replace(text, " ")
        text = Regex("(?is)<footer.*?</footer>").replace(text, " ")
        text = Regex("(?is)<aside.*?</aside>").replace(text, " ")
        text = Regex("(?is)<form.*?</form>").replace(text, " ")
        // Si un conteneur d'article/contenu principal existe, on ne garde QUE lui --
        // sinon on continue avec le document entier (deja nettoye du bruit ci-dessus).
        val articleMatch = Regex("(?is)<article[^>]*>(.*?)</article>").find(text)
            ?: Regex("(?is)<main[^>]*>(.*?)</main>").find(text)
        if (articleMatch != null && articleMatch.groupValues[1].length > 200) {
            text = articleMatch.groupValues[1]
        }
        text = Regex("(?is)<(br|p|div|li|h[1-6]|tr)[^>]*>").replace(text, "\n")
        text = Regex("(?is)<[^>]+>").replace(text, " ")
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'")
            .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
        text = Regex("[ \\t]+").replace(text, " ")
        text = Regex("\n\\s*\n+").replace(text, "\n")
        // Retire les lignes-bruit residuelles trop courtes pour etre une vraie phrase
        // (ex: "Accepter" / "Menu" / "S'abonner" isoles qui auraient survecu au
        // nettoyage structurel ci-dessus).
        text = text.lines().filter { it.trim().length > 3 || it.isBlank() }.joinToString("\n")
        return text.trim().take(5000) // limite raisonnable avant envoi au moteur IA local
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
