package io.github.rayvader.hazemap

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.ln
import kotlin.math.tan

/** One cloud picture: Himawari's infrared view of the sky at [timeMillis] (UTC). */
data class CloudFrame(val timeMillis: Long)

/**
 * The cloud layer's rules, with no Android in them: which pictures to ask NASA GIBS for, which part of them
 * covers the Rain tab's map, how a picture's grey becomes see-through cloud, and which picture goes with a
 * rain frame.
 *
 * The pictures are Himawari's Band 13 ("clean infrared"), served by NASA GIBS with no key. Infrared sees
 * clouds by night as by day: cold cloud tops are bright, warm ground and sea are dark grey. A new picture
 * comes every 10 minutes and is on GIBS about 20 to 30 minutes later.
 */
object CloudLogic {

    /** GIBS draws this layer up to zoom 6, where one picture dot is about 2.4 km here. */
    const val ZOOM = 6

    /** The zoom-6 tiles over Singapore: x 50 (lon 101.25 to 106.9), y 31 (lat 0 to 5.6) and y 32 (0 to -5.6). */
    const val TILE_X = 50
    val TILE_Y = listOf(31, 32)

    /** A picture every 10 minutes, and two hours of them, like the radar. */
    const val STEP_MS = 10 * 60_000L
    const val FRAMES = 13

    /** How far back to look for the newest picture: GIBS has it about 30 minutes late, sometimes later. */
    const val LOOK_BACK_STEPS = 12

    /** The first try is this long ago: nothing newer is ever there yet. */
    const val FIRST_TRY_AGO_MS = 10 * 60_000L

    /**
     * The part of the pictures the app keeps: wider than the map can show at its least zoom (8.5) around
     * Singapore, so the clouds never stop at an edge on screen.
     */
    const val NORTH = 3.2
    const val SOUTH = -0.6
    const val WEST = 102.0
    const val EAST = 105.6

    /** The kept part is drawn this many times bigger before it is made see-through, so cloud edges are smooth. */
    const val UPSCALE = 3

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun tileUrl(frame: CloudFrame, y: Int): String =
        "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/Himawari_AHI_Band13_Clean_Infrared/default/" +
            "${stamp.format(Instant.ofEpochMilli(frame.timeMillis))}/GoogleMapsCompatible_Level$ZOOM/$ZOOM/$y/$TILE_X.png"

    /** The 10-minute mark at or before [millis]. */
    fun floorToStep(millis: Long): Long = Math.floorDiv(millis, STEP_MS) * STEP_MS

    /** The times to try for the newest picture, newest first, without asking GIBS for its 5.8 MB list. */
    fun newestCandidates(nowMillis: Long): List<CloudFrame> {
        val first = floorToStep(nowMillis - FIRST_TRY_AGO_MS)
        return (0 until LOOK_BACK_STEPS).map { CloudFrame(first - it * STEP_MS) }
    }

    /** The two hours of pictures ending at [newest], oldest first. */
    fun framesEndingAt(newest: CloudFrame): List<CloudFrame> =
        (FRAMES - 1 downTo 0).map { CloudFrame(newest.timeMillis - it * STEP_MS) }

    /**
     * The cloud picture to show with a rain frame from [rainMillis]: the newest one taken no later than it
     * (a few minutes' leeway), or the oldest one when the rain is older than every cloud picture. Null when
     * there is none.
     */
    fun matching(frames: List<CloudFrame>, rainMillis: Long): CloudFrame? {
        if (frames.isEmpty()) return null
        val sorted = frames.sortedBy { it.timeMillis }
        return sorted.lastOrNull { it.timeMillis <= rainMillis + LEEWAY_MS } ?: sorted.first()
    }

    private const val LEEWAY_MS = 5 * 60_000L

    // ---- Where the kept part sits in the pictures ----

