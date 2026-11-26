package io.github.rayvader.hazemap

/**
 * The colour of one event row's pill on the Wide widget. The pill keeps the same see-through amount it
 * always had (its alpha); only the colour behind it changes to the event's own colour, so a row reads at a
 * glance which calendar it is from. No Android in here, so it is tested on the JVM.
 *
 * White text sits on the pill. A pale calendar colour would wash the text out, so a too-light tint is
 * darkened (same alpha, darker colour) until the text is readable, rather than changing the text colour.
 */
object WidgetEventColour {

    /** The pill's see-through amount, from widget_event_bg (0x4D of 255, about 30%). Kept exactly. */
    const val PILL_ALPHA = 0x4D

    /** The neutral pill when an event has no colour: plain black at the pill alpha (the old look). */
    const val NEUTRAL_RGB = 0x000000

    /**
     * How light the pill may be for white text to stay readable. The pill is see-through over the map, so
     * this is a guard on the tint's own lightness, not a full contrast sum. 0..255 of perceived brightness.
     */
    private const val MAX_LIGHTNESS = 150

    /** Each darkening step multiplies the tint's RGB by this, keeping its hue. */
    private const val DARKEN_STEP = 0.82

    /** True when [displayColor] from the provider names a real colour (non-zero, any alpha). */
    fun hasColour(displayColor: Int): Boolean = (displayColor and 0xFFFFFF) != 0 || (displayColor ushr 24) != 0

    /**
     * The ARGB the row's pill is drawn with: the event's colour at the pill alpha, darkened if it is too
     * light for white text; or the neutral pill when the event has no colour. The alpha is always
     * [PILL_ALPHA], whatever the event colour's own alpha was.
     */
    fun pillColor(displayColor: Int): Int {
        val rgb = if (hasColour(displayColor)) darkenIfPale(displayColor and 0xFFFFFF) else NEUTRAL_RGB
        return (PILL_ALPHA shl 24) or (rgb and 0xFFFFFF)
    }

    /** Darkens an RGB while it is too light for white text, keeping its hue; leaves a dark colour alone. */
    private fun darkenIfPale(rgb: Int): Int {
        var r = (rgb shr 16) and 0xFF
        var g = (rgb shr 8) and 0xFF
        var b = rgb and 0xFF
        var guard = 0
        while (lightness(r, g, b) > MAX_LIGHTNESS && guard < 12) {
            r = (r * DARKEN_STEP).toInt()
            g = (g * DARKEN_STEP).toInt()
            b = (b * DARKEN_STEP).toInt()
            guard++
        }
        return (r shl 16) or (g shl 8) or b
    }

    /** Perceived brightness, 0 (black) to 255 (white): the usual 0.299/0.587/0.114 weighting. */
    fun lightness(r: Int, g: Int, b: Int): Int = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

    fun lightness(rgb: Int): Int = lightness((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
}
