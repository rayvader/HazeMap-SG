package io.github.rayvader.hazemap

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Names of the app's saved settings. */
object Prefs {
    const val NAME = "hazemap"
    const val KEY_THEME = "theme"
}

/**
 * The theme the user picked. SYSTEM means nothing was picked yet, so the app follows the phone's
 * dark-mode setting, as it did before the toggle existed.
 */
enum class ThemeChoice(val key: String?, val nightMode: Int) {
    SYSTEM(null, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        /** An unknown or missing saved value means no choice was made. */
        fun fromKey(key: String?): ThemeChoice = entries.firstOrNull { it.key == key } ?: SYSTEM

        /** What a tap on the theme button picks: the opposite of what is on screen. */
        fun afterTap(isDarkNow: Boolean): ThemeChoice = if (isDarkNow) LIGHT else DARK
    }
}

/** Saves and loads the theme choice. */
object ThemeStore {
    fun load(context: Context): ThemeChoice =
        ThemeChoice.fromKey(
            context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getString(Prefs.KEY_THEME, null)
        )

    /** Saves the choice and switches now. AppCompat recreates the open screen in the new theme. */
    fun choose(context: Context, choice: ThemeChoice) {
        save(context, choice)
        AppCompatDelegate.setDefaultNightMode(choice.nightMode)
    }

    fun save(context: Context, choice: ThemeChoice) {
        // A null key removes the entry, which puts the app back to following the phone.
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit().putString(Prefs.KEY_THEME, choice.key).apply()
    }
}

/** How the OpenStreetMap tiles are darkened for the dark theme. */
object MapTheme {
    /**
     * An Android colour matrix (4 x 5, row by row; the last column is an offset). It flips brightness and
     * turns the hues back, like CSS invert(1) hue-rotate(180deg): white land turns near-black, text turns
     * white, and water stays blue and grass stays green.
     */
    val DARK_TILES = floatArrayOf(
        0.574f, -1.430f, -0.144f, 0f, 255f,
        -0.426f, -0.430f, -0.144f, 0f, 255f,
        -0.426f, -1.430f, 0.856f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )

    /** What one pixel (0 to 255 per channel) becomes under [DARK_TILES], clamped like Android does. */
    fun apply(r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
        fun channel(row: Int): Int {
            val m = DARK_TILES
            val v = m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]
            return v.toInt().coerceIn(0, 255)
        }
        return Triple(channel(0), channel(1), channel(2))
    }
}
