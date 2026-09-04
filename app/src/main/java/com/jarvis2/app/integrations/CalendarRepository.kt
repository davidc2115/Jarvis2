package com.jarvis2.app.integrations

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.util.TimeZone

/**
 * [calendarId]/[calendarName] identifient le calendrier d'origine de
 * l'evenement (compte pro, perso, partage...) -- necessaires pour que le
 * planning affiche de quel calendrier vient chaque evenement (l'utilisateur
 * ne voyait jusqu'ici jamais cette info) et pour pouvoir filtrer sur un
 * calendrier precis ("planning de Thomas").
 */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val calendarId: Long,
    val calendarName: String,
    val location: String = "",
)

/** Un calendrier du telephone (voir [CalendarRepository.listCalendars]). */
data class CalendarInfo(val id: Long, val displayName: String, val accountName: String)

/**
 * Un ou plusieurs [CalendarInfo] regroupes sous la meme identite visible
 * (meme nom d'affichage + meme compte) -- voir [CalendarRepository.listCalendarGroups].
 * [ids] contient TOUS les id bruts CalendarContract derriere ce doublon
 * apparent, pour qu'une case a cocher "affiche ce calendrier" dans Reglages
 * agisse sur le groupe entier d'un coup plutot que sur un seul id au hasard.
 */
data class CalendarGroup(val displayName: String, val accountName: String, val ids: List<Long>)

/** Reads/writes the device calendar via CalendarContract — requires READ/WRITE_CALENDAR. */
class CalendarRepository(private val context: Context) {

