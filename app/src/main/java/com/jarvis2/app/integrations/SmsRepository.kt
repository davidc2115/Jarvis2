package com.jarvis2.app.integrations

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony

/** Un SMS lu depuis le fournisseur Telephony du telephone. */
data class SmsMessage(val address: String, val body: String, val dateMillis: Long, val isRead: Boolean)

/**
 * Lit/envoie des SMS via le ContentProvider Telephony + SmsManager -- portage
 * Newjarvis/SmsController (fusion Phase 4a, "REPREND COMPLETEMENT NEWJARVIS").
 * Necessite SEND_SMS/READ_SMS. Comme CalendarRepository/ContactsRepository,
 * toute lecture est enveloppee en runCatching pour ne jamais faire planter
 * l'appli si la permission est revoquee ou un fournisseur SMS tiers (MIUI...)
 * se comporte differemment.
 */
class SmsRepository(private val context: Context) {

    fun sendSms(number: String, body: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val smsManager = android.telephony.SmsManager.getDefault()
        val parts = smsManager.divideMessage(body)
        if (parts.size > 1) {
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
        } else {
            smsManager.sendTextMessage(number, null, body, null, null)
        }
        true
    }.getOrDefault(false)

    /**
     * Boite de reception -- comme Newjarvis, essaie d'abord Sms.Inbox
     * (rapide, standard) puis retombe sur Sms.CONTENT_URI filtre TYPE=1
     * (compatible MIUI/OneUI/ColorOS/EMUI dont le fournisseur Inbox dedie
     * est parfois vide/absent alors que le fournisseur generique fonctionne).
     */
    fun inbox(limit: Int = 10): List<SmsMessage> {
        val fromInbox = readFrom(Telephony.Sms.Inbox.CONTENT_URI, null, null, limit)
        return fromInbox.ifEmpty { readFrom(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.TYPE} = 1", null, limit) }
    }

    fun unread(limit: Int = 10): List<SmsMessage> =
        readFrom(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.READ} = 0 AND ${Telephony.Sms.TYPE} = 1", null, limit)

    /** Cherche par mot-cle dans le contenu OU l'expediteur/numero (portage Newjarvis/SmsController.searchSms). */
    fun search(query: String, limit: Int = 10): List<SmsMessage> {
        val like = "%$query%"
        val bodyOrAddress = "(${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?)"
        val args = arrayOf(like, like)
        val fromInbox = readFrom(Telephony.Sms.Inbox.CONTENT_URI, bodyOrAddress, args, limit)
        return fromInbox.ifEmpty { readFrom(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.TYPE} = 1 AND $bodyOrAddress", args, limit) }
    }

    fun markAllRead(): Boolean = runCatching {
        val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
        context.contentResolver.update(Telephony.Sms.Inbox.CONTENT_URI, values, "${Telephony.Sms.READ} = 0", null) > 0
    }.getOrDefault(false)

    private fun readFrom(uri: Uri, selection: String?, args: Array<String>?, limit: Int): List<SmsMessage> = runCatching {
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ)
        val cursor = context.contentResolver.query(uri, projection, selection, args, "${Telephony.Sms.DATE} DESC")
            ?: return@runCatching emptyList()
        cursor.use {
            val result = mutableListOf<SmsMessage>()
            while (it.moveToNext() && result.size < limit) {
                result.add(
                    SmsMessage(
                        address = it.getString(0) ?: "Inconnu",
                        body = it.getString(1) ?: "",
                        dateMillis = it.getLong(2),
                        isRead = it.getInt(3) == 1,
                    ),
                )
            }
            result
        }
    }.getOrDefault(emptyList())
}
