package com.jarvis2.app.integrations

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager

/** Une ligne du journal d'appels natif. [type] = CallLog.Calls.INCOMING_TYPE/OUTGOING_TYPE/MISSED_TYPE. */
data class CallLogEntry(val name: String?, val number: String, val type: Int, val dateMillis: Long, val durationSeconds: Long)

/**
 * Passe/termine des appels + lit le journal d'appels -- portage Newjarvis/
 * PhoneController (fusion Phase 4a, "REPREND COMPLETEMENT NEWJARVIS").
 * Necessite CALL_PHONE/READ_CALL_LOG (ANSWER_PHONE_CALLS pour raccrocher).
 */
class PhoneRepository(private val context: Context) {

    fun call(number: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Raccroche l'appel en cours -- necessite ANSWER_PHONE_CALLS (API 28+), pas d'equivalent avant. */
    fun endCall(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
        @Suppress("DEPRECATION")
        telecomManager.endCall()
        true
    }.getOrDefault(false)

    fun recentCalls(limit: Int = 10): List<CallLogEntry> = runCatching {
        val projection = arrayOf(
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        val cursor = context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC")
            ?: return@runCatching emptyList()
        cursor.use {
            val result = mutableListOf<CallLogEntry>()
            while (it.moveToNext() && result.size < limit) {
                result.add(
                    CallLogEntry(
                        name = it.getString(0),
                        number = it.getString(1) ?: "",
                        type = it.getInt(2),
                        dateMillis = it.getLong(3),
                        durationSeconds = it.getLong(4),
                    ),
                )
            }
            result
        }
    }.getOrDefault(emptyList())

    fun missedCallCount(): Int = runCatching {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.TYPE),
            "${CallLog.Calls.TYPE} = ?",
            arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
            null,
        )?.use { it.count } ?: 0
    }.getOrDefault(0)
}
