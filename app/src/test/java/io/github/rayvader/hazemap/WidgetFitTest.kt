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
        assertEquals(WidgetFit(44, 26, 12, 12, DateSpot.BELOW, showEvent = true), wide)
        val small = fits(WidgetShape.SMALL, true, 223)
        assertEquals(WidgetFit(32, 24, 12, 12, DateSpot.BELOW, showEvent = true), small)
    }

    @Test
    fun partsShrinkAsTheWidgetGetsShorter() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (hasEvent in listOf(true, false)) {
                for (h in heights.drop(1)) {
                    val taller = fits(shape, hasEvent, h)
                    val shorter = fits(shape, hasEvent, h - 1)
                    // Moving the date or dropping the event line frees room, so the rest may grow back at that step.
                    if (taller.date != shorter.date || taller.showEvent != shorter.showEvent) continue
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
    fun aShortWideMovesTheDateBesideTheClockToKeepTheEventLine() {
        // At this height there is no room for the date under the clock as well as the event line.
        val squeezed = fits(WidgetShape.WIDE, true, 139)
        assertEquals(DateSpot.BESIDE, squeezed.date)
        assertTrue(squeezed.showEvent)
        // The same height with no event keeps the date under the clock.
        assertEquals(DateSpot.BELOW, fits(WidgetShape.WIDE, false, 139).date)
    }

    @Test
    fun theWideAlwaysShowsTheDate() {
        for (hasEvent in listOf(true, false)) {
            for (h in heights) assertTrue("event=$hasEvent at ${h}dp", fits(WidgetShape.WIDE, hasEvent, h).date != DateSpot.HIDDEN)
        }
    }

    @Test
    fun onlyTheWideHasRoomBesideItsClock() {
        // The Square's clock shares the top with the picture, so a short Square drops the date instead.
        for (hasEvent in listOf(true, false)) {
            for (h in heights) assertTrue("event=$hasEvent at ${h}dp", fits(WidgetShape.SMALL, hasEvent, h).date != DateSpot.BESIDE)
        }
    }

    @Test
    fun theEventLineGoesOnlyWhenThereIsNoRoomForIt() {
        assertTrue(fits(WidgetShape.WIDE, true, 119).showEvent)
        assertFalse(fits(WidgetShape.WIDE, true, 118).showEvent)
        // The Square's line would cover its sky picture on anything shorter, so it needs more height than the text does.
        assertTrue(fits(WidgetShape.SMALL, true, 168).showEvent)
        assertFalse(fits(WidgetShape.SMALL, true, 167).showEvent)
    }

    @Test
    fun aPhoneThatGivesAWideAboutTheHeightOfTheScreenshotShowsTheEvent() {
        // A real phone gave the Wide 139 dp; the event line overlapped the temperature there before the fit.
        val wide = fits(WidgetShape.WIDE, true, 139)
        assertTrue(wide.showEvent)
        assertEquals(34, wide.clockSp)
        assertEquals(DateSpot.BESIDE, wide.date)
    }

    @Test
    fun withNoEventTheEventLineIsNeverAsked() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (h in heights) assertFalse("$shape at ${h}dp", fits(shape, false, h).showEvent)
        }
    }

    @Test
    fun biggerTextCountsAsLessHeight() {
        // 130 dp holds the Wide's event line at the normal text size, but not at 15% bigger text.
        assertTrue(WidgetFits.of(WidgetShape.WIDE, 130, true, 1f)!!.showEvent)
        assertFalse(WidgetFits.of(WidgetShape.WIDE, 130, true, 1.15f)!!.showEvent)
        // The same widget with more height still holds it.
        assertTrue(WidgetFits.of(WidgetShape.WIDE, 160, true, 1.15f)!!.showEvent)
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
