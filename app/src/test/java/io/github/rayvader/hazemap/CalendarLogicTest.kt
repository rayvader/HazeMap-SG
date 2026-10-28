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

    /** A moment in October 2026, Singapore time. 28 Oct 2026 is a Wednesday. */
    private fun sgt(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, sgtZone).toInstant().toEpochMilli()

    private fun timed(begin: Long, minutes: Int = 60, title: String = "Lunch") =
        CalendarEvent(
            eventId = begin / 1000,
            calendarId = 1,
            title = title,
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
    fun timesReadLikeA24HourClock() {
        val now = sgt(28, 12)
        assertEquals("15:00", EventLogic.whenLabel(timed(sgt(28, 15)), now, sgtZone))
        assertEquals("15:30", EventLogic.whenLabel(timed(sgt(28, 15, 30)), now, sgtZone))
        assertEquals("Now", EventLogic.whenLabel(timed(sgt(28, 11, 50)), now, sgtZone))
        assertEquals("Tmrw 09:00", EventLogic.whenLabel(timed(sgt(29, 9)), now, sgtZone))
        assertEquals("Fri 12:00", EventLogic.whenLabel(timed(sgt(30, 12)), now, sgtZone))
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
        // 15:00 in Singapore is 07:00 in UTC, the same day.
        assertEquals("07:00", EventLogic.whenLabel(timed(sgt(28, 15)), sgt(28, 12), ZoneOffset.UTC))
    }

    @Test
    fun theLineIsTheTimeThenTheTitle() {
        val now = sgt(28, 12)
        assertEquals("15:00 Lunch", EventLogic.lineText(timed(sgt(28, 15)), now, sgtZone))
        assertEquals("15:00 Event", EventLogic.lineText(timed(sgt(28, 15), title = "  "), now, sgtZone))
        assertEquals("15:00 Lunch with Mei", EventLogic.lineText(timed(sgt(28, 15), title = "Lunch\nwith  Mei"), now, sgtZone))
    }

    @Test
    fun theLineChangesWhenTheEventStartsEndsOrTheDayTurns() {
        val now = sgt(28, 12)
        assertEquals(sgt(28, 15), EventLogic.nextChangeMillis(timed(sgt(28, 15)), now, sgtZone))
        assertEquals(sgt(28, 13), EventLogic.nextChangeMillis(timed(sgt(28, 11), minutes = 120), now, sgtZone))
        // Tomorrow's event first changes at midnight, from "Tmrw 09:00" to "09:00".
        assertEquals(sgt(29, 0), EventLogic.nextChangeMillis(timed(sgt(29, 9)), now, sgtZone))
        assertEquals(sgt(29, 0), EventLogic.nextChangeMillis(allDay(28), now, sgtZone))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
