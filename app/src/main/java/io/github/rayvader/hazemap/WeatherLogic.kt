package io.github.rayvader.hazemap

import kotlin.math.roundToInt

/** What a forecast line looks like, for picking an icon. */
enum class Condition {
    SUN, MOON, PARTLY, CLOUD, RAIN, STORM, HAZE, WIND;

    val isWet: Boolean get() = this == RAIN || this == STORM

    companion object {
        /** Maps NEA forecast text ("Partly Cloudy (Night)", "Heavy Thundery Showers") to a condition. */
        fun fromText(text: String?): Condition {
            val t = text?.lowercase().orEmpty()
            val night = t.contains("night")
            return when {
                t.contains("thunder") -> STORM
                t.contains("shower") || t.contains("rain") || t.contains("drizzle") -> RAIN
                t.contains("haz") || t.contains("mist") || t.contains("fog") || t.contains("smoke") -> HAZE
                t.contains("wind") -> WIND
                t.contains("partly") -> if (night) CLOUD else PARTLY
                t.contains("cloud") || t.contains("overcast") -> CLOUD
                t.contains("fair") -> if (night) MOON else SUN
                else -> CLOUD
            }
        }

        fun isWet(text: String?): Boolean = fromText(text).isWet
    }
}

/** Short sentences built from the 24-hour forecast. */
object ForecastText {

    /** "Morning", "Afternoon", "Night" from the hour a period starts; "Tmrw ..." once it starts on a later day. */
    fun periodLabel(startMillis: Long, nowMillis: Long): String {
        val base = when (TimeFmt.hourOfDay(startMillis)) {
            in 5..11 -> "Morning"
            in 12..17 -> "Afternoon"
            else -> "Night"
        }
        val daysAhead = TimeFmt.dayIndex(startMillis) - TimeFmt.dayIndex(nowMillis)
        return if (daysAhead >= 1) "Tmrw ${base.lowercase()}" else base
    }

    /**
     * "thundery showers later" when the user's region gets rain in a coming period and is dry now.
     * Null when it is already wet, or nothing wet is coming.
     */
    fun laterNote(periods: List<ForecastPeriod>, regionKey: String, nowMillis: Long, current: String?): String? {
        if (Condition.isWet(current)) return null
        val next = periods.firstOrNull { it.startMillis > nowMillis && Condition.isWet(it.byRegion[regionKey]) }
            ?: return null
        val text = next.byRegion[regionKey]?.lowercase() ?: return null
        val tomorrow = TimeFmt.dayIndex(next.startMillis) > TimeFmt.dayIndex(nowMillis)
        return if (tomorrow) "$text tomorrow" else "$text later"
    }

    /** "Thundery Showers N, W": rain forecast for other regions when the user's own region stays dry. */
    fun rainElsewhere(period: ForecastPeriod, myRegionKey: String): String? {
        if (Condition.isWet(period.byRegion[myRegionKey])) return null
        val wet = period.byRegion.filter { it.key != myRegionKey && Condition.isWet(it.value) }
        if (wet.isEmpty()) return null
        val text = wet.values.first()
        val letters = wet.filter { it.value == text }.keys.map { it.first().uppercaseChar().toString() }.sorted()
        return "$text ${letters.joinToString(",\u00A0")}"
    }
}

object WindFmt {
    private val points = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    )

    /** Compass point the wind blows FROM, for a bearing in degrees. */
    fun compass(degrees: Double): String {
        val d = ((degrees % 360.0) + 360.0) % 360.0
        return points[((d / 22.5) + 0.5).toInt() % 16]
    }

    fun kmh(knots: Double): Int = (knots * 1.852).roundToInt()
}

/** How hard it rained at a gauge, from the mm it caught in 5 minutes. A tip of the gauge bucket is 0.2 mm. */
enum class RainLevel {
    DRY, LIGHT, MODERATE, HEAVY, VERY_HEAVY;

    companion object {
        fun forFiveMinutes(mm: Double): RainLevel = when {
            mm <= 0.0 -> DRY
            mm < 0.3 -> LIGHT // one tip: 2.4 mm/h
            mm < 0.9 -> MODERATE // up to 9.6 mm/h
            mm < 4.2 -> HEAVY // up to 48 mm/h
            else -> VERY_HEAVY // 50 mm/h and up
        }
    }
}

/** Slippy-map tile maths for the RainViewer radar tiles. */
object TileMath {
    /** RainViewer's free tiles stop at zoom 7. */
    const val ZOOM = 7

    /** The two zoom-7 tiles that cover Singapore and the sea around it (lon 101.25 to 106.9, lat 0 to 2.8). */
    val SINGAPORE_X = listOf(100, 101)
    const val SINGAPORE_Y = 63

    fun lonLeft(x: Int, zoom: Int = ZOOM): Double = x.toDouble() / (1 shl zoom) * 360.0 - 180.0

    fun latTop(y: Int, zoom: Int = ZOOM): Double {
        val n = Math.PI - 2.0 * Math.PI * y / (1 shl zoom)
        return Math.toDegrees(Math.atan(Math.sinh(n)))
    }

    /** Radar tile URL in RainViewer's z/x/y form: 256 px, "Universal Blue" colours, smoothed. */
    fun radarUrl(index: RadarIndex, frame: RadarFrame, x: Int, y: Int): String =
        "${index.host}${frame.path}/256/$ZOOM/$x/$y/2/1_0.png"
}

/** Colours for the rain legends and gauge dots. Plain ARGB ints so they load in a JVM test. */
object RainPalette {
    /** RainViewer's "Universal Blue" scale, light rain to heavy, as published in its colour table. */
    val RADAR = intArrayOf(
        0xFF88DDEE.toInt(), 0xFF00A3E0.toInt(), 0xFF005588.toInt(),
        0xFFFFEE00.toInt(), 0xFFFF4400.toInt(), 0xFFC10000.toInt()
    )

    fun gauge(level: RainLevel): Int = when (level) {
        RainLevel.DRY -> 0x00000000
        RainLevel.LIGHT -> 0xFF36BAE5.toInt()
        RainLevel.MODERATE -> 0xFF005588.toInt()
        RainLevel.HEAVY -> 0xFFFFAA00.toInt()
        RainLevel.VERY_HEAVY -> 0xFFC10000.toInt()
    }

    val GAUGE = intArrayOf(
        gauge(RainLevel.LIGHT), gauge(RainLevel.MODERATE), gauge(RainLevel.HEAVY), gauge(RainLevel.VERY_HEAVY)
    )
}
