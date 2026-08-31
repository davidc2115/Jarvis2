package com.jarvis2.app.integrations

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

/**
 * Reveils et minuteurs via les Intents publics AlarmClock -- deleguent a
 * l'application Horloge par defaut du telephone (pas d'AlarmManager natif :
 * ca demanderait de reimplementer toute une UI de reveil/minuteur, alors que
 * chaque telephone a deja une appli Horloge qui fait deja ca tres bien).
 * EXTRA_SKIP_UI=false volontairement : on laisse l'appli Horloge confirmer
 * visuellement, plutot que de creer silencieusement un reveil sans que
 * l'utilisateur le voie.
 *
 * Necessite la permission com.android.alarm.permission.SET_ALARM (voir
 * AndroidManifest.xml) -- sans elle, ACTION_SET_ALARM/ACTION_SET_TIMER
 * echouent avec "Permission Denial" (voir historique JARVIS #40).
 */
class AlarmController(private val context: Context) {

    fun setAlarm(hour: Int, minute: Int, label: String? = null): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return launch(intent, "setAlarm")
    }

    /** [totalSeconds] : duree du minuteur en secondes. */
    fun setTimer(totalSeconds: Int, label: String? = null): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return launch(intent, "setTimer")
    }

    private fun launch(intent: Intent, what: String): Boolean {
        return try {
            if (intent.resolveActivity(context.packageManager) == null) {
                Log.w("AlarmController", "$what: aucune application Horloge trouvée pour gérer l'intent")
                return false
            }
            context.startActivity(intent)
            true
        } catch (e: SecurityException) {
            Log.w("AlarmController", "$what: permission SET_ALARM refusée", e)
            false
        } catch (e: Exception) {
            Log.w("AlarmController", "$what a échoué", e)
            false
        }
    }
}
