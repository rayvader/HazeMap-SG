package io.github.rayvader.hazemap

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.ZoneId

/**
 * The phone's calendars: which ones there are, which ones the widget line reads, and the next event
 * on them. Event details never leave the phone.
 */
object PhoneCalendars {

    private const val DAY_MS = 24 * 3_600_000L

    fun hasAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Every calendar on the phone, grouped by account. Empty without access. */
    fun list(context: Context): List<PhoneCalendar> {
        if (!hasAccess(context)) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS
        )
        val out = ArrayList<PhoneCalendar>()
        try {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)
                ?.use { c ->
                    while (c.moveToNext()) {
                        out.add(
                            PhoneCalendar(
                                id = c.getLong(0),
                                name = c.getString(1).orEmpty().ifBlank { "Calendar" },
                                account = c.getString(2).orEmpty(),
                                accountType = c.getString(3).orEmpty(),
                                color = c.getInt(4),
                                visible = c.getInt(5) != 0,
                                syncOn = c.getInt(6) != 0
                            )
                        )
                    }
                }
        } catch (e: SecurityException) {
            // Access was taken away while this ran.
            return emptyList()
        }
        return CalendarChoice.sorted(out)
    }

    /** Events and repeats of events on [calendarIds] between two times, leaving out cancelled and declined ones. */
    fun events(context: Context, calendarIds: Set<Long>, fromMillis: Long, toMillis: Long): List<CalendarEvent> {
        if (calendarIds.isEmpty() || !hasAccess(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMillis)
            ContentUris.appendId(it, toMillis)
        }.build()
        // Only what the line shows: no place, no notes, no guests.
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )
        val status = CalendarContract.Instances.STATUS
        val mine = CalendarContract.Instances.SELF_ATTENDEE_STATUS
        // The ids are numbers from the calendar provider, never text a person typed.
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",")})" +
            " AND ($status IS NULL OR $status != ${CalendarContract.Events.STATUS_CANCELED})" +
            " AND ($mine IS NULL OR $mine != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})"
        val out = ArrayList<CalendarEvent>()
        try {
            context.contentResolver.query(uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC")
                ?.use { c ->
                    while (c.moveToNext()) {
                        out.add(
                            CalendarEvent(
                                eventId = c.getLong(0),
                                calendarId = c.getLong(1),
                                title = c.getString(2).orEmpty(),
                                beginMillis = c.getLong(3),
                                endMillis = c.getLong(4),
                                allDay = c.getInt(5) != 0
                            )
                        )
                    }
                }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return out
    }

    /** The ticked calendars, or null before the person ticked anything. */
    fun saved(context: Context): Set<Long>? {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(Prefs.KEY_CALENDARS)) return null
        return prefs.getStringSet(Prefs.KEY_CALENDARS, null).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun save(context: Context, ids: Set<Long>) {
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(Prefs.KEY_CALENDARS, ids.map { it.toString() }.toSet())
            .apply()
    }

    /** The next event on the chosen calendars, or null without access, a choice, or an event this week. */
    fun nextEvent(context: Context, nowMillis: Long): CalendarEvent? {
        if (!hasAccess(context)) return null
        val ids = CalendarChoice.chosen(list(context), saved(context))
        if (ids.isEmpty()) return null
        // A day either side, so an all-day event, kept from midnight UTC, is never cut off.
        val events = events(context, ids, nowMillis - DAY_MS, nowMillis + EventLogic.LOOK_AHEAD_MS + DAY_MS)
        return EventLogic.pickNext(events, nowMillis, ZoneId.systemDefault())
    }
}
