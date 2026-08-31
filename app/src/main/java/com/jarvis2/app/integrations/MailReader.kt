package com.jarvis2.app.integrations

import com.jarvis2.app.data.MailAccount
import com.jarvis2.app.data.MailAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.internet.InternetAddress

/** Resume d'un mail, suffisant pour un affichage chat sans avoir a ouvrir l'appli mail. */
data class MailSummary(
    val from: String,
    val subject: String,
    val dateMillis: Long?,
    val snippet: String,
    val isUnread: Boolean,
)

/**
 * Lecture d'emails via IMAP (com.sun.mail:android-mail, namespace javax.mail
 * -- verifie via le pom Maven du module avant integration). Remplace la
 * lecture Gmail API/OAuth de l'ancienne appli (abandonnee dans la reecriture
 * complete, tache #182) : Claude ne peut pas provisionner de projet Google
 * Cloud/client OAuth pour l'utilisateur, alors qu'IMAP fonctionne avec
 * n'importe quel fournisseur -- dont Gmail lui-meme via un mot de passe
 * d'application -- sans dependre d'aucun service tiers cote developpeur.
 *
 * Lecture seule volontairement (pas de suppression/marquage depuis Jarvis
 * pour l'instant) : le risque d'une commande vocale mal comprise qui
 * supprimerait un mail important est nettement plus genant qu'un manque de
 * fonctionnalite, contrairement a la lecture qui est sans risque.
 */
class MailReader(private val accountStore: MailAccountStore) {

    fun isConfigured(): Boolean = accountStore.get() != null

    suspend fun fetchRecent(limit: Int = 10, unreadOnly: Boolean = false): Result<List<MailSummary>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val account = accountStore.get()
                    ?: throw IllegalStateException("Aucun compte mail configuré (Réglages → Mail).")
                fetch(account, limit, unreadOnly)
            }
        }

    private fun fetch(account: MailAccount, limit: Int, unreadOnly: Boolean): List<MailSummary> {
        val protocol = if (account.useSsl) "imaps" else "imap"
        val props = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.host", account.host)
            put("mail.$protocol.port", account.port.toString())
            put("mail.$protocol.connectiontimeout", "15000")
            put("mail.$protocol.timeout", "15000")
        }
        val session = Session.getInstance(props)
        val store = session.store
        store.connect(account.host, account.port, account.username, account.appPassword)
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            try {
                val total = inbox.messageCount
                if (total == 0) return emptyList()
                // Ne regarde que la fenetre des `limit` derniers messages IMAP
                // (les plus recents) meme en mode unreadOnly=true -- une vraie
                // recherche "tous les non-lus de la boite" ferait potentiellement
                // un FETCH sur des milliers de messages, trop lent pour une
                // reponse chat.
                val start = (total - limit + 1).coerceAtLeast(1)
                val messages = inbox.getMessages(start, total).reversed()
                return messages
                    .filter { !unreadOnly || !it.flags.contains(Flags.Flag.SEEN) }
                    .take(limit)
                    .map { msg ->
                        val from = (msg.from?.firstOrNull() as? InternetAddress)?.let {
                            it.personal ?: it.address
                        } ?: "Expéditeur inconnu"
                        val subject = msg.subject ?: "(sans objet)"
                        val snippet = runCatching { extractSnippet(msg.content) }.getOrDefault("")
                        MailSummary(
                            from = from,
                            subject = subject,
                            dateMillis = msg.sentDate?.time,
                            snippet = snippet,
                            isUnread = !msg.flags.contains(Flags.Flag.SEEN),
                        )
                    }
            } finally {
                inbox.close(false)
            }
        } finally {
            store.close()
        }
    }

    private fun extractSnippet(content: Any?, maxLength: Int = 140): String {
        val text = when (content) {
            is String -> content
            is Multipart -> (0 until content.count)
                .asSequence()
                .map { content.getBodyPart(it) }
                .firstOrNull { it.isMimeType("text/plain") }
                ?.content as? String
                ?: ""
            else -> ""
        }
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        return if (cleaned.length > maxLength) cleaned.take(maxLength) + "…" else cleaned
    }
}
