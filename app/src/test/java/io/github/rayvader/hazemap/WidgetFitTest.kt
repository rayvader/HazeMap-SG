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
        assertEquals(WidgetFit(60, 26, 12, 12, showDate = true, showEvent = true), wide)
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
    fun aVeryShortWideKeepsTheDateUnderTheClockAndLosesTheEventLineFirst() {
        // Under 108 dp there is no room for the event line as well as the date, and the date is what stays.
        val squeezed = fits(WidgetShape.WIDE, true, 107)
        assertTrue(squeezed.showDate)
        assertFalse(squeezed.showEvent)
        // The same height with no event looks the same.
        assertEquals(squeezed, fits(WidgetShape.WIDE, false, 107))
    }

    @Test
    fun theWideClockIsTheSameSizeWithOrWithoutAnEventLine() {
        // The clock must not jump when the event line comes or goes; only the line itself does.
        for (h in heights.filter { it >= 108 }) {
            assertEquals("at ${h}dp", fits(WidgetShape.WIDE, true, h), fits(WidgetShape.WIDE, false, h).copy(showEvent = true))
        }
    }

    @Test
    fun theWideKeepsTheEventLineAtEveryHeightAHomeScreenOffers() {
        // The temperature, weather and place share one line, so even the shortest Wide has room for the event line.
        for (h in heights.filter { it >= 110 }) assertTrue("at ${h}dp", fits(WidgetShape.WIDE, true, h).showEvent)
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
        assertTrue(WidgetFits.of(WidgetShape.WIDE, 110, false, 1.15f)!!.showDate)
        // 110 dp of 50% bigger text counts as 76 dp, where even the smallest clock and numbers need the room.
        assertFalse(WidgetFits.of(WidgetShape.WIDE, 110, false, 1.5f)!!.showDate)
        // Taller, the same bigger text keeps it.
        assertTrue(WidgetFits.of(WidgetShape.WIDE, 140, false, 1.5f)!!.showDate)
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
        assertTrue(fits(WidgetShape.WIDE, true, 108).showEvent)
        assertFalse(fits(WidgetShape.WIDE, true, 107).showEvent)
        // The Square's line would cover its sky picture on anything shorter, so it needs more height than the text does.
        assertTrue(fits(WidgetShape.SMALL, true, 170).showEvent)
        assertFalse(fits(WidgetShape.SMALL, true, 169).showEvent)
    }

    @Test
    fun aPhoneThatGivesAWideAboutTheHeightOfTheScreenshotShowsTheDateAndTheEvent() {
        // A real phone gave the Wide 139 dp; the event line overlapped the temperature there before the fit,
        // and the date had to move beside the clock to make room. Now both fit, the date under the clock,
        // and the clock is 42 sp (it was 30) now that the place shares the temperature's line.
        val wide = fits(WidgetShape.WIDE, true, 139)
        assertTrue(wide.showEvent)
        assertTrue(wide.showDate)
        assertEquals(42, wide.clockSp)
        // With 15% bigger text it keeps both as well, with a smaller clock.
        val bigText = WidgetFits.of(WidgetShape.WIDE, 139, true, 1.15f)!!
        assertTrue(bigText.showEvent)
        assertTrue(bigText.showDate)
        assertEquals(32, bigText.clockSp)
    }

    @Test
    fun onlyTheTextGrowsWithTheTextSize() {
        // 15% bigger text on a 148 dp Wide: the 8 dp that do not grow stay 8 dp, so it counts as 129 dp, not 128.
        assertEquals(
            WidgetFits.of(WidgetShape.WIDE, 129, true, 1f),
            WidgetFits.of(WidgetShape.WIDE, 148, true, 1.15f)
        )
    }

    @Test
    fun aBigClockGivesWayToBigText() {
        // The bold clock is counted as 2.75 dp wide for each sp (Roboto Bold's 2.58, and 6% for a phone's own font)
        // and the Wide has 222 dp before its picture: with text 50% bigger a 60 sp clock would not fit on one line,
        // so it shrinks to what does. Only a very tall widget gets that far.
        for (scale in listOf(1f, 1.15f, 1.3f, 1.5f, 2f)) {
            for (h in (60..420)) {
                val clock = WidgetFits.of(WidgetShape.WIDE, h, true, scale)!!.clockSp
                assertTrue("${h}dp at $scale: $clock sp", clock * scale * 2.75f <= 222f)
            }
        }
        assertEquals(60, WidgetFits.of(WidgetShape.WIDE, 300, true, 1.3f)!!.clockSp)
        assertEquals(53, WidgetFits.of(WidgetShape.WIDE, 300, true, 1.5f)!!.clockSp)
        assertEquals(40, WidgetFits.of(WidgetShape.WIDE, 420, true, 2f)!!.clockSp)
    }

    @Test
    fun aNarrowWideGetsASmallerClock() {
        // Its text has 140 dp less than the widget's width before the picture: 150 dp at 290 wide fits 54 sp, not 60.
        assertEquals(60, WidgetFits.of(WidgetShape.WIDE, 300, true, 1f, 362)!!.clockSp)
        assertEquals(54, WidgetFits.of(WidgetShape.WIDE, 300, true, 1f, 290)!!.clockSp)
        assertEquals(40, WidgetFits.of(WidgetShape.WIDE, 300, true, 1f, 250)!!.clockSp)
        // A width the home screen did not give counts as the usual four-column one.
        assertEquals(WidgetFits.of(WidgetShape.WIDE, 300, true, 1f, 362), WidgetFits.of(WidgetShape.WIDE, 300, true, 1f, 0))
        // The shorter fits already hold a smaller clock, so a narrow widget changes nothing there.
        assertEquals(WidgetFits.of(WidgetShape.WIDE, 139, true, 1f, 362), WidgetFits.of(WidgetShape.WIDE, 139, true, 1f, 290))
    }

    @Test
    fun theSquareClockStaysClearOfItsPicture() {
        // 180 dp wide, 84 of them taken by the padding and the picture: 96 dp. A 32 sp bold clock fits at the
        // usual text size, and gives way to bigger text. A Square must be tall for the 32 sp fit at 130% text,
        // as the text counts as 30% more height: 300 dp is.
        assertEquals(32, WidgetFits.of(WidgetShape.SMALL, 223, true, 1f)!!.clockSp)
        assertEquals(30, WidgetFits.of(WidgetShape.SMALL, 223, true, 1.15f)!!.clockSp)
        assertEquals(26, WidgetFits.of(WidgetShape.SMALL, 300, true, 1.3f)!!.clockSp)
        for (scale in listOf(1f, 1.15f, 1.3f, 1.5f, 2f)) {
            for (h in heights) {
                val clock = WidgetFits.of(WidgetShape.SMALL, h, true, scale)!!.clockSp
                assertTrue("${h}dp at $scale: $clock sp", clock * scale * 2.75f <= 96f)
            }
        }
    }

    @Test
    fun withNoEventTheEventLineIsNeverAsked() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (h in heights) assertFalse("$shape at ${h}dp", fits(shape, false, h).showEvent)
        }
    }

    @Test
    fun biggerTextCountsAsLessHeight() {
        // 115 dp holds the Wide's event line at the normal text size, but not at 15% bigger text.
        assertTrue(WidgetFits.of(WidgetShape.WIDE, 115, true, 1f)!!.showEvent)
        assertFalse(WidgetFits.of(WidgetShape.WIDE, 115, true, 1.15f)!!.showEvent)
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
                for (scale in listOf(1f, 2f)) {
                    for (h in heights) {
                        val f = WidgetFits.of(shape, h, hasEvent, scale)!!
                        // The size the person sees: big text makes the same sp bigger, and the width cap may take some off.
                        assertTrue("$shape ${h}dp at $scale clock", f.clockSp * scale >= 22f)
                        assertTrue("$shape ${h}dp at $scale temp", f.tempSp >= 20)
                    }
                }
            }
        }
    }
}
