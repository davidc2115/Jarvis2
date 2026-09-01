package com.jarvis2.app.proactive

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/**
 * Cles de reglages pour l'axe Proactivite (task #242), regroupees ici
 * plutot que dans SettingsViewModel.kt car elles sont lues depuis des
 * Worker WorkManager (ProactiveReminderWorker/MorningBriefingWorker) en
 * plus de l'ecran Reglages -- eviter un import croise ui.settings <-> worker.
 *
 * Suit le meme pattern que le reste de SettingsDataStore : valeurs stockees
 * comme String ("true"/"false") plutot que des cles booleennes typees, pour
 * rester coherent avec TTS_ENABLED/CALENDAR_GROUP_BY_DAY existants.
 */
val PROACTIVE_REMINDERS_ENABLED = stringPreferencesKey("proactive_reminders_enabled")
val PROACTIVE_REMINDER_MINUTES = stringPreferencesKey("proactive_reminder_minutes")
val PROACTIVE_BRIEFING_ENABLED = stringPreferencesKey("proactive_briefing_enabled")

/** Evenements deja notifies, encodes "eventId:startMillis" -- deduplique les rappels entre deux passages du Worker (toutes les 15 min). */
internal val PROACTIVE_NOTIFIED_EVENTS = stringSetPreferencesKey("proactive_notified_events")

/** Derniere date (yyyy-MM-dd) ou le briefing du matin a ete envoye -- evite un doublon si le Worker se declenche deux fois le meme jour. */
internal val PROACTIVE_BRIEFING_LAST_DATE = stringPreferencesKey("proactive_briefing_last_date")

internal const val DEFAULT_REMINDER_MINUTES = 15
