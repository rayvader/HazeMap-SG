package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarAppsTest {
    private val both = setOf(CalendarApps.SAMSUNG, CalendarApps.GOOGLE)

    @Test
    fun todaysViewComesFirst() {
        assertEquals(CalendarChoice.Today, CalendarApps.choose(opensToday = true, hasCalendarApp = true, launchable = both))
        assertEquals(CalendarChoice.Today, CalendarApps.choose(true, false, emptySet()))
    }

    @Test
    fun thenTheCalendarCategory() {
        assertEquals(CalendarChoice.MainCalendar, CalendarApps.choose(false, true, both))
        assertEquals(CalendarChoice.MainCalendar, CalendarApps.choose(false, true, emptySet()))
    }

    @Test
    fun thenSamsungsCalendar() {
        assertEquals(CalendarChoice.Package(CalendarApps.SAMSUNG), CalendarApps.choose(false, false, both))
    }

    @Test
    fun thenGooglesCalendar() {
        assertEquals(CalendarChoice.Package(CalendarApps.GOOGLE), CalendarApps.choose(false, false, setOf(CalendarApps.GOOGLE)))
    }

    @Test
    fun thenThisApp() {
        assertEquals(CalendarChoice.App, CalendarApps.choose(false, false, emptySet()))
        assertEquals(CalendarChoice.App, CalendarApps.choose(false, false, setOf("com.example.other")))
    }

    @Test
    fun theOrderIsSamsungThenGoogle() {
        assertEquals(listOf("com.samsung.android.calendar", "com.google.android.calendar"), CalendarApps.PACKAGES)
    }
}
