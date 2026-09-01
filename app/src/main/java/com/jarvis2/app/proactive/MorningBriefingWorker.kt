package com.jarvis2.app.proactive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jarvis2.app.data.SettingsDataStore
import com.jarvis2.app.integrations.CalendarEvent
import com.jarvis2.app.integrations.CalendarRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Worker quotidien (voir ProactiveScheduler -- programme pour ~8h locale)
 * qui envoie une notification "briefing du matin" resumant les evenements
 * du jour, meme quand l'utilisateur n'ouvre pas l'app -- c'est ca la
 * difference entre un JARVIS "reactif" (repond seulement quand on lui
 * parle) et "proactif" (task #242), demande explicitement par l'utilisateur.
 *
 * [PROACTIVE_BRIEFING_LAST_DATE] evite un doublon si WorkManager redeclenche
 * le meme jour (ex: reboot du telephone relancant le Worker en retard).
 */
class MorningBriefingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val calendarRepository: CalendarRepository by inject()
    private val settings: SettingsDataStore by inject()

    override suspend fun doWork(): Result {
        val enabled = (settings.get(PROACTIVE_BRIEFING_ENABLED) ?: "true") == "true"
        if (!enabled) return Result.success()

        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val todayKey = today.toString()

        if (settings.get(PROACTIVE_BRIEFING_LAST_DATE) == todayKey) return Result.success()

        ProactiveNotifier.ensureChannels(applicationContext)

        val startMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val events = try {
            calendarRepository.eventsInRange(startMillis, endMillis, limit = 30)
        } catch (e: SecurityException) {
            return Result.success()
        }

        val text = buildSummary(events, zone)
        ProactiveNotifier.notifyBriefing(applicationContext, "Bonjour — votre journée", text)
        settings.set(PROACTIVE_BRIEFING_LAST_DATE, todayKey)
        return Result.success()
    }

    private fun buildSummary(events: List<CalendarEvent>, zone: java.time.ZoneId): String {
        if (events.isEmpty()) return "Aucun événement prévu aujourd'hui."
        val timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH'h'mm")
        val maxShown = 6
        val lines = events.take(maxShown).map { event ->
            val time = java.time.Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalTime().format(timeFmt)
            "$time — ${event.title}"
        }
        val extra = events.size - maxShown
        return if (extra > 0) (lines + "+ $extra autre(s)").joinToString("\n") else lines.joinToString("\n")
    }
}
