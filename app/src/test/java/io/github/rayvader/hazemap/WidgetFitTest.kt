package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFitTest {

    private val heights = (60..260).toList()

    private fun fits(shape: WidgetShape, hasEvent: Boolean, h: Int) = WidgetFits.of(shape, h, hasEvent)!!

    @Test
    fun anUnknownHeightIsTreatedAsTheShortestWidget() {
        // Text that fits the shortest widget fits any, while a guess of plenty of room would overlap on a short one.
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (hasEvent in listOf(true, false)) {
                assertEquals("$shape $hasEvent", fits(shape, hasEvent, 110), fits(shape, hasEvent, 0))
            }
        }
    }

    @Test
    fun theStripIsNeverTrimmed() {
        assertNull(WidgetFits.of(WidgetShape.STRIP, 40, true))
    }

    @Test
    fun theHeightFollowsHowThePhoneIsHeld() {
        // The home screen reports the upright height (max) and the sideways one (min).
        assertEquals(223, WidgetFits.heightFor(uprightDp = 223, sidewaysDp = 120, onItsSide = false))
        assertEquals(120, WidgetFits.heightFor(uprightDp = 223, sidewaysDp = 120, onItsSide = true))
    }

    @Test
    fun aHeightTheHomeScreenDidNotGiveFallsBackToTheOtherOne() {
        assertEquals(139, WidgetFits.heightFor(uprightDp = 139, sidewaysDp = 0, onItsSide = true))
        assertEquals(139, WidgetFits.heightFor(uprightDp = 0, sidewaysDp = 139, onItsSide = false))
        assertEquals(0, WidgetFits.heightFor(uprightDp = 0, sidewaysDp = 0, onItsSide = false))
    }

    @Test
    fun aRoomyWidgetKeepsEverythingAtFullSize() {
        val wide = fits(WidgetShape.WIDE, true, 223)
        assertEquals(WidgetFit(44, 26, 12, 12, showDate = true, showEvent = true), wide)
        val small = fits(WidgetShape.SMALL, true, 223)
        assertEquals(WidgetFit(32, 24, 12, 12, showDate = true, showEvent = true), small)
    }

    @Test
    fun partsShrinkAsTheWidgetGetsShorter() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (hasEvent in listOf(true, false)) {
                for (h in heights.drop(1)) {
                    val taller = fits(shape, hasEvent, h)
                    val shorter = fits(shape, hasEvent, h - 1)
                    // Dropping the event line frees room, so the rest may grow back at that step.
                    if (taller.showEvent != shorter.showEvent) continue
                    val tag = "$shape event=$hasEvent from ${h}dp to ${h - 1}dp"
                    assertTrue("$tag clock", shorter.clockSp <= taller.clockSp)
                    assertTrue("$tag temp", shorter.tempSp <= taller.tempSp)
                    assertTrue("$tag top", shorter.padTopDp <= taller.padTopDp)
                    assertTrue("$tag bottom", shorter.padBottomDp <= taller.padBottomDp)
                }
            }
        }
    }

    @Test
    fun aShortWideKeepsTheDateUnderTheClockAndLosesTheEventLineFirst() {
        // Under 138 dp there is no room for the event line as well as the date, and the date is what stays.
        val squeezed = fits(WidgetShape.WIDE, true, 137)
        assertTrue(squeezed.showDate)
        assertFalse(squeezed.showEvent)
        // The same height with no event looks the same.
        assertEquals(squeezed, fits(WidgetShape.WIDE, false, 137))
    }

    @Test
    fun theWideShowsTheDateAtEveryHeightAHomeScreenOffers() {
        // 110 dp is the least a home screen gives it (minHeight in res/xml/widget_wide_info.xml).
        for (hasEvent in listOf(true, false)) {
            for (h in heights.filter { it >= 110 }) {
                assertTrue("event=$hasEvent at ${h}dp", fits(WidgetShape.WIDE, hasEvent, h).showDate)
            }
        }
    }

    @Test
    fun theWideDropsTheDateOnlyWhenBigTextLeavesNoRoomForItAtTheShortestHeight() {
        assertTrue(WidgetFits.of(WidgetShape.WIDE, 110, false, 1f)!!.showDate)
        // 110 dp of 15% bigger text counts as 95 dp, where even the smallest clock and numbers need the room.
        assertFalse(WidgetFits.of(WidgetShape.WIDE, 110, false, 1.15f)!!.showDate)
        // Taller, the same bigger text keeps it.
        assertTrue(WidgetFits.of(WidgetShape.WIDE, 140, false, 1.15f)!!.showDate)
    }

    @Test
    fun theSquareDropsTheDateOnlyBelowTheHeightWhereItsSmallestClockLeavesRoomForIt() {
        for (hasEvent in listOf(true, false)) {
            for (h in heights.filter { it >= 110 }) {
                assertEquals("event=$hasEvent at ${h}dp", h >= 122, fits(WidgetShape.SMALL, hasEvent, h).showDate)
            }
        }
    }

    @Test
    fun theEventLineGoesOnlyWhenThereIsNoRoomForIt() {
        assertTrue(fits(WidgetShape.WIDE, true, 138).showEvent)
        assertFalse(fits(WidgetShape.WIDE, true, 137).showEvent)
        // The Square's line would cover its sky picture on anything shorter, so it needs more height than the text does.
        assertTrue(fits(WidgetShape.SMALL, true, 170).showEvent)
        assertFalse(fits(WidgetShape.SMALL, true, 169).showEvent)
    }

    @Test
    fun aPhoneThatGivesAWideAboutTheHeightOfTheScreenshotShowsTheDateAndTheEvent() {
        // A real phone gave the Wide 139 dp; the event line overlapped the temperature there before the fit,
        // and the date had to move beside the clock to make room. Now both fit, the date under the clock.
        val wide = fits(WidgetShape.WIDE, true, 139)
        assertTrue(wide.showEvent)
        assertTrue(wide.showDate)
        assertEquals(30, wide.clockSp)
    }

    @Test
    fun withNoEventTheEventLineIsNeverAsked() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (h in heights) assertFalse("$shape at ${h}dp", fits(shape, false, h).showEvent)
        }
    }

    @Test
    fun biggerTextCountsAsLessHeight() {
        // 145 dp holds the Wide's event line at the normal text size, but not at 15% bigger text.
        assertTrue(WidgetFits.of(WidgetShape.WIDE, 145, true, 1f)!!.showEvent)
        assertFalse(WidgetFits.of(WidgetShape.WIDE, 145, true, 1.15f)!!.showEvent)
        // The same widget with more height still holds it.
        assertTrue(WidgetFits.of(WidgetShape.WIDE, 170, true, 1.15f)!!.showEvent)
    }

    @Test
    fun smallerTextGainsNothing() {
        assertEquals(
            WidgetFits.of(WidgetShape.WIDE, 127, true, 1f),
            WidgetFits.of(WidgetShape.WIDE, 127, true, 0.85f)
        )
    }

    @Test
    fun theSizesStayReadable() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (hasEvent in listOf(true, false)) {
                for (h in heights) {
                    val f = fits(shape, hasEvent, h)
                    assertTrue("$shape ${h}dp clock", f.clockSp >= 22)
                    assertTrue("$shape ${h}dp temp", f.tempSp >= 20)
                }
            }
        }
    }
}
