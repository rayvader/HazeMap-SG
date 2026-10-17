package io.github.rayvader.hazemap

import android.graphics.Color

/**
 * Air-quality bands. PSI uses Singapore NEA's 24-hour PSI descriptor scale.
 * PM2.5 uses NEA's 1-hour PM2.5 concentration bands.
 */
enum class Band(val label: String, val color: Int) {
    GOOD("Good", Color.parseColor("#4CAF50")),
    MODERATE("Moderate", Color.parseColor("#C9CC2E")),
    UNHEALTHY("Unhealthy", Color.parseColor("#FF9800")),
    VERY_UNHEALTHY("Very unhealthy", Color.parseColor("#F44336")),
    HAZARDOUS("Hazardous", Color.parseColor("#7B1FA2"));

    companion object {
        /** NEA 24-hour PSI: 0-50 good, 51-100 moderate, 101-200 unhealthy, 201-300 very unhealthy, 300+ hazardous. */
        fun forPsi(value: Int): Band = when {
            value <= 50 -> GOOD
            value <= 100 -> MODERATE
            value <= 200 -> UNHEALTHY
            value <= 300 -> VERY_UNHEALTHY
            else -> HAZARDOUS
        }

        /** NEA 1-hour PM2.5 (ug/m3): 0-55 normal, 56-150 elevated, 151-250 high, 251-350 very high, 350+ hazardous. */
        fun forPm25(value: Int): Band = when {
            value <= 55 -> GOOD
            value <= 150 -> MODERATE
            value <= 250 -> UNHEALTHY
            value <= 350 -> VERY_UNHEALTHY
            else -> HAZARDOUS
        }

        fun forMetric(metric: Metric, value: Int): Band =
            if (metric == Metric.PSI) forPsi(value) else forPm25(value)
    }
}