    // Toute methode de cette classe touchant ContentResolver.query()/insert()
    // sur CalendarContract est enveloppee en runCatching : une permission
    // READ_CALENDAR/WRITE_CALENDAR revoquee en cours de route (Android
    // "auto-reset" des permissions apres inactivite) ou un provider
    // calendrier tiers boiteux (voir task #7 -- deja un probleme connu avec
    // certains calendriers Xiaomi) levent une SecurityException/
    // IllegalArgumentException non rattrapee, qui faisait planter toute
    // l'appli des qu'une commande planning/agenda etait demandee en pleine
    // "reflexion" (voir task #326/#328). Degrade desormais proprement
    // (liste vide / null) plutot que de crasher.
    private fun defaultCalendarId(): Long? = runCatching {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return@runCatching cursor.getLong(0)
        }
        null
    }.getOrNull()

    /**
     * Tous les calendriers disponibles sur le telephone (perso, pro, partages,
     * comptes secondaires...) -- pas seulement le premier (voir
     * [defaultCalendarId], utilise uniquement pour la CREATION d'evenement).
     * Sert a lister les calendriers a l'utilisateur et a resoudre un nom/
     * surnom vers un id pour le filtrage du planning.
     */
    fun listCalendars(): List<CalendarInfo> = runCatching {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        val cursor = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)
            ?: return@runCatching emptyList()
        cursor.use {
            val result = mutableListOf<CalendarInfo>()
            while (it.moveToNext()) {
                result.add(
                    CalendarInfo(
                        id = it.getLong(0),
                        displayName = it.getString(1) ?: "(sans nom)",
                        accountName = it.getString(2) ?: "",
                    ),
                )
            }
            result
        }
    }.getOrDefault(emptyList())

    /**
     * Comme [listCalendars] mais regroupe les entrees identiques (meme nom
     * d'affichage + meme compte) sous une seule ligne -- CalendarContract
     * expose legitimement le meme calendrier plusieurs fois quand plusieurs
     * comptes/applis le synchronisent en parallele (signalement utilisateur :
     * "certains calendriers sont en double"). Utilise par l'ecran Reglages
     * (task #308) pour proposer UNE case a cocher par calendrier reellement
     * distinct plutot qu'une ligne par id brut.
     */
    fun listCalendarGroups(): List<CalendarGroup> =
        listCalendars()
            .groupBy { it.displayName to it.accountName }
            .map { (key, cals) -> CalendarGroup(displayName = key.first, accountName = key.second, ids = cals.map { c -> c.id }) }
            .sortedBy { it.displayName.lowercase() }

    fun createEvent(
        title: String,
        startTimeMillis: Long,
        durationMillis: Long = 3_600_000L,
        description: String = "",
        location: String = "",
        calendarId: Long? = null,
    ): Long? = runCatching {
        val calId = calendarId ?: defaultCalendarId() ?: return@runCatching null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, startTimeMillis)
            put(CalendarContract.Events.DTEND, startTimeMillis + durationMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return@runCatching null
        ContentUris.parseId(uri)
    }.getOrNull()

    fun upcomingEvents(fromMillis: Long = System.currentTimeMillis(), limit: Int = 20): List<CalendarEvent> =
        eventsInRange(fromMillis, Long.MAX_VALUE, limit)

    /**
     * Comme [upcomingEvents] mais borne aussi la fin de la periode -- utilise
     * par CommandRouter.resolvePeriod() pour repondre a "planning de demain",
     * "cette semaine", "ce mois", etc. plutot que de toujours renvoyer les N
     * prochains evenements sans distinction de periode.
     *
     * [calendarId] filtre optionnellement sur un seul calendrier (voir
     * [listCalendars] + CommandRouter : resolution "planning de Thomas" ->
     * calendarId) -- par defaut null, donc tous les calendriers confondus
     * comme avant. La requete interroge deja Events.CONTENT_URI qui couvre
     * TOUS les calendriers (pas seulement [defaultCalendarId], reserve a la
     * creation d'evenement) : Android joint automatiquement les colonnes
     * CALENDAR_DISPLAY_NAME/CALENDAR_ID de la table Calendars sur une requete
     * Events, donc pas besoin de jointure manuelle.
     *
     * [calendarIds] filtre sur un ENSEMBLE de calendriers -- utilise pour
     * respecter la selection "calendriers affiches" choisie dans Reglages
     * (voir SettingsScreen.kt / SettingsViewModel.kt, task #308) quand
     * aucun nom de calendrier precis n'a ete demande dans la phrase.
     * Ignore si [calendarId] est deja fourni (priorite au filtre precis).
     */
    fun eventsInRange(
        fromMillis: Long,
        toMillis: Long,
        limit: Int = 50,
        calendarId: Long? = null,
        calendarIds: Set<Long>? = null,
    ): List<CalendarEvent> = runCatching {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
            CalendarContract.Events.EVENT_LOCATION,
        )
        val idsFilter = calendarIds?.takeIf { it.isNotEmpty() }
        val selection = buildString {
            append("${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ?")
            if (calendarId != null) {
                append(" AND ${CalendarContract.Events.CALENDAR_ID} = ?")
            } else if (idsFilter != null) {
                append(" AND ${CalendarContract.Events.CALENDAR_ID} IN (${idsFilter.joinToString(",") { "?" }})")
            }
        }
        val args = buildList {
            add(fromMillis.toString())
            add(toMillis.toString())
            if (calendarId != null) {
                add(calendarId.toString())
            } else if (idsFilter != null) {
                idsFilter.forEach { add(it.toString()) }
            }
        }.toTypedArray()
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, projection, selection, args,
            "${CalendarContract.Events.DTSTART} ASC",
        ) ?: return@runCatching emptyList()

        cursor.use {
            val events = mutableListOf<CalendarEvent>()
            while (it.moveToNext() && events.size < limit) {
                events.add(
                    CalendarEvent(
                        id = it.getLong(0),
                        title = it.getString(1) ?: "(sans titre)",
                        startMillis = it.getLong(2),
                        endMillis = it.getLong(3),
                        calendarId = it.getLong(4),
                        calendarName = it.getString(5) ?: "(sans nom)",
                        location = it.getString(6) ?: "",
                    )
                )
            }
            events
        }
    }.getOrDefault(emptyList())

    /**
     * Supprime un evenement par id (portage Newjarvis/CalendarController.
     * deleteEvent, fusion task #5) -- capacite qui n'existait PAS du tout
     * dans Jarvis2 jusqu'ici (seules la creation et la lecture du planning
     * etaient possibles). Retourne false (jamais d'exception) si la
     * suppression echoue -- y compris le cas connu MIUI/Xiaomi ou
     * l'ecriture agenda par une appli tierce est bloquee malgre la
     * permission WRITE_CALENDAR accordee (voir task #7 historique de ce
     * depot -- meme famille de probleme).
     */
    fun deleteEvent(eventId: Long): Boolean = runCatching {
        context.contentResolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId.toString()),
        ) > 0
    }.getOrDefault(false)

    /**
     * Modifie un evenement existant (portage Newjarvis/CalendarController.
     * updateEvent, fusion task #5) -- seuls les champs non-null sont
     * modifies, les autres restent inchanges. Retourne false (jamais
     * d'exception) en cas d'echec.
     */
    fun updateEvent(
        eventId: Long,
        newTitle: String? = null,
        newStartMillis: Long? = null,
        newEndMillis: Long? = null,
        newDescription: String? = null,
        newLocation: String? = null,
    ): Boolean = runCatching {
        val values = ContentValues().apply {
            newTitle?.let { put(CalendarContract.Events.TITLE, it) }
            newStartMillis?.let { put(CalendarContract.Events.DTSTART, it) }
            newEndMillis?.let { put(CalendarContract.Events.DTEND, it) }
            newDescription?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            newLocation?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        if (values.size() == 0) return@runCatching false
        context.contentResolver.update(
            CalendarContract.Events.CONTENT_URI,
            values,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId.toString()),
        ) > 0
    }.getOrDefault(false)

    /**
     * Details complets d'UN evenement par id (portage Newjarvis/
     * CalendarController.getEventDetails, fusion task #5 REDO) -- utilise
     * pour verifier qu'un evenement existe encore avant de diagnostiquer un
     * echec d'ecriture (voir CommandRouter.diagnoseCalendarWriteFailure),
     * et pour repondre a une commande get_event_details/search_event de
     * l'IA cloud avec le detail (lieu, description) d'un id precis.
     */
    fun getEventDetails(eventId: Long): CalendarEvent? = runCatching {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
            CalendarContract.Events.EVENT_LOCATION,
        )
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return@runCatching null
            CalendarEvent(
                id = eventId,
                title = c.getString(1) ?: "(sans titre)",
                startMillis = c.getLong(2),
                endMillis = c.getLong(3),
                calendarId = c.getLong(4),
                calendarName = c.getString(5) ?: "(sans nom)",
                location = c.getString(6) ?: "",
            )
        }
    }.getOrNull()
}
