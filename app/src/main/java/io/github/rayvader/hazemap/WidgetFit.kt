package io.github.rayvader.hazemap

/**
 * How a widget packs its text for the height the home screen gave it. The date stays under the clock.
 * A short widget has no room for everything, so the event line goes first, and the date only when even
 * the smallest clock and numbers leave no room for it (a short Square, or the shortest Wide with big
 * text). The clock and the numbers shrink as the room does, and the space at the top and bottom is
 * trimmed.
 *
 * Heights are in dp. Zero means the home screen has not said yet, which counts as the shortest a widget
 * can be: text that fits there fits anywhere, while a guess of plenty of room would overlap on a short one.
 */
data class WidgetFit(
    val clockSp: Int,
    val tempSp: Int,
    val padTopDp: Int,
    val padBottomDp: Int,
    val showDate: Boolean,
    val showEvent: Boolean
)

object WidgetFits {

    /** The least height a home screen gives a widget: the `minHeight` in res/xml/widget_*_info.xml. */
    private const val SHORTEST_DP = 110

    /**
     * The room in a widget that does not grow with the text size, at its least: the top and bottom
     * padding of the Wide's smallest fit (4 + 4 dp). Every other fit has at least this much (its padding,
     * the margins, the event line's own padding), so counting only this much stays on the safe side.
     */
    private const val FIXED_DP = 8

    /**
     * The most a Wide's clock may grow, as its size in sp times the text size setting. The clock is
     * about 2.4 dp wide for each sp and has 222 dp before the picture, so 88 (214 dp) is the most that
     * stays on one line. Bigger text makes the same sp wider, so a large clock gives way to it.
     */
    private const val WIDE_CLOCK_LIMIT_EM = 88

    /**
     * The height the home screen gives a widget right now, in dp. It reports two heights: the one with
     * the phone upright and the one with the phone on its side. Zero means it did not say.
     */
    fun heightFor(uprightDp: Int, sidewaysDp: Int, onItsSide: Boolean): Int {
        val now = if (onItsSide) sidewaysDp else uprightDp
        val other = if (onItsSide) uprightDp else sidewaysDp
        return if (now > 0) now else other
    }

    /**
     * The fit for a widget shape, or null for one that is always short and never trimmed (the Strip).
     *
     * [fontScale] is the phone's text size setting. Bigger text needs more height, so the widget counts
     * as shorter: the text takes that much more room, while the space around it is in dp and stays the
     * same ([FIXED_DP]). A smaller setting gains nothing, to stay on the safe side.
     */
    fun of(shape: WidgetShape, heightDp: Int, hasEvent: Boolean, fontScale: Float = 1f): WidgetFit? {
        val known = if (heightDp > 0) heightDp else SHORTEST_DP
        val scale = fontScale.coerceAtLeast(1f)
        val h = (FIXED_DP + (known - FIXED_DP) / scale).toInt()
        return when (shape) {
            WidgetShape.WIDE -> wide(h, hasEvent).let { it.copy(clockSp = minOf(it.clockSp, (WIDE_CLOCK_LIMIT_EM / scale).toInt())) }
            WidgetShape.SMALL -> small(h, hasEvent)
            WidgetShape.STRIP -> null
        }
    }

    /**
     * The Wide widget. Each height is what the parts take in the emulator test's drawings of it
     * (the `fit-*` screenshots), plus a few dp to spare. Its temperature, weather and place share one
     * line, so the clock gets the rest: as big as the height allows, with the date under it and, down
     * to 108 dp, the event line. The clock is the same size with or without an event line, so it does not
     * jump when one comes or goes. Below 108 dp the event line goes (only a larger text size gets there,
     * as 110 dp is the least a home screen gives), and the date after it.
     */
    private fun wide(h: Int, hasEvent: Boolean): WidgetFit = when {
        h >= 176 -> WidgetFit(60, 26, 12, 12, showDate = true, showEvent = hasEvent)
        h >= 169 -> WidgetFit(54, 26, 12, 12, showDate = true, showEvent = hasEvent)
        h >= 154 -> WidgetFit(50, 24, 8, 8, showDate = true, showEvent = hasEvent)
        h >= 149 -> WidgetFit(46, 24, 8, 8, showDate = true, showEvent = hasEvent)
        h >= 138 -> WidgetFit(42, 22, 6, 6, showDate = true, showEvent = hasEvent)
        h >= 129 -> WidgetFit(34, 22, 6, 6, showDate = true, showEvent = hasEvent)
        h >= 120 -> WidgetFit(32, 20, 4, 4, showDate = true, showEvent = hasEvent)
        h >= 115 -> WidgetFit(28, 20, 4, 4, showDate = true, showEvent = hasEvent)
        h >= 108 -> WidgetFit(22, 20, 4, 4, showDate = true, showEvent = hasEvent)
        h >= 82 -> WidgetFit(22, 20, 4, 4, showDate = true, showEvent = false)
        else -> WidgetFit(22, 20, 4, 4, showDate = false, showEvent = false)
    }

    /**
     * The Square widget. Its event line is as wide as the widget and sits above the temperature, so on a
     * shorter widget it would run over the sky picture in the top right corner. The line shows only
     * where it clears the picture, not wherever the text fits: the picture reaches down to about 63 dp
     * (the bolt of a thunderstorm, day or night), and the line starts about 102 dp above the bottom with
     * this fit, so 170 dp leaves about 4 dp. The date goes only on the shortest, where the smallest clock
     * leaves no room under it.
     */
    private fun small(h: Int, hasEvent: Boolean): WidgetFit = when {
        hasEvent && h >= 178 -> WidgetFit(32, 24, 12, 12, showDate = true, showEvent = true)
        hasEvent && h >= 170 -> WidgetFit(26, 22, 8, 8, showDate = true, showEvent = true)
        h >= 152 -> WidgetFit(32, 24, 12, 12, showDate = true, showEvent = false)
        h >= 134 -> WidgetFit(26, 22, 8, 8, showDate = true, showEvent = false)
        h >= 122 -> WidgetFit(22, 20, 6, 6, showDate = true, showEvent = false)
        else -> WidgetFit(22, 20, 6, 6, showDate = false, showEvent = false)
    }
}
