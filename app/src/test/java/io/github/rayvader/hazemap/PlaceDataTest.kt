package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlaceDataTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    private fun place(id: Long, name: String = "City $id") =
        Place(id, name, "Region", "Country", "XX", 1.0, 2.0, "Asia/Tokyo")

    // ---- Weather codes ----

    @Test
    fun clearSkyFollowsDayAndNight() {
        assertEquals(Condition.SUN, Wmo.condition(0, isDay = true))
        assertEquals(Condition.MOON, Wmo.condition(0, isDay = false))
        assertEquals(Condition.SUN, Wmo.condition(1, isDay = true))
    }

    @Test
    fun cloudAndWetCodes() {
        assertEquals(Condition.PARTLY, Wmo.condition(2, true))
        assertEquals(Condition.CLOUD, Wmo.condition(2, false))
        assertEquals(Condition.CLOUD, Wmo.condition(3, true))
        assertEquals(Condition.HAZE, Wmo.condition(45, true))
        assertEquals(Condition.RAIN, Wmo.condition(53, true))
        assertEquals(Condition.RAIN, Wmo.condition(63, true))
        assertEquals(Condition.RAIN, Wmo.condition(81, true))
        assertEquals(Condition.SNOW, Wmo.condition(73, true))
        assertEquals(Condition.SNOW, Wmo.condition(86, true))
        assertEquals(Condition.STORM, Wmo.condition(95, true))
        assertEquals(Condition.STORM, Wmo.condition(99, true))
    }

    @Test
    fun unknownOrMissingCodeIsCloudWithDashes() {
        assertEquals(Condition.CLOUD, Wmo.condition(null, true))
        assertEquals(Condition.CLOUD, Wmo.condition(1234, true))
        assertEquals("--", Wmo.label(null))
        assertEquals("--", Wmo.label(1234))
    }

    @Test
    fun everyCodeOpenMeteoDocumentsHasAWord() {
        val documented = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        for (code in documented) assertTrue("code $code", Wmo.label(code) != "--")
    }

    // ---- Place search ----

    @Test
    fun searchResultsAreParsed() {
        val found = PlaceParsers.geocoding(fixture("om-geocoding.json"))
        assertTrue(found.size >= 2)
        val tokyo = found.first()
        assertEquals(1850147L, tokyo.id)
        assertEquals("Tokyo", tokyo.name)
        assertEquals("Japan", tokyo.country)
        assertEquals("JP", tokyo.countryCode)
        assertEquals("Asia/Tokyo", tokyo.timezone)
        assertEquals(35.6895, tokyo.lat, 0.0001)
        assertEquals("Japan", tokyo.subtitle)
    }

    @Test
    fun noMatchesGivesAnEmptyList() {
        assertTrue(PlaceParsers.geocoding(fixture("om-geocoding-empty.json")).isEmpty())
    }

    @Test
    fun singaporeIsLeftOutOfSearchResults() {
        val body = """{"results":[
            {"id":1880252,"name":"Singapore","latitude":1.28,"longitude":103.85,"country_code":"SG","country":"Singapore","timezone":"Asia/Singapore"},
            {"id":1,"name":"Kuala Lumpur","latitude":3.14,"longitude":101.68,"country_code":"MY","country":"Malaysia","admin1":"Kuala Lumpur","timezone":"Asia/Kuala_Lumpur"}]}"""
        val found = PlaceParsers.geocoding(body)
        assertEquals(listOf("Kuala Lumpur"), found.map { it.name })
        // A region with the same name as the city is not repeated under it.
        assertEquals("Malaysia", found[0].subtitle)
    }

    @Test
    fun subtitleJoinsRegionAndCountry() {
        assertEquals("Region, Country", place(1).subtitle)
    }

    // ---- Forecast ----

    @Test
    fun forecastIsParsed() {
        val w = PlaceParsers.forecast(fixture("om-forecast.json"))
        assertEquals("Asia/Kuala_Lumpur", w.timezone)
        assertNotNull(w.tempC)
        assertTrue("temp ${w.tempC}", w.tempC!! in -60.0..60.0)
        assertNotNull(w.code)
        assertEquals(5, w.days.size)
        // Days come in order, one after the other, starting today at the place.
        for (i in 1 until w.days.size) assertEquals(w.days[i - 1].date.plusDays(1), w.days[i].date)
        for (d in w.days) {
            assertNotNull(d.code)
            assertTrue("low ${d.low} high ${d.high}", d.low!! <= d.high!!)
        }
    }

    @Test
    fun humidityIsReadFromTheForecast() {
        val w = PlaceParsers.forecast(fixture("om-forecast.json"))
        assertNotNull(w.humidity)
        assertTrue("humidity ${w.humidity}", w.humidity!! in 0..100)
    }

    @Test
    fun humidityIsRoundedToAWholePercent() {
        val body = """{"timezone":"UTC","current":{"temperature_2m":20.5,"relative_humidity_2m":72.4,"weather_code":0,"is_day":1}}"""
        assertEquals(72, PlaceParsers.forecast(body).humidity)
    }

    @Test
    fun missingHumidityIsNullNotZero() {
        val without = """{"timezone":"UTC","current":{"temperature_2m":20.5,"weather_code":0,"is_day":1}}"""
        val empty = """{"timezone":"UTC","current":{"temperature_2m":20.5,"relative_humidity_2m":null,"weather_code":0,"is_day":1}}"""
        assertNull(PlaceParsers.forecast(without).humidity)
        assertNull(PlaceParsers.forecast(empty).humidity)
    }

    @Test
    fun forecastRoundsDailyTemperatures() {
        val body = """{"timezone":"UTC","current":{"temperature_2m":20.5,"weather_code":0,"is_day":1},
            "daily":{"time":["2026-01-01"],"weather_code":[3],"temperature_2m_max":[24.5],"temperature_2m_min":[18.4]}}"""
        val d = PlaceParsers.forecast(body).days.single()
        assertEquals(25, d.high)
        assertEquals(18, d.low)
    }

    @Test
    fun forecastToleratesMissingValues() {
        val body = """{"timezone":"UTC","current":{"temperature_2m":null,"weather_code":null,"is_day":0},
            "daily":{"time":["2026-01-01","2026-01-02"],"weather_code":[null,3],"temperature_2m_max":[null,5.0],"temperature_2m_min":[null,1.0]}}"""
        val w = PlaceParsers.forecast(body)
        assertNull(w.tempC)
        assertNull(w.code)
        assertFalse(w.isDay)
        assertNull(w.days[0].code)
        assertNull(w.days[0].high)
        assertEquals(3, w.days[1].code)
    }

    @Test(expected = IllegalStateException::class)
    fun forecastErrorBodyIsAnError() {
        PlaceParsers.forecast("""{"error":true,"reason":"Latitude must be in range of -90 to 90"}""")
    }

    // ---- List glance ----

    @Test
    fun severalPlacesComeBackInRequestOrder() {
        val g = PlaceParsers.glance(fixture("om-glance.json"))
        assertEquals(3, g.size)
        for (x in g) {
            assertNotNull(x.tempC)
            assertNotNull(x.code)
        }
    }

    @Test
    fun aSinglePlaceAnswerIsAnObjectNotAnArray() {
        val g = PlaceParsers.glance(fixture("om-forecast.json"))
        assertEquals(1, g.size)
        assertNotNull(g[0].tempC)
    }

    // ---- Saved list ----

    @Test
    fun savedListSurvivesAWriteAndRead() {
        val list = listOf(place(1, "Tokyo"), place(2, "London"))
        assertEquals(list, PlaceList.parse(PlaceList.toJson(list)))
    }

    @Test
    fun aDamagedOrMissingSettingGivesAnEmptyList() {
        assertTrue(PlaceList.parse(null).isEmpty())
        assertTrue(PlaceList.parse("").isEmpty())
        assertTrue(PlaceList.parse("not json").isEmpty())
        assertTrue(PlaceList.parse("""[{"id":1}]""").isEmpty())
    }

    @Test
    fun addingTheSamePlaceTwiceKeepsOne() {
        val once = PlaceList.add(emptyList(), place(1))
        assertEquals(once, PlaceList.add(once, place(1)))
    }

    @Test
    fun placesKeepTheOrderTheyWereAdded() {
        var list = emptyList<Place>()
        for (i in 1L..3L) list = PlaceList.add(list, place(i))
        assertEquals(listOf(1L, 2L, 3L), list.map { it.id })
    }

    @Test
    fun theListStopsAtItsLimit() {
        var list = emptyList<Place>()
        for (i in 1L..(PlaceList.MAX + 5L)) list = PlaceList.add(list, place(i))
        assertEquals(PlaceList.MAX, list.size)
    }

    @Test
    fun removingDropsOnlyThatPlace() {
        val list = listOf(place(1), place(2), place(3))
        assertEquals(listOf(1L, 3L), PlaceList.remove(list, 2).map { it.id })
        assertEquals(list, PlaceList.remove(list, 99))
    }

    // ---- Local time ----

    @Test
    fun clockShowsTheTimeAtThePlace() {
        // 2:45 am UTC on 18 Oct 2026.
        val t = 1792291500000L
        assertEquals("2:45 am", TimeFmt.clockIn(t, "UTC"))
        assertEquals("11:45 am", TimeFmt.clockIn(t, "Asia/Tokyo"))
        assertEquals("10:45 am", TimeFmt.clockIn(t, "Asia/Singapore"))
    }

    @Test
    fun anUnknownZoneDoesNotCrash() {
        assertTrue(TimeFmt.clockIn(1792291500000L, "Nowhere/Land").isNotEmpty())
    }
}
