package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class WidgetLogicTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    private fun readings(vararg v: Double, at: Long = 1_000L) = StationReadings(
        stations = v.indices.map { Station("S$it", "Station $it", 1.3, 103.8) },
        values = v.indices.associate { "S$it" to v[it] },
        timestampMillis = at,
        unit = "deg C"
    )

    private val bedok = Areas.byName("Bedok")!!

    @Test
    fun averageIsTheMeanOfAllStations() {
        assertEquals(31.0, WidgetLogic.averageTemp(readings(30.0, 32.0, 31.0))!!, 0.0001)
    }

    @Test
    fun averageSkipsValuesThatAreNotNumbers() {
        assertEquals(31.0, WidgetLogic.averageTemp(readings(30.0, Double.NaN, 32.0))!!, 0.0001)
        assertNull(WidgetLogic.averageTemp(readings(Double.NaN)))
        assertNull(WidgetLogic.averageTemp(null))
    }

    @Test
    fun averageOfTheRealFixtureIsAPlausibleSingaporeTemperature() {
        val avg = WidgetLogic.averageTemp(NeaParsers.stationReadings(fixture("air-temperature.json")))
        assertNotNull(avg)
        assertTrue("average was $avg", avg!! in 20.0..40.0)
    }

    @Test
    fun modelUsesTheAreasRegionForPsiAndItsTimestamp() {
        val psi = ReadingSet(Metric.PSI, mapOf("east" to 154, "central" to 164), 5_000L)
        val model = WidgetLogic.build(bedok, null, readings(30.0, 32.0), psi)
        assertEquals("Bedok", model.areaName)
        assertEquals(154, model.psi)
        assertEquals(5_000L, model.recordedMillis)
        assertEquals("31°", model.tempText)
        assertEquals(Band.UNHEALTHY, model.band)
    }

    @Test
    fun modelUsesTheForecastForTheArea() {
        val forecast = NeaParsers.forecast2h(fixture("two-hr-forecast.json"))
        val model = WidgetLogic.build(bedok, forecast, null, null)
        assertEquals(forecast.forArea("Bedok"), model.condition)
        assertNotNull(model.condition)
    }

    @Test
    fun recordedTimeFallsBackToTemperatureWithoutPsi() {
        val model = WidgetLogic.build(bedok, null, readings(30.0, at = 7_000L), null)
        assertNull(model.psi)
        assertEquals(7_000L, model.recordedMillis)
    }

    @Test
    fun missingDataShowsPlaceholders() {
        val model = WidgetLogic.build(bedok, null, null, null)
        assertEquals("--°", model.tempText)
        assertNull(model.band)
        assertNull(model.recordedMillis)
        assertNull(model.condition)
        assertEquals("--", model.conditionText)
    }

    /** A moment on 18 Oct 2026, Singapore time. */
    private fun sgt(hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 10, 18, hour, minute, 0, 0, TimeFmt.SGT).toInstant().toEpochMilli()

    @Test
    fun nightRunsFromSevenPmToSevenAmSingaporeTime() {
        for (night in listOf(sgt(19), sgt(23, 59), sgt(0), sgt(3), sgt(6, 59))) assertTrue(WidgetLogic.isNight(night))
        for (day in listOf(sgt(7), sgt(12), sgt(18, 59))) assertFalse(WidgetLogic.isNight(day))
    }

    @Test
    fun eachKindOfDaytimeWeatherGetsItsOwnSky() {
        val noon = sgt(12)
        assertEquals(SkyScene.SUN, WidgetLogic.scene("Fair (Day)", noon))
        assertEquals(SkyScene.SUN, WidgetLogic.scene("Fair & Warm", noon))
        assertEquals(SkyScene.PARTLY, WidgetLogic.scene("Partly Cloudy (Day)", noon))
        assertEquals(SkyScene.CLOUD, WidgetLogic.scene("Cloudy", noon))
        assertEquals(SkyScene.CLOUD, WidgetLogic.scene("Overcast", noon))
        assertEquals(SkyScene.RAIN, WidgetLogic.scene("Light Showers", noon))
        assertEquals(SkyScene.RAIN, WidgetLogic.scene("Moderate Rain", noon))
        assertEquals(SkyScene.STORM, WidgetLogic.scene("Heavy Thundery Showers with Gusty Winds", noon))
        assertEquals(SkyScene.HAZE, WidgetLogic.scene("Hazy", noon))
        assertEquals(SkyScene.HAZE, WidgetLogic.scene("Mist", noon))
        assertEquals(SkyScene.WIND, WidgetLogic.scene("Windy", noon))
    }

    @Test
    fun noSunShinesAfterDark() {
        val late = sgt(21)
        assertEquals(SkyScene.NIGHT, WidgetLogic.scene("Fair (Night)", late))
        // A forecast made before 7 pm can still say "Day".
        assertEquals(SkyScene.NIGHT, WidgetLogic.scene("Fair (Day)", late))
        assertEquals(SkyScene.CLOUD, WidgetLogic.scene("Partly Cloudy (Night)", late))
        assertEquals(SkyScene.CLOUD, WidgetLogic.scene("Partly Cloudy (Day)", late))
        // Rain, storms, haze and wind look the same at any hour.
        assertEquals(SkyScene.RAIN, WidgetLogic.scene("Showers", late))
        assertEquals(SkyScene.STORM, WidgetLogic.scene("Thundery Showers", late))
        assertEquals(SkyScene.HAZE, WidgetLogic.scene("Hazy", late))
        assertEquals(SkyScene.WIND, WidgetLogic.scene("Windy", late))
    }

    @Test
    fun nightWordingKeepsTheNightSkyJustAfterSevenAm() {
        assertEquals(SkyScene.NIGHT, WidgetLogic.scene("Fair (Night)", sgt(7, 15)))
        assertEquals(SkyScene.SUN, WidgetLogic.scene("Fair (Day)", sgt(7, 15)))
    }

    @Test
    fun missingOrUnknownWeatherShowsClouds() {
        assertEquals(SkyScene.CLOUD, WidgetLogic.scene(null, sgt(12)))
        assertEquals(SkyScene.CLOUD, WidgetLogic.scene("Something new", sgt(12)))
    }

    @Test
    fun weatherWordsDropDayAndNight() {
        assertEquals("Partly Cloudy", WidgetLogic.conditionLabel("Partly Cloudy (Day)"))
        assertEquals("Fair", WidgetLogic.conditionLabel("Fair (Night)"))
        assertEquals("Thundery Showers", WidgetLogic.conditionLabel("Thundery Showers"))
        assertNull(WidgetLogic.conditionLabel(" (Day)"))
        assertNull(WidgetLogic.conditionLabel(null))
    }

    @Test
    fun everySkyHasItsOwnKey() {
        assertEquals(SkyScene.entries.size, SkyScene.entries.map { it.key }.toSet().size)
        for (s in SkyScene.entries) assertEquals(s, SkyScene.fromKey(s.key))
        assertNull(SkyScene.fromKey("auto"))
    }
}
