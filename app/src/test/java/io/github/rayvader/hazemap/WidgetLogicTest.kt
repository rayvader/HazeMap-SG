package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
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
    fun everyWeatherHasItsOwnSkyAfterDark() {
        val late = sgt(21)
        assertEquals(SkyScene.NIGHT, WidgetLogic.scene("Fair (Night)", late))
        // A forecast made before 7 pm can still say "Day".
        assertEquals(SkyScene.NIGHT, WidgetLogic.scene("Fair (Day)", late))
        assertEquals(SkyScene.PARTLY_NIGHT, WidgetLogic.scene("Partly Cloudy (Night)", late))
        assertEquals(SkyScene.PARTLY_NIGHT, WidgetLogic.scene("Partly Cloudy (Day)", late))
        assertEquals(SkyScene.CLOUD_NIGHT, WidgetLogic.scene("Cloudy", late))
        assertEquals(SkyScene.RAIN_NIGHT, WidgetLogic.scene("Showers", late))
        assertEquals(SkyScene.STORM_NIGHT, WidgetLogic.scene("Thundery Showers", late))
        assertEquals(SkyScene.HAZE_NIGHT, WidgetLogic.scene("Hazy", late))
        assertEquals(SkyScene.WIND_NIGHT, WidgetLogic.scene("Windy", late))
    }

    @Test
    fun theNightSkyShowsOnlyAfterDark() {
        for (condition in Condition.entries) {
            val night = WidgetLogic.sceneOf(condition, night = true)
            assertTrue("$condition at night is $night", night.isNight)
            // A moon is a night sky whatever the clock says; everything else is a day sky by day.
            if (condition != Condition.MOON) {
                val day = WidgetLogic.sceneOf(condition, night = false)
                assertFalse("$condition by day is $day", day.isNight)
            }
        }
    }

    @Test
    fun nightWordingKeepsTheNightSkyJustAfterSevenAm() {
        assertEquals(SkyScene.NIGHT, WidgetLogic.scene("Fair (Night)", sgt(7, 15)))
        assertEquals(SkyScene.SUN, WidgetLogic.scene("Fair (Day)", sgt(7, 15)))
        assertEquals(SkyScene.PARTLY_NIGHT, WidgetLogic.scene("Partly Cloudy (Night)", sgt(7, 15)))
        assertEquals(SkyScene.PARTLY, WidgetLogic.scene("Partly Cloudy (Day)", sgt(7, 15)))
    }

    @Test
    fun missingOrUnknownWeatherShowsClouds() {
        assertEquals(SkyScene.CLOUD, WidgetLogic.scene(null, sgt(12)))
        assertEquals(SkyScene.CLOUD, WidgetLogic.scene("Something new", sgt(12)))
        assertEquals(SkyScene.CLOUD_NIGHT, WidgetLogic.scene(null, sgt(23)))
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

    @Test
    fun singaporeWidgetsKeepSingaporeTimeWhereverThePhoneIs() {
        assertEquals("Asia/Singapore", WidgetLogic.build(bedok, null, null, null).zoneId)
    }

    // ---- A saved city ----

    private val tokyo = Place(1850147L, "Tokyo", "Tokyo", "Japan", "JP", 35.69, 139.69, "Asia/Tokyo")

    private fun weather(
        code: Int? = 61,
        isDay: Boolean = true,
        temp: Double? = 18.4,
        humidity: Int? = 62,
        zone: String = "Asia/Tokyo"
    ) = PlaceWeather(
        tempC = temp,
        humidity = humidity,
        windKmh = 10,
        windFromDeg = 90,
        code = code,
        isDay = isDay,
        timezone = zone,
        days = emptyList()
    )

    private fun cityScene(code: Int?, isDay: Boolean, at: Long): SkyScene =
        WidgetLogic.buildCity(tokyo, weather(code = code, isDay = isDay), 1L).scene(at)

    @Test
    fun cityShowsItsOwnTemperatureWeatherAndHumidity() {
        val model = WidgetLogic.buildCity(tokyo, weather(), 9_000L)
        assertEquals("Tokyo", model.placeName)
        assertEquals("18°", model.tempText)
        assertEquals("Light rain", model.conditionText)
        assertEquals(62, model.humidity)
        assertEquals("Asia/Tokyo", model.zoneId)
        assertEquals(9_000L, model.recordedMillis)
        assertEquals(SkyScene.RAIN, model.scene(sgt(12)))
    }

    @Test
    fun cityNightFollowsTheSunThereNotTheSingaporeClock() {
        // Noon in Singapore but dark in the city: night. Late evening in Singapore but sunny there: sun.
        assertEquals(SkyScene.NIGHT, cityScene(code = 0, isDay = false, at = sgt(12)))
        assertEquals(SkyScene.SUN, cityScene(code = 0, isDay = true, at = sgt(23)))
        assertEquals(SkyScene.PARTLY_NIGHT, cityScene(code = 2, isDay = false, at = sgt(12)))
        assertEquals(SkyScene.PARTLY, cityScene(code = 2, isDay = true, at = sgt(23)))
        // The other weather has its night sky too.
        assertEquals(SkyScene.RAIN_NIGHT, cityScene(code = 63, isDay = false, at = sgt(12)))
        assertEquals(SkyScene.STORM_NIGHT, cityScene(code = 95, isDay = false, at = sgt(12)))
        assertEquals(SkyScene.HAZE_NIGHT, cityScene(code = 45, isDay = false, at = sgt(12)))
    }

    @Test
    fun cityWeatherCodesPickTheirSky() {
        assertEquals(SkyScene.SUN, cityScene(1, true, sgt(12)))
        assertEquals(SkyScene.CLOUD, cityScene(3, true, sgt(12)))
        assertEquals(SkyScene.HAZE, cityScene(45, true, sgt(12)))
        assertEquals(SkyScene.RAIN, cityScene(63, true, sgt(12)))
        assertEquals(SkyScene.RAIN, cityScene(81, true, sgt(12)))
        assertEquals(SkyScene.STORM, cityScene(95, true, sgt(12)))
        // There is no snow sky yet: snow shows clouds.
        assertEquals(SkyScene.CLOUD, cityScene(73, true, sgt(12)))
    }

    @Test
    fun cityWithNothingLoadedShowsPlaceholders() {
        val model = WidgetLogic.buildCity(tokyo, null, null)
        assertEquals("--°", model.tempText)
        assertEquals("--", model.conditionText)
        assertNull(model.humidity)
        assertNull(model.recordedMillis)
        assertEquals("Asia/Tokyo", model.zoneId)
        assertEquals(SkyScene.CLOUD, model.scene(sgt(12)))
    }

    @Test
    fun cityClockUsesTheZoneFromTheAnswerThenTheSavedOne() {
        val answered = WidgetLogic.buildCity(tokyo.copy(timezone = "UTC"), weather(zone = "Asia/Tokyo"), 1L)
        assertEquals("Asia/Tokyo", answered.zoneId)
        assertEquals("Asia/Tokyo", WidgetLogic.buildCity(tokyo, null, null).zoneId)
        // A zone id Java does not know falls back to the phone's zone, as the rest of the app does.
        val unknown = WidgetLogic.buildCity(tokyo.copy(timezone = "Mars/Olympus"), null, null)
        assertEquals(ZoneId.systemDefault().id, unknown.zoneId)
    }

    @Test
    fun cityBuiltFromARealOpenMeteoAnswer() {
        val body = """{"timezone":"Asia/Tokyo","current":{"temperature_2m":21.6,"relative_humidity_2m":55,
            "wind_speed_10m":7.2,"wind_direction_10m":200,"weather_code":3,"is_day":0}}"""
        val model = WidgetLogic.buildCity(tokyo, PlaceParsers.forecast(body), 5_000L)
        assertEquals("22°", model.tempText)
        assertEquals("Overcast", model.conditionText)
        assertEquals(55, model.humidity)
        // The answer says the sun is down in Tokyo, so it is cloudy night there.
        assertEquals(SkyScene.CLOUD_NIGHT, model.scene(sgt(12)))
    }

    // ---- Where the weather is from ----

    @Test
    fun aCityWidgetSaysWhereTheWeatherIsFrom() {
        val model = WidgetLogic.buildCity(tokyo, weather(), 9_000L)
        assertEquals("Tokyo, Japan", model.placeLabel)
        // The name alone is still there, for the log line the emulator test reads.
        assertEquals("Tokyo", model.placeName)
    }

    @Test
    fun aSingaporeWidgetSaysItsForecastArea() {
        val model = WidgetLogic.build(bedok, null, null, null)
        assertEquals("Bedok", model.placeLabel)
        assertEquals("Bedok", model.placeName)
    }
}
