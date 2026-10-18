package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads real responses captured from data.gov.sg and RainViewer on 18 Oct 2026 (src/test/resources/fixtures). */
class NeaParsersTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    // Ang Mo Kio, where the app starts.
    private val amkLat = 1.375
    private val amkLon = 103.839

    @Test
    fun temperatureHasEveryStationAndPicksTheNearest() {
        val t = NeaParsers.stationReadings(fixture("air-temperature.json"))
        assertEquals(18, t.stations.size)
        assertEquals(18, t.values.size)
        assertEquals(1792291380000L, t.timestampMillis)
        assertEquals(31.1, t.nearestValue(amkLat, amkLon)!!, 0.001)
        assertEquals("deg C", t.unit)
    }

    @Test
    fun humidityAndWindForAngMoKio() {
        assertEquals(67.0, NeaParsers.stationReadings(fixture("relative-humidity.json")).nearestValue(amkLat, amkLon)!!, 0.001)
        assertEquals(3.8, NeaParsers.stationReadings(fixture("wind-speed.json")).nearestValue(amkLat, amkLon)!!, 0.001)
        assertEquals(163.0, NeaParsers.stationReadings(fixture("wind-direction.json")).nearestValue(amkLat, amkLon)!!, 0.001)
    }

    @Test
    fun nearestValueSkipsStationsThatDidNotReport() {
        val near = Station("near", "Near", 1.0, 103.0)
        val far = Station("far", "Far", 1.2, 103.2)
        val readings = StationReadings(listOf(near, far), mapOf("far" to 7.0), 0L, "")
        assertEquals(7.0, readings.nearestValue(1.0, 103.0)!!, 0.0)
        assertNull(StationReadings(listOf(near), emptyMap(), 0L, "").nearestValue(1.0, 103.0))
    }

    @Test
    fun twoHourForecastHasAllAreas() {
        val f = NeaParsers.forecast2h(fixture("two-hr-forecast.json"))
        assertEquals(47, f.byArea.size)
        assertEquals(1792290600000L, f.validFromMillis)
        assertEquals(1792297800000L, f.validUntilMillis)
        assertEquals("Cloudy", f.forArea("Ang Mo Kio"))
        assertEquals("Cloudy", f.forArea("ang mo kio"))
        assertNull(f.forArea("Atlantis"))
    }

    @Test
    fun twentyFourHourForecastHasPeriodsByRegion() {
        val f = NeaParsers.forecast24(fixture("twenty-four-hr-forecast.json"))
        assertEquals("Thundery Showers", f.general)
        assertEquals(25, f.low)
        assertEquals(34, f.high)
        assertEquals(3, f.periods.size)
        assertEquals(1792274400000L, f.periods[0].startMillis)
        assertEquals(f.periods[0].endMillis, f.periods[1].startMillis)
        assertEquals("Cloudy", f.periods[1].byRegion["central"])
        assertEquals("Thundery Showers", f.periods[1].byRegion["north"])
        assertEquals("Thundery Showers", f.periods[1].byRegion["west"])
        assertEquals(5, f.periods[1].byRegion.size)
    }

    @Test
    fun fourDayOutlook() {
        val days = NeaParsers.outlook(fixture("four-day-outlook.json"))
        assertEquals(4, days.size)
        assertEquals(1792339200000L, days[0].dateMillis)
        assertEquals("Thundery Showers", days[0].forecast)
        assertEquals("Afternoon thundery showers", days[0].summary)
        assertEquals(25, days[0].low)
        assertEquals(34, days[0].high)
        // NEA sends a trailing space on one summary; it must not leak through.
        assertEquals("Afternoon thundery showers", days[2].summary)
    }

    @Test
    fun uvIsNewestFirst() {
        val uv = NeaParsers.uv(fixture("uv.json"))
        assertEquals(4, uv.size)
        assertEquals(1792288800000L, uv[0].hourMillis)
        assertEquals(2, uv[0].index)
    }

    @Test
    fun uvWithNoRecordsIsEmpty() {
        assertTrue(NeaParsers.uv("""{"code":0,"data":{"records":[]},"errorMsg":""}""").isEmpty())
    }

    @Test
    fun latestRainfallCoversEveryGauge() {
        val page = NeaParsers.rainfall(fixture("rainfall-latest.json"))
        assertEquals(90, page.stations.size)
        assertEquals(1, page.frames.size)
        assertEquals(90, page.frames[0].mm.size)
        assertEquals(1792291200000L, page.frames[0].timeMillis)
        assertEquals(0.2, page.frames[0].mm["S305"]!!, 0.0001)
        assertNull(page.nextToken)
    }

    @Test
    fun dayRainfallIsOldestFirstAndDropsTheStillFillingFrame() {
        val page = NeaParsers.rainfall(fixture("rainfall-day.json"))
        assertEquals(listOf(1792290600000L, 1792290900000L, 1792291200000L, 1792291500000L), page.frames.map { it.timeMillis })
        // The newest frame (10:45) had only 4 of 90 gauges when captured.
        assertEquals(4, page.frames.last().mm.size)
        val complete = page.completeFrames()
        assertEquals(3, complete.size)
        assertEquals(1792291200000L, complete.last().timeMillis)
        assertNotNull(page.nextToken)
    }

    @Test
    fun radarIndexIsOldestFirstWithThirteenFrames() {
        val idx = NeaParsers.radarIndex(fixture("rainviewer.json"))
        assertEquals("https://tilecache.rainviewer.com", idx.host)
        assertEquals(13, idx.frames.size)
        assertEquals(1792286400000L, idx.frames.first().timeMillis)
        assertEquals(1792293600000L, idx.frames.last().timeMillis)
        assertEquals("/v2/radar/22da387ef0a5", idx.frames.last().path)
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/22da387ef0a5/256/7/100/63/2/1_0.png",
            TileMath.radarUrl(idx, idx.frames.last(), 100, TileMath.SINGAPORE_Y)
        )
    }

    @Test(expected = IllegalStateException::class)
    fun errorBodyIsRejected() {
        NeaParsers.forecast2h("""{"code":24,"name":"TOO_MANY_REQUESTS","data":null,"errorMsg":"slow down"}""")
    }
}
