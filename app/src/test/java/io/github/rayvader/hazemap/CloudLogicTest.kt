package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CloudLogicTest {

    private fun utc(text: String) = Instant.parse(text).toEpochMilli()

    // ---- Which pictures ----

    @Test
    fun theTileUrlIsGibsHimawariBand13AtZoom6() {
        assertEquals(
            "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/Himawari_AHI_Band13_Clean_Infrared/default/" +
                "2026-11-22T09:00:00Z/GoogleMapsCompatible_Level6/6/31/50.png",
            CloudLogic.tileUrl(CloudFrame(utc("2026-11-22T09:00:00Z")), 31)
        )
    }

    @Test
    fun theNewestIsLookedForFromTenMinutesAgoBackInTenMinuteSteps() {
        val tries = CloudLogic.newestCandidates(utc("2026-11-22T09:26:30Z"))
        assertEquals(CloudLogic.LOOK_BACK_STEPS, tries.size)
        assertEquals(utc("2026-11-22T09:10:00Z"), tries[0].timeMillis)
        assertEquals(utc("2026-11-22T09:00:00Z"), tries[1].timeMillis)
        // Two hours back at the most.
        assertEquals(utc("2026-11-22T07:20:00Z"), tries.last().timeMillis)
    }

    @Test
    fun twoHoursOfFramesEndAtTheNewestOldestFirst() {
        val frames = CloudLogic.framesEndingAt(CloudFrame(utc("2026-11-22T09:00:00Z")))
        assertEquals(13, frames.size)
        assertEquals(utc("2026-11-22T07:00:00Z"), frames.first().timeMillis)
        assertEquals(utc("2026-11-22T09:00:00Z"), frames.last().timeMillis)
    }

    @Test
    fun theTimesFallOnTenMinuteMarks() {
        assertEquals(utc("2026-11-22T09:20:00Z"), CloudLogic.floorToStep(utc("2026-11-22T09:29:59Z")))
        assertEquals(utc("2026-11-22T09:20:00Z"), CloudLogic.floorToStep(utc("2026-11-22T09:20:00Z")))
    }

    // ---- Which picture goes with a rain frame ----

    @Test
    fun aRainFrameGetsTheNewestCloudPictureNotAfterIt() {
        val clouds = listOf("08:40", "08:50", "09:00").map { CloudFrame(utc("2026-11-22T$it:00Z")) }
        assertEquals(clouds[2], CloudLogic.matching(clouds, utc("2026-11-22T09:20:00Z")))
        assertEquals(clouds[1], CloudLogic.matching(clouds, utc("2026-11-22T08:54:00Z")))
        // Up to five minutes early still counts.
        assertEquals(clouds[2], CloudLogic.matching(clouds.reversed(), utc("2026-11-22T08:55:00Z")))
        // Rain older than every cloud picture gets the oldest.
        assertEquals(clouds[0], CloudLogic.matching(clouds, utc("2026-11-22T07:00:00Z")))
        assertNull(CloudLogic.matching(emptyList(), utc("2026-11-22T09:00:00Z")))
    }

    // ---- Where the kept part sits ----

    @Test
    fun theKeptPartCoversSingaporeAndTheMapAroundIt() {
        val c = CloudLogic.crop()
        // Inside the stacked pair of tiles: 256 wide, 512 tall.
        assertTrue(c[0] >= 0 && c[0] + c[2] <= 256)
        assertTrue(c[1] >= 0 && c[1] + c[3] <= 512)
        val e = CloudLogic.cropBounds()
        val (north, west, south, east) = e.toList()
        assertTrue("north $north", north >= 3.2 && north < 3.3)
        assertTrue("south $south", south <= -0.6 && south > -0.7)
        assertTrue("west $west", west <= 102.0 && west > 101.9)
        assertTrue("east $east", east >= 105.6 && east < 105.7)
        // Singapore, and the radar's area, are inside it.
        assertTrue(1.35 in south..north && 103.82 in west..east)
    }

    @Test
    fun pictureDotsMatchTheTileEdges() {
        assertEquals(0.0, CloudLogic.pixelX(101.25), 1e-9)
        assertEquals(256.0, CloudLogic.pixelX(106.875), 1e-9)
        // The equator is where tile 31 ends and tile 32 starts.
        assertEquals(256.0, CloudLogic.pixelY(0.0), 1e-9)
    }

    // ---- Grey to see-through cloud ----

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun clearSkyIsSeeThroughAndColdCloudIsLightGrey() {
        // Warm sea and ground, as GIBS draws them: about 100 to 110.
        for (v in 95..115) assertEquals("grey $v", 0, CloudLogic.toCloud(grey(v), dark = false))
        // Thick cold cloud: the fill colour, nearly opaque.
        val thick = CloudLogic.toCloud(grey(200), dark = false)
        assertEquals(CloudLogic.fillColour(false), thick and 0xFFFFFF)
        assertTrue((thick ushr 24) >= 220)
        // The coldest storm tops are drawn in colour by GIBS: they count as full cloud.
        val storm = CloudLogic.toCloud((0xFF shl 24) or (0x20 shl 16) or (0xD0 shl 8) or 0x40, dark = true)
        assertEquals(CloudLogic.fillColour(true), storm and 0xFFFFFF)
    }

    @Test
    fun aCloudHasADarkerEdgeSoItStandsOutOnTheLightMap() {
        // Just past clear, a dot is the edge line.
        val edge = CloudLogic.toCloud(grey(125), dark = false)
        assertEquals(CloudLogic.edgeColour(false), edge and 0xFFFFFF)
        // The edge is darker than the fill, and the fill is grey, not white, in both modes.
        for (dark in listOf(false, true)) {
            assertTrue(lum(CloudLogic.edgeColour(dark)) < lum(CloudLogic.fillColour(dark)))
            assertTrue(lum(CloudLogic.fillColour(dark)) < 240)
        }
    }

    @Test
    fun moreCloudIsNeverMoreSeeThrough() {
        for (dark in listOf(false, true)) {
            var before = -1
            for (v in 100..255) {
                val c = CloudLogic.toCloud(grey(v), dark)
                val a = c ushr 24
                // The edge band is fainter than the fill on purpose; inside the fill alpha only grows.
                if (CloudLogic.cloudiness(grey(v)) >= CloudLogic.EDGE_TO) {
                    assertTrue("grey $v: $a < $before", a >= before)
                    before = a
                }
            }
        }
    }

    @Test
    fun coverIsTheShareOfCloudyDots() {
        val dots = intArrayOf(grey(100), grey(200), grey(200), grey(100))
        CloudLogic.toClouds(dots, dark = false)
        assertEquals(0.5f, CloudLogic.cover(dots), 0.0001f)
        assertEquals(0f, CloudLogic.cover(IntArray(0)), 0f)
    }

    private fun lum(rgb: Int) = (0.299 * ((rgb shr 16) and 0xFF) + 0.587 * ((rgb shr 8) and 0xFF) + 0.114 * (rgb and 0xFF))
}
