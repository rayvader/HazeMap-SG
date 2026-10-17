package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Test

class BandTest {

    @Test
    fun psiBands() {
        assertEquals(Band.GOOD, Band.forPsi(50))
        assertEquals(Band.MODERATE, Band.forPsi(100))
        assertEquals(Band.UNHEALTHY, Band.forPsi(200))
        assertEquals(Band.VERY_UNHEALTHY, Band.forPsi(300))
        assertEquals(Band.HAZARDOUS, Band.forPsi(301))
    }

    @Test
    fun pm25Bands() {
        assertEquals(Band.GOOD, Band.forPm25(55))
        assertEquals(Band.MODERATE, Band.forPm25(150))
        assertEquals(Band.UNHEALTHY, Band.forPm25(250))
        assertEquals(Band.VERY_UNHEALTHY, Band.forPm25(350))
        assertEquals(Band.HAZARDOUS, Band.forPm25(400))
    }
}
