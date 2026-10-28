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

/** One event, or one time of a repeating event. */
data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    /** Where it is, as typed in the calendar. May be empty. */
    val location: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean
) {
    /** The first day of an all-day event. Android keeps all-day events from midnight UTC. */
    val firstDay: Long get() = utcDay(beginMillis)

    /** The day after the last day of an all-day event. */
    val dayAfter: Long get() = maxOf(utcDay(endMillis), firstDay + 1)

    private fun utcDay(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
}

/** Where an event's weather comes from. */
sealed interface EventPlace {
    data class SingaporeArea(val area: Area) : EventPlace
    data class City(val place: Place) : EventPlace
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

/** The next event on the widget line, and the weather for it. */
object EventLogic {

    /** How far ahead the line looks. */
    const val LOOK_AHEAD_MS = 7 * 24 * 3_600_000L

    /** An event that began this recently still beats one that has been running a while. */
    private const val JUST_STARTED_MS = 15 * 60_000L
    private const val HOUR_MS = 3_600_000L

    /** Names of Singapore areas that are everyday words too. They count only next to "Singapore". */
    private val COMMON_WORDS = setOf("City")

    private val clockFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val hourFmt = DateTimeFormatter.ofPattern("h a", Locale.ENGLISH)

    /**
     * The event the line shows. Of the first day that still has an event, a timed event beats an
     * all-day one, and one about to start beats one that has been running a while.
     */
    fun pickNext(events: List<CalendarEvent>, nowMillis: Long, zone: ZoneId): CalendarEvent? {
        val today = day(nowMillis, zone)
        val lastDay = day(nowMillis + LOOK_AHEAD_MS, zone)
        val live = events.filter { e ->
            if (e.allDay) {
                e.dayAfter > today && e.firstDay <= lastDay
            } else {
                e.endMillis > nowMillis && e.beginMillis < nowMillis + LOOK_AHEAD_MS
            }
        }
        if (live.isEmpty()) return null
        val firstDay = live.minOf { dayOf(it, today, zone) }
        val thatDay = live.filter { dayOf(it, today, zone) == firstDay }
        val timed = thatDay.filter { !it.allDay }
        return timed.filter { it.beginMillis >= nowMillis - JUST_STARTED_MS }.minByOrNull { it.beginMillis }
            ?: timed.minByOrNull { it.endMillis }
            ?: thatDay.minByOrNull { it.title.lowercase(Locale.ROOT) }
    }

    /** The day an event counts for: the day it starts, or today while it is still on. */
    private fun dayOf(e: CalendarEvent, today: Long, zone: ZoneId): Long =
        maxOf(if (e.allDay) e.firstDay else day(e.beginMillis, zone), today)

    /** "Now", "3 pm", "3:30 pm", "Tmrw 9 am", "Fri 3 pm"; for an all-day event "Today", "Tmrw", "Fri". */
    fun whenLabel(e: CalendarEvent, nowMillis: Long, zone: ZoneId): String {
        val today = day(nowMillis, zone)
        if (e.allDay) return dayWord(maxOf(e.firstDay, today), today)
        if (e.beginMillis <= nowMillis) return "Now"
        val start = Instant.ofEpochMilli(e.beginMillis).atZone(zone)
        val clock = (if (start.minute == 0) hourFmt else clockFmt).format(start).lowercase(Locale.ENGLISH)
        val startDay = start.toLocalDate().toEpochDay()
        return if (startDay == today) clock else "${dayWord(startDay, today)} $clock"
    }

    /** "Today", "Tmrw", or the short name of the weekday. */
    private fun dayWord(date: Long, today: Long): String = when (date) {
        today -> "Today"
        today + 1 -> "Tmrw"
        else -> LocalDate.ofEpochDay(date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    }

    /** "3 pm Lunch". A title with no words reads "Event". */
    fun lineText(e: CalendarEvent, nowMillis: Long, zone: ZoneId): String {
        val title = e.title.replace(Regex("\\s+"), " ").trim().ifEmpty { "Event" }
        return "${whenLabel(e, nowMillis, zone)} $title"
    }

    /**
     * The place an event names: a saved city, else a Singapore forecast area, matched as whole words.
     * Null when it names neither; the widget then uses its own place. Nothing leaves the phone.
     */
    fun placeFor(location: String, cities: List<Place>, areas: List<Area> = Areas.all): EventPlace? {
        val text = location.trim()
        if (text.isEmpty()) return null
        val city = cities.firstOrNull { hasWord(text, it.name) }
        if (city != null) return EventPlace.City(city)
        val inSingapore = hasWord(text, "Singapore")
        // The longest name first, so a long name is never taken for a short one inside it.
        val area = areas.sortedByDescending { it.name.length }
            .firstOrNull { hasWord(text, it.name) && (inSingapore || it.name !in COMMON_WORDS) }
        return area?.let { EventPlace.SingaporeArea(it) }
    }

    private fun hasWord(text: String, word: String): Boolean =
        word.isNotBlank() &&
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)

    /**
     * NEA's words for an area at the event's time: the 2-hour forecast while it lasts, then the
     * 24-hour forecast for the area's region, then the 4-day outlook. Null past all of them.
     */
    fun singaporeWeather(
        e: CalendarEvent,
        area: Area,
        nowMillis: Long,
        twoHour: Forecast2h?,
        nextDay: Forecast24?,
        outlook: List<DayForecast>?
    ): String? {
        val today = day(nowMillis, TimeFmt.SGT)
        // A 24-hour forecast saved yesterday says nothing about today.
        val freshDay = nextDay?.takeIf { d -> d.periods.any { it.endMillis > nowMillis } }
        if (e.allDay) {
            val date = maxOf(e.firstDay, today)
            if (date == today && freshDay != null) return freshDay.general
            return outlook?.firstOrNull { day(it.dateMillis, TimeFmt.SGT) == date }?.forecast
        }
        // A running event gets the weather for now, not for when it began.
        val at = maxOf(e.beginMillis, nowMillis)
        if (twoHour != null && at < twoHour.validUntilMillis) {
            val text = twoHour.forArea(area.name)
            if (text != null) return text
        }
        val period = freshDay?.periods?.firstOrNull { at >= it.startMillis && at < it.endMillis }
        val regional = period?.byRegion?.get(area.region.key)
        if (regional != null) return regional
        val date = day(at, TimeFmt.SGT)
        return outlook?.firstOrNull { day(it.dateMillis, TimeFmt.SGT) == date }?.forecast
    }

    /** The weather at a saved city at the event's time: that hour if known, else that day. */
    fun cityWeather(e: CalendarEvent, weather: PlaceWeather?, nowMillis: Long): Condition? {
        if (weather == null) return null
        val zone = TimeFmt.zoneOrDefault(weather.timezone)
        val at = maxOf(e.beginMillis, nowMillis)
        if (!e.allDay) {
            val hour = weather.hours.firstOrNull { at >= it.startMillis && at < it.startMillis + HOUR_MS }
            val code = hour?.code
            if (hour != null && code != null) return Wmo.condition(code, hour.isDay)
        }
        val date = if (e.allDay) maxOf(e.firstDay, day(nowMillis, zone)) else day(at, zone)
        val code = weather.days.firstOrNull { it.date.toEpochDay() == date }?.code ?: return null
        return Wmo.condition(code, true)
    }

    /** A word or two for the line. */
    fun word(c: Condition): String = when (c) {
        Condition.SUN -> "Sunny"
        Condition.MOON -> "Clear"
        Condition.PARTLY -> "Partly cloudy"
        Condition.CLOUD -> "Cloudy"
        Condition.RAIN -> "Rain"
        Condition.STORM -> "Thunder"
        Condition.HAZE -> "Hazy"
        Condition.WIND -> "Windy"
        Condition.SNOW -> "Snow"
    }

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
