package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockAppsTest {
    private val both = setOf(ClockApps.SAMSUNG, ClockApps.GOOGLE)

    @Test
    fun alarmsComeFirst() {
        assertEquals(ClockChoice.Alarms, ClockApps.choose(showsAlarms = true, launchable = both))
        assertEquals(ClockChoice.Alarms, ClockApps.choose(showsAlarms = true, launchable = emptySet()))
    }

    @Test
    fun thenSamsungsClock() {
        assertEquals(ClockChoice.Package(ClockApps.SAMSUNG), ClockApps.choose(false, both))
        assertEquals(ClockChoice.Package(ClockApps.SAMSUNG), ClockApps.choose(false, setOf(ClockApps.SAMSUNG)))
    }

    @Test
    fun thenGooglesClock() {
        assertEquals(ClockChoice.Package(ClockApps.GOOGLE), ClockApps.choose(false, setOf(ClockApps.GOOGLE)))
    }

    @Test
    fun thenThisApp() {
        assertEquals(ClockChoice.App, ClockApps.choose(false, emptySet()))
        assertEquals(ClockChoice.App, ClockApps.choose(false, setOf("com.example.other")))
    }

    @Test
    fun theOrderIsSamsungThenGoogle() {
        assertEquals(listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"), ClockApps.PACKAGES)
    }
}
