package io.github.rayvader.hazemap

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** A calendar on the phone, as the calendar list shows it. */
data class PhoneCalendar(
    val id: Long,
    val name: String,
    /** The account it belongs to, such as an email address. */
    val account: String,
    val accountType: String,
    /** ARGB colour of the calendar. */
    val color: Int,
    /** Shown in the phone's Calendar app. */
    val visible: Boolean,
    /** The account keeps this calendar's events on the phone. */
    val syncOn: Boolean
) {
    /** Kept only on this phone, with no account to sync with. */
    val phoneOnly: Boolean get() = accountType == ACCOUNT_TYPE_LOCAL

    /**
     * Switched off in its account's settings, so none of its events are on the phone. A phone-only
     * calendar has nothing to sync with and always keeps its events.
     */
    val switchedOff: Boolean get() = !syncOn && !phoneOnly

    companion object {
        /** CalendarContract.ACCOUNT_TYPE_LOCAL, here so the tests need no Android. */
        const val ACCOUNT_TYPE_LOCAL = "LOCAL"
    }
}

/** One event, or one time of a repeating event. Only what the widget line shows is read. */
data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    /**
     * The event's display colour (its own colour if set, else its calendar's), as the provider's
     * DISPLAY_COLOR gives it, or 0 when none is known. The Wide widget tints each row's pill with it.
     */
    val displayColor: Int = 0
) {
    /** The first day of an all-day event. Android keeps all-day events from midnight UTC. */
    val firstDay: Long get() = utcDay(beginMillis)

    /** The day after the last day of an all-day event. */
    val dayAfter: Long get() = maxOf(utcDay(endMillis), firstDay + 1)

    private fun utcDay(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
}

/** Which of the phone's calendars the widget line reads. */
object CalendarChoice {

    /**
     * The calendars to read: the ticked ones, or, before anything was ticked, the ones the Calendar
     * app shows. A switched-off calendar has no events on the phone and never counts.
     */
    fun chosen(calendars: List<PhoneCalendar>, saved: Set<Long>?): Set<Long> =
        calendars.filter { !it.switchedOff && (saved?.contains(it.id) ?: it.visible) }.map { it.id }.toSet()

    /** "Personal", "Personal, Family", "Personal, Family +2", or null when none is chosen. */
    fun summary(calendars: List<PhoneCalendar>, chosen: Set<Long>): String? {
        val names = calendars.filter { it.id in chosen }.map { it.name }
        return when {
            names.isEmpty() -> null
            names.size <= 2 -> names.joinToString(", ")
            else -> "${names[0]}, ${names[1]} +${names.size - 2}"
        }
    }

    /** Grouped by account, each group by name; calendars only on the phone come last. */
    fun sorted(calendars: List<PhoneCalendar>): List<PhoneCalendar> = calendars.sortedWith(
        compareBy<PhoneCalendar>({ it.phoneOnly }, { it.account.lowercase(Locale.ROOT) }, { it.accountType })
            .thenBy { it.name.lowercase(Locale.ROOT) }
    )
}

/** The next event on the widget line, and how it reads. */
object EventLogic {

    /** How far ahead the line looks. */
    const val LOOK_AHEAD_MS = 7 * 24 * 3_600_000L

    /** An event that began this recently still beats one that has been running a while. */
    private const val JUST_STARTED_MS = 15 * 60_000L

    /** 24-hour, like every time in the app (see [TimeFmt]). */
    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /**
     * The event the line shows. Of the first day that still has an event, a timed event beats an
     * all-day one, and one about to start beats one that has been running a while.
     */
    fun pickNext(events: List<CalendarEvent>, nowMillis: Long, zone: ZoneId): CalendarEvent? {
        val today = day(nowMillis, zone)
        val live = live(events, nowMillis, zone)
        if (live.isEmpty()) return null
        val firstDay = live.minOf { dayOf(it, today, zone) }
        val thatDay = live.filter { dayOf(it, today, zone) == firstDay }
        val timed = thatDay.filter { !it.allDay }
        return timed.filter { it.beginMillis >= nowMillis - JUST_STARTED_MS }.minByOrNull { it.beginMillis }
            ?: timed.minByOrNull { it.endMillis }
            ?: thatDay.minByOrNull { it.title.lowercase(Locale.ROOT) }
    }

    /**
     * The events the widget lists, the next one first (see [pickNext]), then the rest of the week in the
     * order they come: by day, a timed event before an all-day one, then by start.
     */
    fun pickUpcoming(events: List<CalendarEvent>, nowMillis: Long, zone: ZoneId): List<CalendarEvent> {
        val first = pickNext(events, nowMillis, zone) ?: return emptyList()
        val today = day(nowMillis, zone)
        val rest = live(events, nowMillis, zone).filterNot { it === first }.sortedWith(
            compareBy<CalendarEvent>({ dayOf(it, today, zone) }, { it.allDay }, { it.beginMillis })
                .thenBy { it.title.lowercase(Locale.ROOT) }
        )
        return listOf(first) + rest
    }

    /** The events still to come, or on now, within the look-ahead. */
    private fun live(events: List<CalendarEvent>, nowMillis: Long, zone: ZoneId): List<CalendarEvent> {
        val today = day(nowMillis, zone)
        val lastDay = day(nowMillis + LOOK_AHEAD_MS, zone)
        return events.filter { e ->
            if (e.allDay) {
                e.dayAfter > today && e.firstDay <= lastDay
            } else {
                e.endMillis > nowMillis && e.beginMillis < nowMillis + LOOK_AHEAD_MS
            }
        }
    }

    /** The day an event counts for: the day it starts, or today while it is still on. */
    private fun dayOf(e: CalendarEvent, today: Long, zone: ZoneId): Long =
        maxOf(if (e.allDay) e.firstDay else day(e.beginMillis, zone), today)

    /** "Now", "15:00", "15:30", "Tmrw 09:00", "Fri 15:00"; for an all-day event "Today", "Tmrw", "Fri". */
    fun whenLabel(e: CalendarEvent, nowMillis: Long, zone: ZoneId): String {
        val today = day(nowMillis, zone)
        if (e.allDay) return dayWord(maxOf(e.firstDay, today), today)
        if (e.beginMillis <= nowMillis) return "Now"
        val start = Instant.ofEpochMilli(e.beginMillis).atZone(zone)
        val clock = clockFmt.format(start)
        val startDay = start.toLocalDate().toEpochDay()
        return if (startDay == today) clock else "${dayWord(startDay, today)} $clock"
    }

    /** "Today", "Tmrw", or the short name of the weekday. */
    private fun dayWord(date: Long, today: Long): String = when (date) {
        today -> "Today"
        today + 1 -> "Tmrw"
        else -> LocalDate.ofEpochDay(date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    }

    /** "15:00 Lunch". A title with no words reads "Event". */
    fun lineText(e: CalendarEvent, nowMillis: Long, zone: ZoneId): String {
        val title = e.title.replace(Regex("\\s+"), " ").trim().ifEmpty { "Event" }
        return "${whenLabel(e, nowMillis, zone)} $title"
    }

    /** The title on its own, tidied, never empty: the scrolling list shows it beside the bold when-label. */
    fun titleText(e: CalendarEvent): String =
        e.title.replace(Regex("\\s+"), " ").trim().ifEmpty { "Event" }

    /**
     * One row of the Wide widget's scrolling event list: the bold when-label ("15:00", "Tmrw", "Fri 12:30")
     * and the title, kept apart so the list row can draw the label bold and never cut it. Pure, so it is
     * tested on the JVM; the widget's list factory turns each [CalendarEvent] into one of these.
     */
    data class EventRow(val event: CalendarEvent, val whenLabel: String, val title: String)

    /** The rows for the scrolling list, the next event first (see [pickUpcoming]). */
    fun rows(events: List<CalendarEvent>, nowMillis: Long, zone: ZoneId): List<EventRow> =
        pickUpcoming(events, nowMillis, zone).let { rowsFromPicked(it, nowMillis, zone) }

    /** The rows for events already put in order by [pickUpcoming]; maps each to its when-label and title. */
    fun rowsFromPicked(picked: List<CalendarEvent>, nowMillis: Long, zone: ZoneId): List<EventRow> =
        picked.map { EventRow(it, whenLabel(it, nowMillis, zone), titleText(it)) }

    /**
     * When the line next changes by itself: as the event starts ("Now") or ends, or at midnight,
     * when "Tmrw" turns into today.
     */
    fun nextChangeMillis(e: CalendarEvent, nowMillis: Long, zone: ZoneId): Long {
        val midnight = LocalDate.ofEpochDay(day(nowMillis, zone) + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val own = when {
            e.allDay -> midnight
            e.beginMillis > nowMillis -> e.beginMillis
            else -> e.endMillis
        }
        return minOf(own, midnight)
    }

    private fun day(millis: Long, zone: ZoneId): Long = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()
}
