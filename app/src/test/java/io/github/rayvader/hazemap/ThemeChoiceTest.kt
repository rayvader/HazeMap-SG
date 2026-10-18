package io.github.rayvader.hazemap

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeChoiceTest {

    @Test
    fun savedValuesMapBackToChoices() {
        assertEquals(ThemeChoice.LIGHT, ThemeChoice.fromKey("light"))
        assertEquals(ThemeChoice.DARK, ThemeChoice.fromKey("dark"))
    }

    @Test
    fun nothingSavedOrAnythingUnknownFollowsThePhone() {
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromKey(null))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromKey(""))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromKey("purple"))
    }

    @Test
    fun everyChoiceSurvivesSavingAndLoading() {
        for (choice in ThemeChoice.entries) {
            assertEquals(choice, ThemeChoice.fromKey(choice.key))
        }
    }

    @Test
    fun aTapFlipsWhatIsOnScreen() {
        assertEquals(ThemeChoice.LIGHT, ThemeChoice.afterTap(isDarkNow = true))
        assertEquals(ThemeChoice.DARK, ThemeChoice.afterTap(isDarkNow = false))
    }

    @Test
    fun choicesUseTheRightAndroidNightMode() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeChoice.SYSTEM.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeChoice.LIGHT.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeChoice.DARK.nightMode)
    }

    @Test
    fun darkMapMatrixIsFourByFive() {
        assertEquals(20, MapTheme.DARK_TILES.size)
        // Alpha passes through untouched.
        assertEquals(listOf(0f, 0f, 0f, 1f, 0f), MapTheme.DARK_TILES.toList().subList(15, 20))
    }

    @Test
    fun darkMapFlipsGreysSoWhiteBecomesBlackAndTextBecomesWhite() {
        for (g in listOf(0, 64, 128, 200, 255)) {
            val (r, gg, b) = MapTheme.apply(g, g, g)
            // Allow 1 for float rounding.
            for (out in listOf(r, gg, b)) assertTrue("grey $g became $out", Math.abs(out - (255 - g)) <= 1)
        }
    }

    @Test
    fun darkMapKeepsWaterBlueAndDarkensIt() {
        // OpenStreetMap water is about (170, 211, 223).
        val (r, g, b) = MapTheme.apply(170, 211, 223)
        assertTrue("water should stay blue-dominant: ($r, $g, $b)", b > r && b > g - 20)
        assertTrue("water should be dark: ($r, $g, $b)", r < 60 && g < 100 && b < 110)
    }

    @Test
    fun darkMapKeepsGrassGreen() {
        // OpenStreetMap parkland is about (205, 235, 176).
        val (r, g, b) = MapTheme.apply(205, 235, 176)
        assertTrue("grass should stay green-dominant: ($r, $g, $b)", g > r && g > b)
    }
}
