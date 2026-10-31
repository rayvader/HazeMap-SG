package io.github.rayvader.hazemap

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One location reading from the phone: where, and when it was taken (epoch ms). */
data class Fix(val lat: Double, val lon: Double, val timeMs: Long)

/** What "Follow my location" does right now. */
enum class FollowState {
    /** The switch is off: the app and widgets stay on the chosen area. */
    OFF,

    /** The app and the widgets follow the phone. */
    ALL,

    /** The app follows the phone; widgets keep the last area the app saw ("all the time" was not allowed). */
    APP_ONLY,

    /** The switch is on, but the app may not read the location at all now. Nothing moves. */
    NO_PERMISSION
}

/** The rules of "Follow my location", with no Android in them. */
object FollowLogic {
    /** A new reading must be this far from the one last used before the area can change. */
    const val MOVE_METERS = 1_000.0

    /** An older reading says little about where the phone is now: keep the last area instead. */
    const val STALE_MS = 6 * 60 * 60 * 1000L

    /** A reading at least this fresh is good enough when the app opens; older ones ask for a new one. */
    const val FRESH_MS = 15 * 60 * 1000L

    /** Android 10: "Allow all the time" became its own permission. */
    const val BACKGROUND_SDK = 29

    fun state(follow: Boolean, coarse: Boolean, background: Boolean, sdk: Int): FollowState = when {
        !follow -> FollowState.OFF
        !coarse -> FollowState.NO_PERMISSION
        sdk >= BACKGROUND_SDK && !background -> FollowState.APP_ONLY
        else -> FollowState.ALL
    }

    /** Singapore and a little sea around it. Outside it the 47 NEA areas say nothing about the weather. */
    fun inSingapore(lat: Double, lon: Double): Boolean = lat in 1.10..1.50 && lon in 103.55..104.12

    /** Great-circle distance in metres. */
    fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** What to do with one reading. */
    sealed interface Step {
        /** Ignore it: missing, too old, abroad, or too close to the last one used. */
        object Keep : Step

        /** Use it: remember [fix] as the last one used, and show [area] (null when the area stays the same). */
        data class Use(val fix: Fix, val area: Area?) : Step
    }

    /**
     * Decides what a new reading changes. The area changes only when the phone moved more than [MOVE_METERS]
     * since the last reading used AND the nearest NEA area is a different one, so a phone standing near the
     * line between two areas does not flip between them. Changing area needs no new download: NEA's
     * documents are island-wide, and the app picks the area's values from them.
     */
    fun step(current: Area, lastUsed: Fix?, fix: Fix?, nowMs: Long): Step {
        if (fix == null) return Step.Keep
        if (nowMs - fix.timeMs > STALE_MS) return Step.Keep
        if (!inSingapore(fix.lat, fix.lon)) return Step.Keep
        if (lastUsed != null && distanceMeters(lastUsed.lat, lastUsed.lon, fix.lat, fix.lon) < MOVE_METERS) {
            return Step.Keep
        }
        val nearest = Areas.nearest(fix.lat, fix.lon)
        return Step.Use(fix, nearest.takeIf { it != current })
    }

    /** Whether the app should ask for a new reading after looking at [last] (null: there is none). */
    fun wantsNewReading(last: Fix?, nowMs: Long): Boolean = last == null || nowMs - last.timeMs > FRESH_MS

    /** How the last reading used is kept on the phone: rounded to about 100 m, it never leaves the phone. */
    fun encode(fix: Fix): String = "%.3f,%.3f,%d".format(java.util.Locale.ROOT, fix.lat, fix.lon, fix.timeMs)

    fun decode(text: String?): Fix? {
        val parts = text?.split(',') ?: return null
        if (parts.size != 3) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        val time = parts[2].toLongOrNull() ?: return null
        return Fix(lat, lon, time)
    }
}