    private fun mercatorY(lat: Double): Double = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2))

    /** Picture dots from the left of tile [TILE_X], at [lon]. */
    fun pixelX(lon: Double): Double = ((lon + 180.0) / 360.0 * (1 shl ZOOM) - TILE_X) * 256.0

    /** Picture dots from the top of tile [TILE_Y].first, at [lat]: both tiles are stacked, 512 dots tall. */
    fun pixelY(lat: Double): Double =
        ((1 - mercatorY(lat) / Math.PI) / 2 * (1 shl ZOOM) - TILE_Y.first()) * 256.0

    /** The kept part, in dots of the stacked pair: left, top, width, height. */
    fun crop(): IntArray {
        val left = pixelX(WEST).toInt()
        val right = Math.ceil(pixelX(EAST)).toInt()
        val top = pixelY(NORTH).toInt()
        val bottom = Math.ceil(pixelY(SOUTH)).toInt()
        return intArrayOf(left, top, right - left, bottom - top)
    }

    /** The edges of the kept part, back on the map, so the drawing lines up dot for dot: north, west, south, east. */
    fun cropBounds(): DoubleArray {
        val c = crop()
        return doubleArrayOf(latAt(c[1].toDouble()), lonAt(c[0].toDouble()), latAt((c[1] + c[3]).toDouble()), lonAt((c[0] + c[2]).toDouble()))
    }

    private fun lonAt(px: Double): Double = (px / 256.0 + TILE_X) / (1 shl ZOOM) * 360.0 - 180.0

    private fun latAt(py: Double): Double {
        val n = Math.PI - 2.0 * Math.PI * (py / 256.0 + TILE_Y.first()) / (1 shl ZOOM)
        return Math.toDegrees(Math.atan(Math.sinh(n)))
    }

    // ---- Grey picture to see-through cloud ----

    /**
     * How cloudy one picture dot is, 0 (clear) to 1. GIBS draws warm ground and sea in dark grey (about 100
     * to 110 of 255), colder cloud tops in lighter grey, and the coldest, tallest storm tops in colour.
     */
    fun cloudiness(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        if (chroma > COLOUR_CHROMA) return 1f
        return ((r - CLEAR_GREY) / (CLOUD_GREY - CLEAR_GREY).toFloat()).coerceIn(0f, 1f)
    }

    private const val COLOUR_CHROMA = 30
    private const val CLEAR_GREY = 112
    private const val CLOUD_GREY = 187

    /** Under this much cloud a dot is see-through; up to [EDGE_TO] it is the edge line around a cloud. */
    const val CLEAR_BELOW = 0.12f
    const val EDGE_TO = 0.24f

    /** Light grey clouds with a slightly darker edge, so they stand out on the light map and on the dark one. */
    fun fillColour(dark: Boolean): Int = if (dark) 0xDEE4EA else 0xCDD3DB
    fun edgeColour(dark: Boolean): Int = if (dark) 0x96A0AC else 0x7A8592

    private const val EDGE_ALPHA = 170
    private const val FILL_ALPHA_MIN = 120
    private const val FILL_ALPHA_MAX = 225

    /** One picture dot as a see-through cloud dot. */
    fun toCloud(argb: Int, dark: Boolean): Int {
        val c = cloudiness(argb)
        return when {
            c < CLEAR_BELOW -> 0
            c < EDGE_TO -> (EDGE_ALPHA shl 24) or edgeColour(dark)
            else -> {
                val alpha = (FILL_ALPHA_MIN + (FILL_ALPHA_MAX - FILL_ALPHA_MIN) * (c - EDGE_TO) / (1 - EDGE_TO)).toInt()
                (alpha shl 24) or fillColour(dark)
            }
        }
    }

    /** A whole picture, in place. */
    fun toClouds(pixels: IntArray, dark: Boolean) {
        for (i in pixels.indices) pixels[i] = toCloud(pixels[i], dark)
    }

    /** How much of the kept part is cloud, 0 to 1: the emulator test logs it. */
    fun cover(pixels: IntArray): Float = if (pixels.isEmpty()) 0f else pixels.count { (it ushr 24) > 0 } / pixels.size.toFloat()
}
