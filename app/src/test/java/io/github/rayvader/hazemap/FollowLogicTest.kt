package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowLogicTest {
    private val now = 1_800_000_000_000L
    private val bedok = Areas.byName("Bedok")!!
    private val tampines = Areas.byName("Tampines")!!

    private fun fix(area: Area, ageMs: Long = 0L) = Fix(area.lat, area.lon, now - ageMs)

    // ---- State ----

    @Test
    fun theSwitchAndThePermissionsSayWhoFollows() {
        assertEquals(FollowState.OFF, FollowLogic.state(follow = false, coarse = true, background = true, sdk = 34))
        assertEquals(FollowState.NO_PERMISSION, FollowLogic.state(true, coarse = false, background = false, sdk = 34))
        assertEquals(FollowState.APP_ONLY, FollowLogic.state(true, coarse = true, background = false, sdk = 34))
        assertEquals(FollowState.ALL, FollowLogic.state(true, coarse = true, background = true, sdk = 34))
    }

    @Test
    fun beforeAndroid10TheWidgetsNeedNoMorePermission() {
        assertEquals(FollowState.ALL, FollowLogic.state(true, coarse = true, background = false, sdk = 28))
        assertEquals(FollowState.APP_ONLY, FollowLogic.state(true, coarse = true, background = false, sdk = 29))
    }

    // ---- Steps ----

    @Test
    fun theFirstReadingMovesToTheNearestArea() {
        assertEquals(FollowLogic.Step.Use(fix(tampines), tampines), FollowLogic.step(bedok, null, fix(tampines), now))
    }

    @Test
    fun aReadingInTheSameAreaIsRememberedButChangesNothing() {
        val step = FollowLogic.step(bedok, null, fix(bedok), now) as FollowLogic.Step.Use
        assertNull(step.area)
    }

    @Test
    fun aSmallMoveIsIgnoredSoTheAreaDoesNotFlip() {
        // About 500 m east of the last reading: the nearest area may differ, but nothing changes.
        val last = Fix(1.333, 103.934, now - 60_000)
        val near = Fix(1.333, 103.9385, now)
        assertTrue(FollowLogic.distanceMeters(last.lat, last.lon, near.lat, near.lon) < FollowLogic.MOVE_METERS)
        assertEquals(FollowLogic.Step.Keep, FollowLogic.step(bedok, last, near, now))
    }

    @Test
    fun aRealMoveChangesTheArea() {
        val step = FollowLogic.step(bedok, fix(bedok, 60_000), fix(tampines), now)
        assertEquals(FollowLogic.Step.Use(fix(tampines), tampines), step)
    }

    @Test
    fun noReadingKeepsTheLastArea() {
        assertEquals(FollowLogic.Step.Keep, FollowLogic.step(bedok, null, null, now))
    }

    @Test
    fun anOldReadingKeepsTheLastArea() {
        assertEquals(FollowLogic.Step.Keep, FollowLogic.step(bedok, null, fix(tampines, FollowLogic.STALE_MS + 1), now))
        assertTrue(FollowLogic.step(bedok, null, fix(tampines, FollowLogic.STALE_MS - 1), now) is FollowLogic.Step.Use)
    }

    @Test
    fun abroadKeepsTheLastArea() {
        // Kuala Lumpur and Johor Bahru town are not in Singapore; Pulau Tekong is.
        assertEquals(FollowLogic.Step.Keep, FollowLogic.step(bedok, null, Fix(3.139, 101.687, now), now))
        assertEquals(FollowLogic.Step.Keep, FollowLogic.step(bedok, null, Fix(1.52, 103.76, now), now))
        assertTrue(FollowLogic.inSingapore(1.403, 104.053))
    }

    @Test
    fun everyAreaIsInsideSingapore() {
        for (a in Areas.all) assertTrue(a.name, FollowLogic.inSingapore(a.lat, a.lon))
    }

    // ---- Distance, freshness, saving ----

    @Test
    fun distanceIsInMetres() {
        // 0.01 degree of latitude is about 1.11 km.
        assertEquals(1112.0, FollowLogic.distanceMeters(1.30, 103.8, 1.31, 103.8), 5.0)
        assertEquals(0.0, FollowLogic.distanceMeters(1.3, 103.8, 1.3, 103.8), 0.001)
    }

    @Test
    fun anOldOrMissingReadingAsksForANewOne() {
        assertTrue(FollowLogic.wantsNewReading(null, now))
        assertTrue(FollowLogic.wantsNewReading(fix(bedok, FollowLogic.FRESH_MS + 1), now))
        assertFalse(FollowLogic.wantsNewReading(fix(bedok, 60_000), now))
    }

    @Test
    fun theSavedReadingIsRoundedAndReadBack() {
        val text = FollowLogic.encode(Fix(1.3214567, 103.9241234, 42L))
        assertEquals("1.321,103.924,42", text)
        assertEquals(Fix(1.321, 103.924, 42L), FollowLogic.decode(text))
        assertNull(FollowLogic.decode(null))
        assertNull(FollowLogic.decode("1.3,103.9"))
        assertNull(FollowLogic.decode("a,b,c"))
    }
}
