package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarAppsTest {
    private val both = setOf(CalendarApps.SAMSUNG, CalendarApps.GOOGLE)

    @Test
    fun todaysViewComesFirst() {
        assertEquals(CalendarTap.Today, CalendarApps.choose(opensToday = true, hasCalendarApp = true, launchable = both))
        assertEquals(CalendarTap.Today, CalendarApps.choose(true, false, emptySet()))
    }

    @Test
    fun thenTheCalendarCategory() {
        assertEquals(CalendarTap.MainCalendar, CalendarApps.choose(false, true, both))
        assertEquals(CalendarTap.MainCalendar, CalendarApps.choose(false, true, emptySet()))
    }

    @Test
    fun thenSamsungsCalendar() {
        assertEquals(CalendarTap.Package(CalendarApps.SAMSUNG), CalendarApps.choose(false, false, both))
    }

    @Test
    fun thenGooglesCalendar() {
        assertEquals(CalendarTap.Package(CalendarApps.GOOGLE), CalendarApps.choose(false, false, setOf(CalendarApps.GOOGLE)))
    }

    @Test
    fun thenThisApp() {
        assertEquals(CalendarTap.App, CalendarApps.choose(false, false, emptySet()))
        assertEquals(CalendarTap.App, CalendarApps.choose(false, false, setOf("com.example.other")))
    }

    @Test
    fun theOrderIsSamsungThenGoogle() {
        assertEquals(listOf("com.samsung.android.calendar", "com.google.android.calendar"), CalendarApps.PACKAGES)
    }

    // ---- The tap target for one row of the Wide's scrolling list ----

    @Test
    fun aRowTapPointsAtThatEventsPageWithItsTimes() {
        val event = CalendarEvent(
            eventId = 4242,
            calendarId = 1,
            title = "Lunch",
            beginMillis = 1_700_000_000_000,
            endMillis = 1_700_003_600_000,
            allDay = false
        )
        val target = CalendarApps.eventTarget(event)
        assertEquals("content://com.android.calendar/events/4242", target.uri)
        assertEquals(1_700_000_000_000, target.beginMillis)
        assertEquals(1_700_003_600_000, target.endMillis)
    }

    @Test
    fun theEventsUriIsTheCalendarEventsTable() {
        assertEquals("content://com.android.calendar/events", CalendarApps.EVENTS_URI)
    }
}
