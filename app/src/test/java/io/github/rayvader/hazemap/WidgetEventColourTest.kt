package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetEventColourTest {

    private fun alpha(argb: Int) = (argb ushr 24) and 0xFF
    private fun rgb(argb: Int) = argb and 0xFFFFFF

    @Test
    fun thePillKeepsTheOldSeeThroughAmount() {
        // Whatever the event colour, the pill alpha is always 0x4D, the old widget_event_bg alpha.
        assertEquals(0x4D, alpha(WidgetEventColour.pillColor(0xFF3366CC.toInt())))
        assertEquals(0x4D, alpha(WidgetEventColour.pillColor(0)))
        assertEquals(0x4D, alpha(WidgetEventColour.pillColor(0xFFFFFFFF.toInt())))
    }

    @Test
    fun aMidEventColourIsKept() {
        // A normal mid colour (Google "Tomato" red, 0xD50000) is used as-is: dark enough for white text.
        val pill = WidgetEventColour.pillColor(0xFFD50000.toInt())
        assertEquals(0xD50000, rgb(pill))
        assertEquals(0x4D, alpha(pill))
    }

    @Test
    fun noColourFallsBackToTheNeutralPill() {
        // 0 means the provider gave no colour: the old neutral black pill at the same alpha.
        assertEquals(WidgetEventColour.NEUTRAL_RGB, rgb(WidgetEventColour.pillColor(0)))
        assertFalse(WidgetEventColour.hasColour(0))
        assertTrue(WidgetEventColour.hasColour(0xFF112233.toInt()))
        assertTrue(WidgetEventColour.hasColour(0x00112233)) // colour in the low bits, no alpha, still a colour
    }

    @Test
    fun aVeryLightColourIsDarkenedForWhiteText() {
        // A pale yellow (0xFFF6C8) would wash out white text; it must come back darker, same alpha.
        val pale = 0xFFFFF6C8.toInt()
        val pill = WidgetEventColour.pillColor(pale)
        assertEquals(0x4D, alpha(pill))
        assertTrue("pill lightness ${WidgetEventColour.lightness(rgb(pill))}", WidgetEventColour.lightness(rgb(pill)) <= 150)
        // It is darker than the original, not black, and keeps a warm hue (r still the largest channel).
        val r = (rgb(pill) shr 16) and 0xFF
        val g = (rgb(pill) shr 8) and 0xFF
        val b = rgb(pill) and 0xFF
        assertTrue("not black", r + g + b > 0)
        assertTrue("hue kept: r>=g>=b", r >= g && g >= b)
    }

    @Test
    fun aDarkColourIsNotLightened() {
        // A dark blue stays as it is (only too-light colours are changed).
        val dark = 0xFF1A237E.toInt()
        assertEquals(0x1A237E, rgb(WidgetEventColour.pillColor(dark)))
    }

    @Test
    fun theDisplayColourTravelsOnTheEvent() {
        // The data class carries the colour the provider gives; 0 by default when none.
        val e = CalendarEvent(1, 2, "Lunch", 1_000, 2_000, allDay = false, displayColor = 0xFF0B8043.toInt())
        assertEquals(0xFF0B8043.toInt(), e.displayColor)
        assertEquals(0, CalendarEvent(1, 2, "x", 0, 0, allDay = false).displayColor)
    }
}
