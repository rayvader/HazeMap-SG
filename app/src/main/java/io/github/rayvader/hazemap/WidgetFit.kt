package io.github.rayvader.hazemap

/**
 * How a widget packs its text for the height the home screen gave it. A short widget has no room for
 * everything, so the least useful part goes first: the date, then the event line. The clock and the
 * numbers shrink as the room does, and the space at the top and bottom is trimmed.
 *
 * Heights are in dp. Zero means the home screen has not said yet, which counts as plenty of room.
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

    private const val PLENTY = 1000

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
        val h = if (heightDp <= 0) PLENTY else (heightDp / fontScale.coerceAtLeast(1f)).toInt()
        return when (shape) {
            WidgetShape.WIDE -> wide(h, hasEvent)
            WidgetShape.SMALL -> small(h, hasEvent)
            WidgetShape.STRIP -> null
        }
    }

    /**
     * The Wide widget. Each height is what the parts take in the emulator test's drawings of it
     * (the `fit-*` screenshots), plus a few dp to spare.
     */
    private fun wide(h: Int, hasEvent: Boolean): WidgetFit = when {
        hasEvent && h >= 170 -> WidgetFit(44, 26, 12, 12, showDate = true, showEvent = true)
        hasEvent && h >= 146 -> WidgetFit(40, 26, 12, 12, showDate = false, showEvent = true)
        hasEvent && h >= 128 -> WidgetFit(34, 24, 8, 8, showDate = false, showEvent = true)
        h >= 146 -> WidgetFit(44, 26, 12, 12, showDate = true, showEvent = false)
        h >= 122 -> WidgetFit(34, 24, 8, 8, showDate = true, showEvent = false)
        h >= 112 -> WidgetFit(30, 22, 6, 6, showDate = true, showEvent = false)
        else -> WidgetFit(26, 22, 6, 6, showDate = false, showEvent = false)
    }

    /** The Square widget, which has less width, so the event line costs it more height. */
    private fun small(h: Int, hasEvent: Boolean): WidgetFit = when {
        hasEvent && h >= 178 -> WidgetFit(32, 24, 12, 12, showDate = true, showEvent = true)
        hasEvent && h >= 162 -> WidgetFit(32, 24, 12, 12, showDate = false, showEvent = true)
        hasEvent && h >= 144 -> WidgetFit(26, 22, 8, 8, showDate = false, showEvent = true)
        h >= 152 -> WidgetFit(32, 24, 12, 12, showDate = true, showEvent = false)
        h >= 134 -> WidgetFit(26, 22, 8, 8, showDate = true, showEvent = false)
        h >= 117 -> WidgetFit(26, 22, 8, 8, showDate = false, showEvent = false)
        else -> WidgetFit(22, 20, 6, 6, showDate = false, showEvent = false)
    }
}
