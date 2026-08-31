package com.jarvis2.app.integrations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** Resume d'un mail, suffisant pour un affichage chat sans avoir a ouvrir l'appli mail. */
data class MailSummary(
    val from: String,
    val subject: String,
    val dateMillis: Long?,
    val snippet: String,
    val isUnread: Boolean,
)

/**
 * Lecture d'emails via l'API Gmail REST (https://gmail.googleapis.com/gmail/v1/...),
 * authentifiee par [GoogleAuthController] (scope gmail.readonly). Remplace l'integration
 * IMAP precedente a la demande explicite de l'utilisateur, qui a fourni son propre Client
 * ID OAuth Web -- l'obstacle initial ("Claude ne peut pas provisionner de projet Google
 * Cloud") ne s'applique donc plus ici.
 *
 * Appelle directement l'API REST plutot que la lourde librairie Java
 * google-api-services-gmail (coordonnees Maven generees, faciles a se tromper, gros
 * poids dans l'APK) -- coherent avec le style deja utilise ailleurs dans l'appli
 * (voir ai/WebSearchTool.kt : OkHttpClient + org.json.JSONObject).
 *
 * Lecture seule volontairement (pas de suppression/marquage depuis Jarvis pour
 * l'instant) : le risque d'une commande vocale mal comprise qui supprimerait un mail
 * important est nettement plus genant qu'un manque de fonctionnalite.
 */
class MailReader(
    private val googleAuth: GoogleAuthController,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {

    /** Pour l'affichage Reglages uniquement -- voir GoogleAuthController.isConnected(). */
    suspend fun isConfigured(): Boolean = googleAuth.isConnected()

    suspend fun fetchRecent(limit: Int = 10, unreadOnly: Boolean = false): Result<List<MailSummary>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = googleAuth.getAccessToken().getOrThrow()
                fetchViaGmailApi(token, limit, unreadOnly)
            }
        }

    private suspend fun fetchViaGmailApi(token: String, limit: Int, unreadOnly: Boolean): List<MailSummary> =
        coroutineScope {
            val query = if (unreadOnly) "&q=" + URLEncoder.encode("is:unread", "UTF-8") else ""
            val listUrl = "$GMAIL_API_BASE/messages?maxResults=$limit&labelIds=INBOX$query"
            val listJson = JSONObject(getJson(listUrl, token))
            val ids = listJson.optJSONArray("messages")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
            } ?: return@coroutineScope emptyList()

            ids.map { id ->
                async {
                    val msgUrl = "$GMAIL_API_BASE/messages/$id" +
                        "?format=metadata&metadataHeaders=From&metadataHeaders=Subject"
                    parseMessage(JSONObject(getJson(msgUrl, token)))
                }
            }.awaitAll()
        }

    private fun getJson(url: String, token: String): String {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Gmail API : HTTP ${response.code} (${response.body?.string()?.take(200).orEmpty()})")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun parseMessage(json: JSONObject): MailSummary {
        var from = "Expéditeur inconnu"
        var subject = "(sans objet)"
        json.optJSONObject("payload")?.optJSONArray("headers")?.let { headers ->
            for (i in 0 until headers.length()) {
                val header = headers.getJSONObject(i)
                when (header.optString("name")) {
                    "From" -> from = header.optString("value", from)
                    "Subject" -> subject = header.optString("value", subject)
                }
            }
        }
        val labelIds = json.optJSONArray("labelIds")
        val isUnread = (0 until (labelIds?.length() ?: 0)).any { labelIds!!.getString(it) == "UNREAD" }
        val dateMillis = json.optString("internalDate").toLongOrNull()
        val snippet = decodeBasicHtmlEntities(json.optString("snippet"))
        return MailSummary(from = from, subject = subject, dateMillis = dateMillis, snippet = snippet, isUnread = isUnread)
    }

    /** Le "snippet" Gmail contient parfois des entites HTML (&#39; etc.) -- decodage minimal, pas de vraie librairie HTML necessaire pour un extrait de texte. */
    private fun decodeBasicHtmlEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private companion object {
        const val GMAIL_API_BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
    }
}
