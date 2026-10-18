package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLogicTest {

    // Sunday 18 Oct 2026, 10:45 am in Singapore.
    private val now = 1792291500000L

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    private val periods = NeaParsers.forecast24(fixture("twenty-four-hr-forecast.json")).periods

    @Test
    fun conditionFromNeaText() {
        assertEquals(Condition.CLOUD, Condition.fromText("Cloudy"))
        assertEquals(Condition.PARTLY, Condition.fromText("Partly Cloudy (Day)"))
        assertEquals(Condition.CLOUD, Condition.fromText("Partly Cloudy (Night)"))
        assertEquals(Condition.MOON, Condition.fromText("Fair (Night)"))
        assertEquals(Condition.SUN, Condition.fromText("Fair (Day)"))
        assertEquals(Condition.SUN, Condition.fromText("Fair and Warm"))
        assertEquals(Condition.STORM, Condition.fromText("Thundery Showers"))
        assertEquals(Condition.STORM, Condition.fromText("Heavy Thundery Showers with Gusty Winds"))
        assertEquals(Condition.RAIN, Condition.fromText("Passing Showers"))
        assertEquals(Condition.RAIN, Condition.fromText("Light Rain"))
        assertEquals(Condition.HAZE, Condition.fromText("Hazy"))
        assertEquals(Condition.HAZE, Condition.fromText("Slightly Hazy"))
        assertEquals(Condition.WIND, Condition.fromText("Windy"))
        assertEquals(Condition.CLOUD, Condition.fromText(null))
        assertEquals(Condition.CLOUD, Condition.fromText("something new"))
    }

    @Test
    fun wetMeansRainOrStorm() {
        assertTrue(Condition.isWet("Showers"))
        assertTrue(Condition.isWet("Thundery Showers"))
        assertFalse(Condition.isWet("Cloudy"))
        assertFalse(Condition.isWet(null))
    }

    @Test
    fun periodLabelsFollowTheStartHour() {
        assertEquals("Morning", ForecastText.periodLabel(periods[0].startMillis, now))
        assertEquals("Afternoon", ForecastText.periodLabel(periods[1].startMillis, now))
        assertEquals("Night", ForecastText.periodLabel(periods[2].startMillis, now))
        // 6 am the next day
        assertEquals("Tmrw morning", ForecastText.periodLabel(periods[0].startMillis + 86_400_000L, now))
    }

    @Test
    fun laterNoteIsPerRegion() {
        assertNull(ForecastText.laterNote(periods, "central", now, "Cloudy"))
        assertEquals("thundery showers later", ForecastText.laterNote(periods, "north", now, "Cloudy"))
        assertEquals("thundery showers later", ForecastText.laterNote(periods, "west", now, null))
    }

    @Test
    fun noLaterNoteWhenAlreadyWet() {
        assertNull(ForecastText.laterNote(periods, "north", now, "Light Rain"))
    }

    @Test
    fun noLaterNoteForPeriodsThatAlreadyStarted() {
        // At 1 pm the afternoon period (from midday) has started, so it is not "later".
        val onePm = periods[1].startMillis + 3_600_000L
        assertNull(ForecastText.laterNote(periods, "north", onePm, "Cloudy"))
    }

    @Test
    fun rainElsewhereNamesTheWetRegions() {
        assertEquals("Thundery Showers N, W", ForecastText.rainElsewhere(periods[1], "central"))
        assertNull(ForecastText.rainElsewhere(periods[1], "north")) // north is wet itself
        assertNull(ForecastText.rainElsewhere(periods[0], "central")) // nowhere is wet
    }

    @Test
    fun windCompass() {
        assertEquals("SSE", WindFmt.compass(163.0))
        assertEquals("N", WindFmt.compass(0.0))
        assertEquals("N", WindFmt.compass(359.0))
        assertEquals("N", WindFmt.compass(-10.0))
        assertEquals("E", WindFmt.compass(90.0))
        assertEquals("SW", WindFmt.compass(225.0))
        assertEquals("NNW", WindFmt.compass(337.5))
    }

    @Test
    fun knotsToKmh() {
        assertEquals(7, WindFmt.kmh(3.8))
        assertEquals(0, WindFmt.kmh(0.0))
        assertEquals(19, WindFmt.kmh(10.0))
    }

    @Test
    fun rainLevelsFromFiveMinuteTotals() {
        assertEquals(RainLevel.DRY, RainLevel.forFiveMinutes(0.0))
        assertEquals(RainLevel.LIGHT, RainLevel.forFiveMinutes(0.2))
        assertEquals(RainLevel.MODERATE, RainLevel.forFiveMinutes(0.4))
        assertEquals(RainLevel.MODERATE, RainLevel.forFiveMinutes(0.8))
        assertEquals(RainLevel.HEAVY, RainLevel.forFiveMinutes(1.0))
        assertEquals(RainLevel.HEAVY, RainLevel.forFiveMinutes(4.0))
        assertEquals(RainLevel.VERY_HEAVY, RainLevel.forFiveMinutes(4.2))
        assertEquals(RainLevel.VERY_HEAVY, RainLevel.forFiveMinutes(12.0))
    }

    @Test
    fun tileEdges() {
        assertEquals(101.25, TileMath.lonLeft(100), 0.0001)
        assertEquals(104.0625, TileMath.lonLeft(101), 0.0001)
        assertEquals(0.0, TileMath.latTop(64), 0.0001)
        assertEquals(2.8106, TileMath.latTop(63), 0.001)
        // Singapore sits inside the two tiles we fetch.
        assertTrue(TileMath.lonLeft(100) < 103.6 && TileMath.lonLeft(102) > 104.1)
        assertTrue(TileMath.latTop(TileMath.SINGAPORE_Y) > 1.5 && TileMath.latTop(TileMath.SINGAPORE_Y + 1) < 1.1)
    }

    @Test
    fun timeFormatting() {
        assertEquals("10:45 am", TimeFmt.clock(now))
        assertEquals("6:00 pm", TimeFmt.clock(periods[2].startMillis))
        assertEquals("Sun", TimeFmt.dayShort(now))
        assertEquals("Mon", TimeFmt.dayShort(1792339200000L))
        assertEquals("2026-10-18", TimeFmt.isoDate(now))
        assertEquals(10, TimeFmt.hourOfDay(now))
        // 30 minutes past midnight in Singapore is still the 18th there, though it is the 17th in UTC.
        assertEquals("2026-10-18", TimeFmt.isoDate(1792254600000L))
    }
}
