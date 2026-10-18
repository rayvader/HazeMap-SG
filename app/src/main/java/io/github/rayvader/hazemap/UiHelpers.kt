package io.github.rayvader.hazemap

import android.graphics.drawable.GradientDrawable
import android.widget.TextView

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
