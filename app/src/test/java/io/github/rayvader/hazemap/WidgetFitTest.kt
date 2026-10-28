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
    fun anUnknownHeightIsTreatedAsPlentyOfRoom() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (hasEvent in listOf(true, false)) {
                assertEquals("$shape $hasEvent", fits(shape, hasEvent, 400), fits(shape, hasEvent, 0))
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
    fun biggerTextCountsAsLessHeight() {
        // 139 dp holds the Wide's event line at the normal text size, but not at 15% bigger text.
        assertTrue(WidgetFits.of(WidgetShape.WIDE, 139, true, 1f)!!.showEvent)
        assertFalse(WidgetFits.of(WidgetShape.WIDE, 139, true, 1.15f)!!.showEvent)
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
                var last: WidgetFit? = null
                for (h in heights.reversed()) {
                    val now = fits(shape, hasEvent, h)
                    val before = last
                    if (before != null) {
                        val tag = "$shape event=$hasEvent at ${h}dp"
                        assertTrue("$tag clock", now.clockSp <= before.clockSp)
                        assertTrue("$tag temp", now.tempSp <= before.tempSp)
                        assertTrue("$tag top", now.padTopDp <= before.padTopDp)
                        assertTrue("$tag bottom", now.padBottomDp <= before.padBottomDp)
                    }
                    last = now
                }
            }
        }
    }

    @Test
    fun theDateGoesBeforeTheEventLine() {
        // Between these heights the Wide has room for the event but not for the date as well.
        val squeezed = fits(WidgetShape.WIDE, true, 139)
        assertFalse(squeezed.showDate)
        assertTrue(squeezed.showEvent)
        // The same height with no event keeps the date.
        assertTrue(fits(WidgetShape.WIDE, false, 139).showDate)
    }

    @Test
    fun theEventLineGoesOnlyWhenThereIsNoRoomForIt() {
        assertTrue(fits(WidgetShape.WIDE, true, 128).showEvent)
        assertFalse(fits(WidgetShape.WIDE, true, 127).showEvent)
        assertTrue(fits(WidgetShape.SMALL, true, 144).showEvent)
        assertFalse(fits(WidgetShape.SMALL, true, 143).showEvent)
    }

    @Test
    fun withNoEventTheEventLineIsNeverAsked() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (h in heights) assertFalse("$shape at ${h}dp", fits(shape, false, h).showEvent)
        }
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
