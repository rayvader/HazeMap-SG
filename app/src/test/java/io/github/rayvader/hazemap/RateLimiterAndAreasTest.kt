package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterAndAreasTest {

    @Test
    fun firstFiveCallsGoStraightAway() {
        val limiter = RateLimiter(5, 10_000)
        repeat(5) { assertEquals(0L, limiter.reserve(0)) }
    }

    @Test
    fun sixthCallWaitsForTheWindowToPass() {
        val limiter = RateLimiter(5, 10_000)
        repeat(5) { limiter.reserve(0) }
        assertEquals(10_000L, limiter.reserve(0))
    }

    @Test
    fun queuedCallsAreSpreadAcrossWindows() {
        val limiter = RateLimiter(5, 10_000)
        repeat(5) { limiter.reserve(0) }
        // Calls 6 to 10 all start at 10 s, call 11 at 20 s.
        repeat(5) { assertEquals(10_000L, limiter.reserve(0)) }
        assertEquals(20_000L, limiter.reserve(0))
    }

    @Test
    fun slotsFreeUpAsTimePasses() {
        val limiter = RateLimiter(5, 10_000)
        repeat(5) { limiter.reserve(0) }
        assertEquals(0L, limiter.reserve(15_000))
        // All five original calls are older than 10 s at 15 s: five slots are free, the one above plus four more.
        repeat(4) { assertEquals(0L, limiter.reserve(15_000)) }
    }

    @Test
    fun noWindowEverHoldsMoreThanTheLimit() {
        val limiter = RateLimiter(5, 10_000)
        val starts = (0 until 40).map { 1_000L * (it / 8) + limiter.reserve(1_000L * (it / 8)) }
        for (s in starts) {
            val inWindow = starts.count { it >= s && it < s + 10_000 }
            assertTrue("window from $s holds $inWindow calls", inWindow <= 5)
        }
    }

    @Test
    fun allFortySevenAreasAreKnownAndMatchTheNeaForecast() {
        assertEquals(47, Areas.all.size)
        val fixture = checkNotNull(javaClass.getResource("/fixtures/two-hr-forecast.json")).readText()
        val neaNames = NeaParsers.forecast2h(fixture).byArea.keys
        assertEquals(neaNames, Areas.all.map { it.name }.toSet())
    }

    @Test
    fun areaLookupIgnoresCase() {
        assertEquals("Ang Mo Kio", Areas.byName("ang mo kio")?.name)
        assertNull(Areas.byName("Narnia"))
        assertNull(Areas.byName(null))
        assertNotNull(Areas.default)
    }

    @Test
    fun nearestAreaUsesTheClosestForecastPoint() {
        assertEquals("Bedok", Areas.nearest(1.3211, 103.9239).name)
        assertEquals("Tuas", Areas.nearest(1.295, 103.636).name)
    }

    @Test
    fun areasMapToTheNearestNeaRegion() {
        fun region(name: String) = Areas.byName(name)!!.region
        assertEquals(Region.CENTRAL, region("Ang Mo Kio"))
        assertEquals(Region.CENTRAL, region("Bishan"))
        assertEquals(Region.NORTH, region("Woodlands"))
        assertEquals(Region.NORTH, region("Yishun"))
        assertEquals(Region.WEST, region("Jurong West"))
        assertEquals(Region.WEST, region("Tuas"))
        assertEquals(Region.EAST, region("Tampines"))
        assertEquals(Region.EAST, region("Changi"))
        assertEquals(Region.SOUTH, region("Bukit Merah"))
        assertEquals(Region.SOUTH, region("Sentosa"))
    }
}
