package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    }
}
