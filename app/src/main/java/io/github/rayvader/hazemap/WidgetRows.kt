package io.github.rayvader.hazemap

import kotlin.math.floor

/** Where the date sits in a widget's clock block. */
enum class DateSpot {
    /** On the clock's row: "14:05  Sun 15 Nov". */
    BESIDE,

    /** Under the clock, because the two do not fit on one row. */
    BELOW
}

/**
 * What the clock block and the event lines of one widget look like at its size.
 *
 * @property clockSp the clock's size: the fit's, or a little smaller so that the date fits beside it.
 * @property events how many events get a line of their own.
 * @property more how many are left over, shown on one "+N more" line; 0 for none.
 */
data class RowPlan(
    val dateSpot: DateSpot,
    val showDate: Boolean,
    val clockSp: Int,
    val events: Int,
    val more: Int
) {
    /** Event lines on the widget, the "+N more" line included. */
    val lines: Int get() = events + if (more > 0) 1 else 0
}

/**
 * The date goes on the clock's row when it fits there, and the room it frees under the clock goes to
 * event lines, as many as the widget's real height holds. Pure arithmetic, so it is tested on the JVM; the
 * measures are the ones the emulator test's layout dumps give for these layouts (dp at the usual text size).
 */
object WidgetRows {

    /** Roboto's line height for each sp of text: the clock (no font padding), the temperature, the date. */
    private const val CLOCK_LINE = 1.17f
    private const val TEMP_LINE = 1.34f
    private const val DATE_LINE = 1.37f

    /** The date's text size in each layout, and the Strip's clock (the Strip has no fit). */
    private const val WIDE_DATE_SP = 13
    private const val SMALL_DATE_SP = 11
    private const val STRIP_DATE_SP = 10
    const val STRIP_CLOCK_SP = 28

    /** The space the date takes under the clock: its top margin (the Wide 2 dp, the Square 1 dp). */
    private const val WIDE_DATE_MARGIN_DP = 2
    private const val SMALL_DATE_MARGIN_DP = 1

    /**
     * How wide "EEE d MMM" is for each sp of its size, in em, in the widest case ("Wed 30 Sep", "Mon 30 Nov").
     * Roboto draws it at about 4.8 em; this counts 8% more for a phone's own font.
     */
    private const val DATE_EM = 5.2f

    /** The space between the clock and the date on their row, in dp. */
    const val DATE_GAP_DP = 8

    /** The Strip puts the date beside the clock only this far beyond the usual Strip width: the date and a margin. */
    private const val USUAL_STRIP_DP = 362
    private const val STRIP_SPARE_DP = 24

    /** The clock may shrink to this share of its fit to make room for the date beside it, but not under 22 sp. */
    private const val SHRINK_TO_PERCENT = 85
    private const val SMALLEST_CLOCK_SP = 22

    /** An event line: the text (11 sp, with its font padding) and the 2 dp above and below it; 3 dp between lines. */
    private const val EVENT_TEXT_LINE = 15.2f
    private const val EVENT_PAD_DP = 4
    const val EVENT_GAP_DP = 3

    /** The Wide's first event line is 4 dp under the clock block, and the last needs this much before the numbers. */
    private const val WIDE_FIRST_EVENT_DP = 4
    private const val WIDE_MIN_GAP_DP = 2.5f

    /**
     * The Square's lines grow upwards from the numbers and must stay under the sky picture, whose art
     * reaches 63 dp down (the 170 dp fit's line starts at 67 dp). The numbers, place and PSI under them take
     * 42 dp, of which 30 grow with the text size; the stack has 4 dp under it.
     */
    private const val SMALL_ART_CLEAR_DP = 67
    private const val SMALL_NUMBERS_TEXT_DP = 30
    private const val SMALL_NUMBERS_FIXED_DP = 12
    private const val SMALL_STACK_BOTTOM_DP = 4

    private const val SHORTEST_DP = 110

    private fun textScale(fontScale: Float) = fontScale.coerceAtLeast(1f)

    private fun dateSp(shape: WidgetShape) = when (shape) {
        WidgetShape.WIDE -> WIDE_DATE_SP
        WidgetShape.SMALL -> SMALL_DATE_SP
        WidgetShape.STRIP -> STRIP_DATE_SP
    }

    /** How wide the date is, in dp, at this text size. */
    fun dateWidthDp(shape: WidgetShape, fontScale: Float): Float = dateSp(shape) * textScale(fontScale) * DATE_EM

    /** The room for the clock, the gap and the date on one row, in dp: the clock's own room before the picture. */
    private fun rowRoomDp(shape: WidgetShape, widthDp: Int): Int = when (shape) {
        WidgetShape.WIDE -> (if (widthDp > 0) widthDp else WidgetFits.USUAL_WIDE_DP) - WidgetFits.WIDE_CLOCK_MARGINS_DP
        else -> (if (widthDp > 0) widthDp else WidgetFits.USUAL_SMALL_DP) - WidgetFits.SMALL_CLOCK_MARGINS_DP
    }

