package io.github.rayvader.hazemap

import android.content.Context
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import org.osmdroid.views.MapView

object Icons {
    fun forCondition(c: Condition): Int = when (c) {
        Condition.SUN -> R.drawable.cond_sun
        Condition.MOON -> R.drawable.cond_moon
        Condition.PARTLY -> R.drawable.cond_partly
        Condition.CLOUD -> R.drawable.cond_cloud
        Condition.RAIN -> R.drawable.cond_rain
        Condition.STORM -> R.drawable.cond_storm
        Condition.HAZE -> R.drawable.cond_haze
        Condition.WIND -> R.drawable.cond_wind
    }
}

/** Paints a TextView as a rounded colour badge. */
fun TextView.setBadge(color: Int, textColor: Int, density: Float) {
    background = GradientDrawable().apply {
        cornerRadius = 10f * density
        setColor(color)
    }
    setTextColor(textColor)
}

/** True when the screen is showing the dark theme (the user's pick, or the phone's setting). */
fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Darkens the base map for the dark theme. Radar, gauges and dots are drawn above it and keep their colours. */
fun MapView.styleForTheme(night: Boolean) {
    if (night) overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(ColorMatrix(MapTheme.DARK_TILES)))
}
