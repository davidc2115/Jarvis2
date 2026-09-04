package com.jarvis2.app.integrations

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Recepteur Broadcast SMS -- portage Newjarvis/SmsReceiver (fusion Phase 4a).
 * Necessaire sur Android 10+ pour que le systeme valide correctement les
 * permissions SEND_SMS/READ_SMS accordees a l'appli (une appli qui ne
 * declare AUCUN recepteur SMS_RECEIVED peut voir ces permissions traitees
 * differemment par certains OEM). Ne fait que journaliser -- la lecture
 * reelle des SMS passe par SmsRepository, jamais par ce recepteur.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (msg in messages) {
                val sender = msg.displayOriginatingAddress ?: "Inconnu"
                Log.d("SmsReceiver", "Nouveau SMS de $sender")
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Erreur reception SMS", e)
        }
    }
}
