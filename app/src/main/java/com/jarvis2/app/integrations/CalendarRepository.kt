package com.jarvis2.app.integrations

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.util.TimeZone

data class CalendarEvent(val id: Long, val title: String, val startMillis: Long, val endMillis: Long)

/** Reads/writes the device calendar via CalendarContract — requires READ/WRITE_CALENDAR. */
class CalendarRepository(private val context: Context) {

    private fun defaultCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    fun createEvent(title: String, startTimeMillis: Long, durationMillis: Long = 3_600_000L, description: String = ""): Long? {
        val calId = defaultCalendarId() ?: return null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.DTSTART, startTimeMillis)
            put(CalendarContract.Events.DTEND, startTimeMillis + durationMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        return ContentUris.parseId(uri)
    }

    fun upcomingEvents(fromMillis: Long = System.currentTimeMillis(), limit: Int = 20): List<CalendarEvent> {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ?"
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, projection, selection, arrayOf(fromMillis.toString()),
            "${CalendarContract.Events.DTSTART} ASC",
        ) ?: return emptyList()

        return cursor.use {
            val events = mutableListOf<CalendarEvent>()
            while (it.moveToNext() && events.size < limit) {
                events.add(
                    CalendarEvent(
                        id = it.getLong(0),
                        title = it.getString(1) ?: "(sans titre)",
                        startMillis = it.getLong(2),
                        endMillis = it.getLong(3),
                    )
                )
            }
            events
        }
    }
}