    /** The biggest clock, in sp, that fits on one row with the date beside it (the Wide and the Square). */
    fun besideClockSp(shape: WidgetShape, widthDp: Int, fontScale: Float): Int {
        val s = textScale(fontScale)
        val room = rowRoomDp(shape, widthDp) - DATE_GAP_DP - dateWidthDp(shape, fontScale)
        return floor(room / (WidgetFits.CLOCK_DP_PER_SP * s)).toInt()
    }

    /**
     * Where the date goes, and how big the clock is then. Beside the clock when both fit on one row;
     * when only a clock up to 15% smaller would fit, that smaller clock; otherwise under it, on that size only.
     * The Strip has no fit and a fixed clock: it takes the date beside it only when it is wide enough that
     * nothing else in its row is squeezed.
     */
    fun dateSpot(shape: WidgetShape, fitClockSp: Int, widthDp: Int, fontScale: Float): Pair<DateSpot, Int> {
        if (shape == WidgetShape.STRIP) {
            val width = if (widthDp > 0) widthDp else USUAL_STRIP_DP
            val needed = USUAL_STRIP_DP + dateWidthDp(shape, fontScale) + DATE_GAP_DP + STRIP_SPARE_DP
            return (if (width >= needed) DateSpot.BESIDE else DateSpot.BELOW) to fitClockSp
        }
        val fits = besideClockSp(shape, widthDp, fontScale)
        return when {
            fitClockSp <= fits -> DateSpot.BESIDE to fitClockSp
            fits * 100 >= fitClockSp * SHRINK_TO_PERCENT && fits >= SMALLEST_CLOCK_SP -> DateSpot.BESIDE to fits
            else -> DateSpot.BELOW to fitClockSp
        }
    }

    /** One event line's height in dp at this text size. */
    fun eventLineDp(fontScale: Float): Float = EVENT_TEXT_LINE * textScale(fontScale) + EVENT_PAD_DP

    /**
     * How many event lines the widget's real height holds, with the clock at [clockSp] and the date at [spot].
     * Zero when the fit shows none; at least one when it shows one, as it always has.
     */
    fun lineCapacity(
        shape: WidgetShape,
        heightDp: Int,
        fit: WidgetFit,
        clockSp: Int,
        spot: DateSpot,
        fontScale: Float
    ): Int {
        if (!fit.showEvent || !shape.hasEventLine) return 0
        val s = textScale(fontScale)
        val h = if (heightDp > 0) heightDp else SHORTEST_DP
        val available = when (shape) {
            WidgetShape.WIDE -> {
                val dateUnder = if (spot == DateSpot.BELOW && fit.showDate) DATE_LINE * WIDE_DATE_SP * s + WIDE_DATE_MARGIN_DP else 0f
                val blockTop = fit.padTopDp + CLOCK_LINE * clockSp * s + dateUnder
                val numbersTop = h - (TEMP_LINE * fit.tempSp * s + fit.padBottomDp)
                numbersTop - WIDE_MIN_GAP_DP - blockTop - WIDE_FIRST_EVENT_DP
            }
            else -> {
                val dateUnder = if (fit.showDate) SMALL_DATE_MARGIN_DP + DATE_LINE * SMALL_DATE_SP * s else 0f
                val clockBlockBottom = fit.padTopDp + CLOCK_LINE * clockSp * s + dateUnder + 2
                val stackTop = maxOf(SMALL_ART_CLEAR_DP.toFloat(), clockBlockBottom)
                val numbers = s * (TEMP_LINE * fit.tempSp + SMALL_NUMBERS_TEXT_DP) + SMALL_NUMBERS_FIXED_DP + fit.padBottomDp
                h - stackTop - numbers - SMALL_STACK_BOTTOM_DP
            }
        }
        val lines = floor((available + EVENT_GAP_DP) / (eventLineDp(s) + EVENT_GAP_DP)).toInt()
        return maxOf(1, lines)
    }

    /**
     * The plan for one widget: where the date goes, and which events get a line. When there are more events
     * than lines, the last line says how many are left ("+N more"); one line alone shows just the next event,
     * as the widget always has, as there is no room to say more.
     *
     * [fit] is null for the Strip, which has no events and no trimming. [eventCount] is how many events the
     * calendars have in the next week.
     */
    fun plan(
        shape: WidgetShape,
        heightDp: Int,
        widthDp: Int,
        fontScale: Float,
        fit: WidgetFit?,
        eventCount: Int
    ): RowPlan {
        val (spot, clockSp) = dateSpot(shape, fit?.clockSp ?: STRIP_CLOCK_SP, widthDp, fontScale)
        val showDate = spot == DateSpot.BESIDE || fit?.showDate != false
        val capacity = if (fit == null || eventCount == 0) 0 else lineCapacity(shape, heightDp, fit, clockSp, spot, fontScale)
        return when {
            capacity == 0 -> RowPlan(spot, showDate, clockSp, 0, 0)
            eventCount <= capacity -> RowPlan(spot, showDate, clockSp, eventCount, 0)
            capacity == 1 -> RowPlan(spot, showDate, clockSp, 1, 0)
            else -> RowPlan(spot, showDate, clockSp, capacity - 1, eventCount - (capacity - 1))
        }
    }
}
