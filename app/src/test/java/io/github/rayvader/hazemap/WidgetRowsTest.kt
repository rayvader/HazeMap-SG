package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRowsTest {

    private val heights = (60..420).toList()
    private val scales = listOf(1f, 1.15f, 1.3f, 1.5f)

    private fun fit(shape: WidgetShape, h: Int, hasEvent: Boolean, scale: Float = 1f, width: Int = 0) =
        WidgetFits.of(shape, h, hasEvent, scale, width)

    private fun plan(shape: WidgetShape, h: Int, events: Int, scale: Float = 1f, width: Int = 0): RowPlan =
        WidgetRows.plan(shape, h, width, scale, fit(shape, h, events > 0, scale, width), events)

    // ---- Where the date goes ----

    @Test
    fun theWideTakesTheDateBesideTheClockWhereAPhoneGivesItItsUsualSize() {
        // A real phone gave the Wide about 362 x 139 dp: the time and the date share a row, at the fit's clock.
        val p = plan(WidgetShape.WIDE, 139, 6)
        assertEquals(DateSpot.BESIDE, p.dateSpot)
        assertEquals(42, p.clockSp)
        assertTrue(p.showDate)
        // And with text 15% bigger, with the fit's smaller clock.
        val big = plan(WidgetShape.WIDE, 139, 6, 1.15f)
        assertEquals(DateSpot.BESIDE, big.dateSpot)
        assertEquals(32, big.clockSp)
    }

    @Test
    fun aBigClockShrinksALittleSoTheDateStaysBesideIt() {
        // 60 sp is 165 dp wide: with the date it needs 246 dp and the Wide has 222. The biggest clock that
        // fits with the date is 53 sp, 88% of 60.
        assertEquals(53, WidgetRows.besideClockSp(WidgetShape.WIDE, 362, 1f))
        val tall = plan(WidgetShape.WIDE, 223, 6)
        assertEquals(DateSpot.BESIDE, tall.dateSpot)
        assertEquals(53, tall.clockSp)
    }

    @Test
    fun theDateWrapsUnderTheClockWhenEvenASmallerClockWouldNotFit() {
        // At 115% text the biggest clock beside the date is 43 sp; the 60 sp fit would have to lose more than 15%.
        assertEquals(43, WidgetRows.besideClockSp(WidgetShape.WIDE, 362, 1.15f))
        val tall = plan(WidgetShape.WIDE, 223, 6, 1.15f)
        assertEquals(DateSpot.BELOW, tall.dateSpot)
        assertEquals(60, tall.clockSp)
        assertTrue(tall.showDate)
        // A shorter Wide at the same text size has a clock that fits beside the date as it is, or nearly.
        assertEquals(DateSpot.BESIDE, plan(WidgetShape.WIDE, 190, 6, 1.15f).dateSpot)
    }

    @Test
    fun aNarrowWideWrapsTheDateAndKeepsItsClock() {
        // 250 dp wide: 110 dp for the clock and the date, so a clock of 12 sp would be all that fits beside it.
        val p = plan(WidgetShape.WIDE, 223, 6, 1f, 250)
        assertEquals(DateSpot.BELOW, p.dateSpot)
        assertEquals(fit(WidgetShape.WIDE, 223, true, 1f, 250)!!.clockSp, p.clockSp)
    }

    @Test
    fun theSquareKeepsTheDateUnderTheClockAtItsUsualWidth() {
        // 96 dp for the clock before the picture: not even a 22 sp clock fits with the date beside it.
        for (scale in scales) {
            for (h in heights) {
                assertEquals("${h}dp at $scale", DateSpot.BELOW, plan(WidgetShape.SMALL, h, 3, scale).dateSpot)
            }
        }
    }

    @Test
    fun aWiderSquareTakesTheDateBesideTheClock() {
        // An unfolded phone's two-column widget: 230 dp leaves 146 dp, and a 29 sp clock fits with the date.
        val p = plan(WidgetShape.SMALL, 223, 6, 1f, 230)
        assertEquals(DateSpot.BESIDE, p.dateSpot)
        assertEquals(29, p.clockSp)
    }

    @Test
    fun theStripKeepsTheDateUnderTheClockUntilItIsWideEnoughNotToSqueezeItsRow() {
        assertEquals(DateSpot.BELOW, WidgetRows.dateSpot(WidgetShape.STRIP, 28, 0, 1f).first)
        assertEquals(DateSpot.BELOW, WidgetRows.dateSpot(WidgetShape.STRIP, 28, 362, 1f).first)
        assertEquals(DateSpot.BELOW, WidgetRows.dateSpot(WidgetShape.STRIP, 28, 440, 1f).first)
        assertEquals(DateSpot.BESIDE, WidgetRows.dateSpot(WidgetShape.STRIP, 28, 456, 1f).first)
        assertEquals(DateSpot.BESIDE, WidgetRows.dateSpot(WidgetShape.STRIP, 28, 520, 1.15f).first)
        // Its clock never changes.
        assertEquals(28, WidgetRows.dateSpot(WidgetShape.STRIP, 28, 520, 1f).second)
    }

    @Test
    fun theDateAlwaysFitsOnTheRowWhenItIsPutThere() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (scale in scales) {
                for (width in listOf(0, 250, 290, 362, 480, 540)) {
                    for (h in heights) {
                        val f = fit(shape, h, true, scale, width)!!
                        val p = WidgetRows.plan(shape, h, width, scale, f, 3)
                        val tag = "$shape ${h}dp w=$width at $scale"
                        // The clock never grows, and never goes under 85% of its fit or under 22 sp.
                        assertTrue("$tag grew", p.clockSp <= f.clockSp)
                        if (p.dateSpot == DateSpot.BELOW) {
                            assertEquals("$tag below keeps its clock", f.clockSp, p.clockSp)
                        } else {
                            assertTrue("$tag shrank too far", p.clockSp * 100 >= f.clockSp * 85)
                            assertTrue("$tag shrank under 22", p.clockSp >= 22 || p.clockSp == f.clockSp)
                            val usual = if (shape == WidgetShape.WIDE) WidgetFits.USUAL_WIDE_DP else WidgetFits.USUAL_SMALL_DP
                            val margins = if (shape == WidgetShape.WIDE) WidgetFits.WIDE_CLOCK_MARGINS_DP else WidgetFits.SMALL_CLOCK_MARGINS_DP
                            val room = (if (width > 0) width else usual) - margins
                            val needed = p.clockSp * maxOf(scale, 1f) * WidgetFits.CLOCK_DP_PER_SP +
                                WidgetRows.DATE_GAP_DP + WidgetRows.dateWidthDp(shape, scale)
                            assertTrue("$tag needs $needed of $room", needed <= room + 0.01f)
                            assertTrue("$tag shows the date", p.showDate)
                        }
                    }
                }
            }
        }
    }

    // ---- How many event lines ----

    @Test
    fun theWideHoldsASecondLineOnceTheDateIsBesideTheClock() {
        // Before, 139 dp held the event line and no more. With the date's line gone there is room for one more.
        val f = fit(WidgetShape.WIDE, 139, true)!!
        assertEquals(1, WidgetRows.lineCapacity(WidgetShape.WIDE, 139, f, f.clockSp, DateSpot.BELOW, 1f))
        assertEquals(2, WidgetRows.lineCapacity(WidgetShape.WIDE, 139, f, f.clockSp, DateSpot.BESIDE, 1f))
        assertEquals(2, plan(WidgetShape.WIDE, 139, 6).lines)
        assertEquals(2, plan(WidgetShape.WIDE, 139, 6, 1.15f).lines)
    }

    @Test
    fun aTallerWideHoldsMore() {
        val p = plan(WidgetShape.WIDE, 223, 6)
        assertEquals(RowPlan(DateSpot.BESIDE, true, 53, events = 3, more = 3), p)
        assertEquals(4, p.lines)
        // Tall enough for every event: all six, and no "+N more".
        assertEquals(RowPlan(DateSpot.BESIDE, true, 53, events = 6, more = 0), plan(WidgetShape.WIDE, 300, 6))
    }

    @Test
    fun theSquareHoldsMoreLinesAsItGrows() {
        // At 170 dp its one line only just clears the sky picture.
        assertEquals(1, plan(WidgetShape.SMALL, 170, 6).lines)
        assertEquals(RowPlan(DateSpot.BELOW, true, 32, events = 2, more = 4), plan(WidgetShape.SMALL, 223, 6))
        assertEquals(RowPlan(DateSpot.BELOW, true, 32, events = 3, more = 0), plan(WidgetShape.SMALL, 223, 3))
        assertEquals(RowPlan(DateSpot.BELOW, true, 32, events = 6, more = 0), plan(WidgetShape.SMALL, 300, 6))
        assertEquals(RowPlan(DateSpot.BELOW, true, 32, events = 5, more = 5), plan(WidgetShape.SMALL, 300, 10))
    }

    @Test
    fun leftOverEventsAreCountedOnTheLastLine() {
        val p = plan(WidgetShape.WIDE, 139, 6)
        assertEquals(1, p.events)
        assertEquals(5, p.more)
        assertEquals(2, p.lines)
        // Everything fits: no "+N more".
        val all = plan(WidgetShape.WIDE, 139, 2)
        assertEquals(2, all.events)
        assertEquals(0, all.more)
    }

    @Test
    fun oneLineAloneShowsJustTheNextEventAsItAlwaysDid() {
        // The Square at 170 dp has room for one line: the next event, with no room to say there are more.
        val p = plan(WidgetShape.SMALL, 170, 6)
        assertEquals(RowPlan(DateSpot.BELOW, true, 26, events = 1, more = 0), p)
    }

    @Test
    fun noEventsNoLines() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (h in heights) assertEquals("$shape ${h}dp", 0, plan(shape, h, 0).lines)
        }
        assertEquals(0, WidgetRows.plan(WidgetShape.STRIP, 70, 362, 1f, null, 5).lines)
    }

    @Test
    fun aLineThatShowedBeforeStillShowsAndNeverMoreThanTheFitAllows() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (scale in scales) {
                for (n in listOf(1, 2, 3, 10)) {
                    for (h in heights) {
                        val f = fit(shape, h, true, scale)!!
                        val p = WidgetRows.plan(shape, h, 0, scale, f, n)
                        val tag = "$shape ${h}dp at $scale with $n"
                        if (!f.showEvent) {
                            assertEquals("$tag: the fit has no room for a line", 0, p.lines)
                        } else {
                            assertTrue("$tag: the line that showed before is gone", p.lines >= 1 && p.events >= 1)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun noEventIsLostWithoutBeingCounted() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (scale in scales) {
                for (n in 1..12) {
                    for (h in heights) {
                        val p = plan(shape, h, n, scale)
                        if (p.lines < 2) continue
                        val tag = "$shape ${h}dp at $scale with $n"
                        // With room for two lines or more, every event is on a line or counted in the "+N more".
                        assertEquals(tag, n, p.events + p.more)
                        assertTrue("$tag: a +N more of one", p.more != 1)
                    }
                }
            }
        }
    }

    @Test
    fun theDateBesideTheClockNeverHoldsFewerLinesThanUnderIt() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (scale in scales) {
                for (h in heights) {
                    val f = fit(shape, h, true, scale)!!
                    val under = WidgetRows.lineCapacity(shape, h, f, f.clockSp, DateSpot.BELOW, scale)
                    val beside = WidgetRows.lineCapacity(shape, h, f, f.clockSp, DateSpot.BESIDE, scale)
                    assertTrue("$shape ${h}dp at $scale: $beside < $under", beside >= under)
                }
            }
        }
    }

    @Test
    fun moreHeightNeverHoldsFewerLinesAtTheSameClock() {
        for (shape in listOf(WidgetShape.WIDE, WidgetShape.SMALL)) {
            for (spot in DateSpot.entries) {
                for (scale in scales) {
                    // The fit's clock and numbers step down as the height does; the capacity at one fit only grows.
                    for (h in heights.drop(1)) {
                        val shorter = fit(shape, h - 1, true, scale)!!
                        val taller = fit(shape, h, true, scale)!!
                        if (shorter.copy(showEvent = true) != taller.copy(showEvent = true)) continue
                        val a = WidgetRows.lineCapacity(shape, h - 1, shorter, shorter.clockSp, spot, scale)
                        val b = WidgetRows.lineCapacity(shape, h, taller, taller.clockSp, spot, scale)
                        if (!shorter.showEvent || !taller.showEvent) continue
                        assertTrue("$shape $spot ${h}dp at $scale: $b < $a", b >= a)
                    }
                }
            }
        }
    }

    @Test
    fun theLineSizeFollowsTheTextSize() {
        assertEquals(19.2f, WidgetRows.eventLineDp(1f), 0.01f)
        assertTrue(WidgetRows.eventLineDp(1.3f) > WidgetRows.eventLineDp(1f))
        // Smaller text gains nothing.
        assertEquals(WidgetRows.eventLineDp(1f), WidgetRows.eventLineDp(0.85f), 0.001f)
    }

    @Test
    fun theDateHidesOnlyWhereItIsNotBesideTheClockAndTheFitHasNoRoom() {
        // The Wide at 110 dp with 150% text drops the date under the clock (see WidgetFits); beside the clock it costs
        // no height, so it stays.
        val f = fit(WidgetShape.WIDE, 110, false, 1.5f)!!
        assertFalse(f.showDate)
        val p = WidgetRows.plan(WidgetShape.WIDE, 110, 0, 1.5f, f, 0)
        assertEquals(DateSpot.BESIDE, p.dateSpot)
        assertTrue(p.showDate)
    }
}
