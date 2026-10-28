package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class CalendarLogicTest {

    private val sgtZone: ZoneId = TimeFmt.SGT
    private val tokyoZone: ZoneId = ZoneId.of("Asia/Tokyo")

    /** A moment in October 2026, Singapore time. 28 Oct 2026 is a Wednesday. */
    private fun sgt(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, sgtZone).toInstant().toEpochMilli()

    private fun tokyo(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, tokyoZone).toInstant().toEpochMilli()

    private fun timed(begin: Long, minutes: Int = 60, title: String = "Lunch", location: String = "") =
        CalendarEvent(
            eventId = begin / 1000,
            calendarId = 1,
            title = title,
            location = location,
            beginMillis = begin,
            endMillis = begin + minutes * MINUTE,
            allDay = false
        )

    /** An all-day event, the way Android keeps it: from midnight UTC. */
    private fun allDay(day: Int, days: Int = 1, title: String = "Holiday"): CalendarEvent {
        val first = LocalDate.of(2026, 10, day)
        return CalendarEvent(
            eventId = 100L + day,
            calendarId = 1,
            title = title,
            location = "",
            beginMillis = first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            endMillis = first.plusDays(days.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            allDay = true
        )
    }

    // ---- Which calendars ----

    private fun cal(
        id: Long,
        visible: Boolean = true,
        syncOn: Boolean = true,
        type: String = "com.google",
        account: String = "me@gmail.com",
        name: String = "Cal $id"
    ) = PhoneCalendar(id, name, account, type, 0xFF3F7AD6.toInt(), visible, syncOn)

    @Test
    fun theListStartsWithTheCalendarsTheCalendarAppShows() {
        val calendars = listOf(cal(1), cal(2, visible = false), cal(3, syncOn = false))
        assertEquals(setOf(1L), CalendarChoice.chosen(calendars, null))
    }

    @Test
    fun ticksWinOnceMade() {
        val calendars = listOf(cal(1), cal(2, visible = false))
        assertEquals(setOf(2L), CalendarChoice.chosen(calendars, setOf(2L)))
        assertEquals(emptySet<Long>(), CalendarChoice.chosen(calendars, emptySet()))
        // A ticked calendar that is gone from the phone drops out.
        assertEquals(setOf(1L), CalendarChoice.chosen(calendars, setOf(1L, 9L)))
    }

    @Test
    fun aSwitchedOffCalendarNeverCounts() {
        val off = cal(3, syncOn = false)
        assertTrue(off.switchedOff)
        assertEquals(emptySet<Long>(), CalendarChoice.chosen(listOf(off), setOf(3L)))
        // A calendar only on the phone has nothing to sync with, so it is never off.
        assertFalse(cal(4, syncOn = false, type = PhoneCalendar.ACCOUNT_TYPE_LOCAL).switchedOff)
    }

    @Test
    fun theSummaryNamesUpToTwoCalendars() {
        val calendars = listOf(cal(1, name = "Personal"), cal(2, name = "Family"), cal(3, name = "Work"))
        assertEquals("Personal", CalendarChoice.summary(calendars, setOf(1L)))
        assertEquals("Personal, Family", CalendarChoice.summary(calendars, setOf(1L, 2L)))
        assertEquals("Personal, Family +1", CalendarChoice.summary(calendars, setOf(1L, 2L, 3L)))
        assertNull(CalendarChoice.summary(calendars, emptySet()))
    }

    @Test
    fun calendarsAreGroupedByAccountWithPhoneOnlyOnesLast() {
        val sorted = CalendarChoice.sorted(
            listOf(
                cal(1, account = "zed@gmail.com", name = "B"),
                cal(2, type = PhoneCalendar.ACCOUNT_TYPE_LOCAL, account = "phone", name = "A"),
                cal(3, account = "amy@gmail.com", name = "C"),
                cal(4, account = "zed@gmail.com", name = "a")
            )
        )
        assertEquals(listOf(3L, 4L, 1L, 2L), sorted.map { it.id })
    }

    // ---- Which event ----

    @Test
    fun theSoonestEventStillToComeIsNext() {
        val events = listOf(
            timed(sgt(28, 15), title = "Later"),
            timed(sgt(28, 13), title = "Soon"),
            timed(sgt(28, 9), title = "Over")
        )
        assertEquals("Soon", EventLogic.pickNext(events, sgt(28, 12), sgtZone)?.title)
    }

    @Test
    fun onOneDayATimedEventBeatsAnAllDayOne() {
        assertEquals("Lunch", EventLogic.pickNext(listOf(allDay(28), timed(sgt(28, 15))), sgt(28, 12), sgtZone)?.title)
    }

    @Test
    fun anAllDayEventShowsWhenItsDayHasNoTimedEvent() {
        val events = listOf(allDay(28), timed(sgt(29, 9), title = "Meeting"))
        assertEquals("Holiday", EventLogic.pickNext(events, sgt(28, 12), sgtZone)?.title)
    }

    @Test
    fun anEventAboutToStartBeatsOneRunningForHours() {
        val work = timed(sgt(28, 9), minutes = 9 * 60, title = "Work")
        val lunch = timed(sgt(28, 13), title = "Lunch")
        assertEquals("Lunch", EventLogic.pickNext(listOf(work, lunch), sgt(28, 12), sgtZone)?.title)
        // Once lunch is over, the running one is all that is left today.
        assertEquals("Work", EventLogic.pickNext(listOf(work, lunch), sgt(28, 14, 30), sgtZone)?.title)
    }

    @Test
    fun anEventThatJustStartedIsStillNext() {
        val standUp = timed(sgt(28, 11, 55), minutes = 15, title = "Stand-up")
        assertEquals("Stand-up", EventLogic.pickNext(listOf(standUp, timed(sgt(28, 13))), sgt(28, 12), sgtZone)?.title)
    }

    @Test
    fun yesterdaysAllDayEventIsOverInTheSingaporeMorning() {
        // 7 am on 28 Oct in Singapore is still 27 Oct in UTC, where Android keeps all-day events.
        val events = listOf(allDay(27, title = "Yesterday"), allDay(28, title = "Today"))
        assertEquals("Today", EventLogic.pickNext(events, sgt(28, 7), sgtZone)?.title)
    }

    @Test
    fun nothingInTheNextWeekShowsNoLine() {
        val now = sgt(28, 12)
        assertNull(EventLogic.pickNext(listOf(timed(now + 8 * DAY)), now, sgtZone))
        assertNull(EventLogic.pickNext(emptyList(), now, sgtZone))
    }

    // ---- How it reads ----

    @Test
    fun timesReadLikeAClock() {
        val now = sgt(28, 12)
        assertEquals("3 pm", EventLogic.whenLabel(timed(sgt(28, 15)), now, sgtZone))
        assertEquals("3:30 pm", EventLogic.whenLabel(timed(sgt(28, 15, 30)), now, sgtZone))
        assertEquals("Now", EventLogic.whenLabel(timed(sgt(28, 11, 50)), now, sgtZone))
        assertEquals("Tmrw 9 am", EventLogic.whenLabel(timed(sgt(29, 9)), now, sgtZone))
        assertEquals("Fri 12 pm", EventLogic.whenLabel(timed(sgt(30, 12)), now, sgtZone))
    }

    @Test
    fun allDayEventsReadAsADay() {
        val now = sgt(28, 12)
        assertEquals("Today", EventLogic.whenLabel(allDay(28), now, sgtZone))
        assertEquals("Tmrw", EventLogic.whenLabel(allDay(29), now, sgtZone))
        assertEquals("Fri", EventLogic.whenLabel(allDay(30), now, sgtZone))
        // One that began days ago and runs on through today.
        assertEquals("Today", EventLogic.whenLabel(allDay(26, days = 4), now, sgtZone))
    }

    @Test
    fun timesFollowThePhonesClock() {
        // 3 pm in Singapore is 7 am in UTC, the same day.
        assertEquals("7 am", EventLogic.whenLabel(timed(sgt(28, 15)), sgt(28, 12), ZoneOffset.UTC))
    }

    @Test
    fun theLineIsTheTimeThenTheTitle() {
        val now = sgt(28, 12)
        assertEquals("3 pm Lunch", EventLogic.lineText(timed(sgt(28, 15)), now, sgtZone))
        assertEquals("3 pm Event", EventLogic.lineText(timed(sgt(28, 15), title = "  "), now, sgtZone))
        assertEquals("3 pm Lunch with Mei", EventLogic.lineText(timed(sgt(28, 15), title = "Lunch\nwith  Mei"), now, sgtZone))
    }

    @Test
    fun weatherWordsAreShort() {
        assertEquals("Rain", EventLogic.word(Condition.RAIN))
        assertEquals("Thunder", EventLogic.word(Condition.STORM))
        assertEquals("Clear", EventLogic.word(Condition.MOON))
        for (c in Condition.entries) assertTrue(c.name, EventLogic.word(c).length <= 13)
    }

    @Test
    fun theLineChangesWhenTheEventStartsEndsOrTheDayTurns() {
        val now = sgt(28, 12)
        assertEquals(sgt(28, 15), EventLogic.nextChangeMillis(timed(sgt(28, 15)), now, sgtZone))
        assertEquals(sgt(28, 13), EventLogic.nextChangeMillis(timed(sgt(28, 11), minutes = 120), now, sgtZone))
        // Tomorrow's event first changes at midnight, from "Tmrw 9 am" to "9 am".
        assertEquals(sgt(29, 0), EventLogic.nextChangeMillis(timed(sgt(29, 9)), now, sgtZone))
        assertEquals(sgt(29, 0), EventLogic.nextChangeMillis(allDay(28), now, sgtZone))
    }

    // ---- Where it is ----

    private val tokyoCity = Place(1850147L, "Tokyo", "Tokyo", "Japan", "JP", 35.69, 139.69, "Asia/Tokyo")

    private fun areaName(text: String): String? =
        (EventLogic.placeFor(text, emptyList()) as? EventPlace.SingaporeArea)?.area?.name

    @Test
    fun aPlaceNamingASingaporeAreaUsesThatArea() {
        assertEquals("Bedok", areaName("Bedok Mall"))
        assertEquals("Jurong West", areaName("Jurong Point, 1 Jurong West Central 2, Singapore 648886"))
        assertEquals("Ang Mo Kio", areaName("ang mo kio hub"))
    }

    @Test
    fun aPlaceNamingASavedCityUsesThatCity() {
        assertEquals(EventPlace.City(tokyoCity), EventLogic.placeFor("Tokyo Station", listOf(tokyoCity)))
    }

    @Test
    fun namesOnlyMatchAsWholeWords() {
        assertNull(areaName("Bedokian Road"))
        assertNull(EventLogic.placeFor("Tokyoite bar", listOf(tokyoCity)))
    }

    @Test
    fun cityIsTheSingaporeAreaOnlyWhenSingaporeIsNamed() {
        assertNull(areaName("Ho Chi Minh City"))
        assertEquals("City", areaName("City Hall, Singapore"))
    }

    @Test
    fun noPlaceOrAnUnknownOneMatchesNothing() {
        assertNull(EventLogic.placeFor("", listOf(tokyoCity)))
        assertNull(EventLogic.placeFor("1 Raffles Place, Singapore 048616", listOf(tokyoCity)))
    }

    // ---- The weather for it ----

    private val bedok = Areas.byName("Bedok")!!

    private val twoHour = Forecast2h(sgt(28, 11, 30), sgt(28, 13, 30), mapOf("Bedok" to "Thundery Showers"))

    private val nextDay = Forecast24(
        general = "Partly Cloudy (Day)",
        low = 25,
        high = 33,
        periods = listOf(
            ForecastPeriod(sgt(28, 12), sgt(28, 18), mapOf("east" to "Light Showers")),
            ForecastPeriod(sgt(28, 18), sgt(29, 6), mapOf("east" to "Partly Cloudy (Night)")),
            ForecastPeriod(sgt(29, 6), sgt(29, 12), mapOf("east" to "Fair (Day)"))
        )
    )

    private val outlook = listOf(
        DayForecast(sgt(29, 0), "Cloudy", "", 25, 32),
        DayForecast(sgt(30, 0), "Thundery Showers", "", 25, 33)
    )

    private fun singapore(event: CalendarEvent, now: Long, twoHour: Forecast2h? = this.twoHour): String? =
        EventLogic.singaporeWeather(event, bedok, now, twoHour, nextDay, outlook)

    @Test
    fun singaporeEventsUseTheClosestForecastThatReachesThem() {
        val now = sgt(28, 12)
        // The 2-hour forecast for Bedok, while it lasts.
        assertEquals("Thundery Showers", singapore(timed(sgt(28, 13)), now))
        // After it, the 24-hour forecast for the east.
        assertEquals("Light Showers", singapore(timed(sgt(28, 15)), now))
        assertEquals("Partly Cloudy (Night)", singapore(timed(sgt(28, 21)), now))
        // After that, the 4-day outlook.
        assertEquals("Cloudy", singapore(timed(sgt(29, 14)), now))
        assertEquals("Thundery Showers", singapore(timed(sgt(30, 14)), now))
        // And nothing past it.
        assertNull(singapore(timed(sgt(31, 9)), now))
    }

    @Test
    fun aRunningEventGetsTheWeatherForNow() {
        // Began at 9, before the 24-hour forecast starts, and still on at 12:30.
        val event = timed(sgt(28, 9), minutes = 6 * 60)
        assertEquals("Light Showers", singapore(event, sgt(28, 12, 30), twoHour = null))
    }

    @Test
    fun anOldTwoHourForecastIsSkipped() {
        assertEquals("Light Showers", singapore(timed(sgt(28, 16)), sgt(28, 15)))
    }

    @Test
    fun allDaySingaporeEventsUseTheDaysForecast() {
        val now = sgt(28, 12)
        assertEquals("Partly Cloudy (Day)", singapore(allDay(28), now))
        assertEquals("Thundery Showers", singapore(allDay(30), now))
    }

    private val tokyoWeather = PlaceWeather(
        tempC = 18.0,
        humidity = 60,
        windKmh = 10,
        windFromDeg = 90,
        code = 3,
        isDay = true,
        timezone = "Asia/Tokyo",
        days = listOf(PlaceDay(LocalDate.of(2026, 10, 28), 61, 12, 19), PlaceDay(LocalDate.of(2026, 10, 29), 95, 13, 20)),
        hours = listOf(PlaceHour(tokyo(28, 15), 0, true), PlaceHour(tokyo(28, 22), 0, false))
    )

    @Test
    fun cityEventsUseThatHourThere() {
        val now = sgt(28, 12)
        assertEquals(Condition.SUN, EventLogic.cityWeather(timed(tokyo(28, 15, 30)), tokyoWeather, now))
        assertEquals(Condition.MOON, EventLogic.cityWeather(timed(tokyo(28, 22)), tokyoWeather, now))
    }

    @Test
    fun cityEventsWithoutThatHourUseTheDay() {
        val now = sgt(28, 12)
        assertEquals(Condition.STORM, EventLogic.cityWeather(timed(tokyo(29, 10)), tokyoWeather, now))
        assertEquals(Condition.RAIN, EventLogic.cityWeather(allDay(28), tokyoWeather, now))
        assertNull(EventLogic.cityWeather(allDay(31), tokyoWeather, now))
        assertNull(EventLogic.cityWeather(timed(tokyo(28, 15)), null, now))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
