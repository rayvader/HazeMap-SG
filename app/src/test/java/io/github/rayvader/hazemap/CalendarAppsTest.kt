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
}
