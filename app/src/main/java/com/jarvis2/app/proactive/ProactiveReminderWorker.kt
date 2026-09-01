package com.jarvis2.app.proactive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jarvis2.app.data.SettingsDataStore
import com.jarvis2.app.integrations.CalendarRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Worker periodique (toutes les 15 min, voir ProactiveScheduler -- c'est
 * l'intervalle minimum autorise par WorkManager pour du travail periodique)
 * qui alerte l'utilisateur quelques minutes avant un evenement de son
 * agenda. Utilise KoinComponent + by inject() plutot qu'un WorkerFactory
 * personnalise : WorkManager instancie deja ce Worker via reflection avec
 * son constructeur (Context, WorkerParameters), et Koin est deja demarre
 * (startKoin) au moment ou Application.onCreate() programme ce Worker --
 * inutile d'ajouter la complexite d'un Configuration.Provider pour un seul
 * cas d'usage.
 *
 * Deduplique via [PROACTIVE_NOTIFIED_EVENTS] (cle "eventId:startMillis")
 * pour ne pas re-notifier le meme evenement a chaque passage du Worker tant
 * qu'il reste dans la fenetre de rappel.
 */
class ProactiveReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val calendarRepository: CalendarRepository by inject()
    private val settings: SettingsDataStore by inject()

    override suspend fun doWork(): Result {
        val enabled = (settings.get(PROACTIVE_REMINDERS_ENABLED) ?: "true") == "true"
        if (!enabled) return Result.success()

        ProactiveNotifier.ensureChannels(applicationContext)

        val minutes = settings.get(PROACTIVE_REMINDER_MINUTES)?.toIntOrNull() ?: DEFAULT_REMINDER_MINUTES
        val now = System.currentTimeMillis()
        val windowEnd = now + minutes * 60_000L

        val events = try {
            calendarRepository.eventsInRange(now, windowEnd, limit = 20)
        } catch (e: SecurityException) {
            // Permission calendrier non accordee -- rien a faire ici, pas la peine
            // d'echouer/reessayer, PermissionsGate.kt redemandera au prochain lancement.
            return Result.success()
        }
        if (events.isEmpty()) return Result.success()

        val notified = (settings.get(PROACTIVE_NOTIFIED_EVENTS) ?: emptySet()).toMutableSet()
        // Purge les entrees perimees (plus d'1h dans le passe) pour eviter que ce
        // Set ne grossisse indefiniment au fil des jours.
        notified.removeAll { entry -> (entry.substringAfter(':').toLongOrNull() ?: 0L) < now - 3_600_000L }

        val timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH'h'mm")
        val zone = java.time.ZoneId.systemDefault()
        var changed = false

        for (event in events) {
            val key = "${event.id}:${event.startMillis}"
            if (key in notified) continue
            val time = java.time.Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalTime().format(timeFmt)
            val subtitle = if (event.calendarName.isNotBlank()) "À $time — ${event.calendarName}" else "À $time"
            ProactiveNotifier.notifyReminder(
                applicationContext,
                notificationId = (event.id % Int.MAX_VALUE).toInt(),
                title = "Dans $minutes min : ${event.title}",
                text = subtitle,
            )
            notified += key
            changed = true
        }
        if (changed) settings.set(PROACTIVE_NOTIFIED_EVENTS, notified)
        return Result.success()
    }
}
