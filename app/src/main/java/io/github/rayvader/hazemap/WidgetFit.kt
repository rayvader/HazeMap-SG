package io.github.rayvader.hazemap

/**
 * How a widget packs its text for the height the home screen gave it. The date stays under the clock.
 * A short widget has no room for everything, so the event line goes first, and the date only when even
 * the smallest clock and numbers leave no room for it (a short Square). The clock and the numbers shrink
 * as the room does, and the space at the top and bottom is trimmed.
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
     * as that much shorter. A smaller setting gains nothing, to stay on the safe side.
     */
    fun of(shape: WidgetShape, heightDp: Int, hasEvent: Boolean, fontScale: Float = 1f): WidgetFit? {
        val known = if (heightDp > 0) heightDp else SHORTEST_DP
        val h = (known / fontScale.coerceAtLeast(1f)).toInt()
        return when (shape) {
            WidgetShape.WIDE -> wide(h, hasEvent)
            WidgetShape.SMALL -> small(h, hasEvent)
            WidgetShape.STRIP -> null
        }
    }

    /**
     * The Wide widget. Each height is what the parts take in the emulator test's drawings of it
     * (the `fit-*` screenshots), plus a few dp to spare. The date sits under the clock at every height a
     * home screen offers; the event line is what gives way. Only text enlarged past what the shortest
     * Wide holds loses the date as well.
     */
    private fun wide(h: Int, hasEvent: Boolean): WidgetFit = when {
        hasEvent && h >= 170 -> WidgetFit(44, 26, 12, 12, showDate = true, showEvent = true)
        hasEvent && h >= 166 -> WidgetFit(40, 26, 12, 12, showDate = true, showEvent = true)
        hasEvent && h >= 149 -> WidgetFit(34, 24, 8, 8, showDate = true, showEvent = true)
        hasEvent && h >= 138 -> WidgetFit(30, 22, 6, 6, showDate = true, showEvent = true)
        h >= 146 -> WidgetFit(44, 26, 12, 12, showDate = true, showEvent = false)
        h >= 122 -> WidgetFit(34, 24, 8, 8, showDate = true, showEvent = false)
        h >= 112 -> WidgetFit(30, 22, 6, 6, showDate = true, showEvent = false)
        h >= 106 -> WidgetFit(26, 22, 6, 6, showDate = true, showEvent = false)
        else -> WidgetFit(26, 22, 6, 6, showDate = false, showEvent = false)
    }

    /**
     * The Square widget. Its event line is as wide as the widget and sits above the temperature, so on a
     * shorter widget it would run over the sky picture in the top right corner. The line shows only
     * where it clears the picture, not wherever the text fits: the picture reaches down to about 63 dp
     * (the bolt of the night thunderstorm), and the line starts 101 dp above the bottom with this fit,
     * so 168 dp leaves about 3 dp. The date goes only on the shortest, where the smallest clock leaves
     * no room under it.
     */
    private fun small(h: Int, hasEvent: Boolean): WidgetFit = when {
        hasEvent && h >= 178 -> WidgetFit(32, 24, 12, 12, showDate = true, showEvent = true)
        hasEvent && h >= 168 -> WidgetFit(26, 22, 8, 8, showDate = true, showEvent = true)
        h >= 152 -> WidgetFit(32, 24, 12, 12, showDate = true, showEvent = false)
        h >= 134 -> WidgetFit(26, 22, 8, 8, showDate = true, showEvent = false)
        h >= 122 -> WidgetFit(22, 20, 6, 6, showDate = true, showEvent = false)
        else -> WidgetFit(22, 20, 6, 6, showDate = false, showEvent = false)
    }
}
