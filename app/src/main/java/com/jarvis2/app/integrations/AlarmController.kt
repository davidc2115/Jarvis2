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
 * EXTRA_SKIP_UI=true sur demande explicite de l'utilisateur : le reveil/
 * minuteur est cree directement en arriere-plan par l'appli Horloge, sans
 * ouvrir son ecran de confirmation (verifie via la doc AlarmClock -- ignore
 * uniquement si aucune duree n'est precisee pour ACTION_SET_TIMER, ce qui
 * n'arrive jamais ici puisque extractDurationSeconds() est deja verifie non
 * nul avant l'appel, voir CommandRouter.kt).
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
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return launch(intent, "setAlarm")
    }

    /** [totalSeconds] : duree du minuteur en secondes. */
    fun setTimer(totalSeconds: Int, label: String? = null): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
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
