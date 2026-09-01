package com.jarvis2.app.proactive

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Programme les deux Worker de l'axe Proactivite (task #242), appele une
 * fois depuis Jarvis2Application.onCreate(). Le WorkManagerInitializer par
 * defaut (androidx.startup) a deja configure WorkManager avant que
 * Application.onCreate() ne s'execute -- pas besoin d'init manuelle.
 *
 * ExistingPeriodicWorkPolicy.KEEP : si un travail du meme nom existe deja
 * (app relancee), on garde la planification existante plutot que de la
 * redemarrer a chaque lancement de l'app (ce qui decalerait sans fin
 * l'horaire du briefing du matin).
 */
object ProactiveScheduler {

    private const val WORK_REMINDERS = "jarvis_proactive_reminders"
    private const val WORK_BRIEFING = "jarvis_morning_briefing"

    fun schedule(context: Context) {
        ProactiveNotifier.ensureChannels(context)
        val workManager = WorkManager.getInstance(context)

        // 15 min = intervalle minimum periodique autorise par WorkManager.
        val reminderRequest = PeriodicWorkRequestBuilder<ProactiveReminderWorker>(15, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(WORK_REMINDERS, ExistingPeriodicWorkPolicy.KEEP, reminderRequest)

        val briefingRequest = PeriodicWorkRequestBuilder<MorningBriefingWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(millisUntilNextEightAm(), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_BRIEFING, ExistingPeriodicWorkPolicy.KEEP, briefingRequest)
    }

    /** Delai (ms) jusqu'a la prochaine occurrence de 8h locale (aujourd'hui si pas encore passee, sinon demain). */
    private fun millisUntilNextEightAm(): Long {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        var target = now.withHour(8).withMinute(0).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return java.time.Duration.between(now, target).toMillis()
    }
}
