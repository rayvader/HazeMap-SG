package io.github.rayvader.hazemap

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.ln
import kotlin.math.tan

/** One cloud picture: Himawari's infrared view of the sky at [timeMillis] (UTC). */
data class CloudFrame(val timeMillis: Long)

/** Where a cloud picture came from, shown in the time box so the reading is honest. */
enum class CloudSourceId(val label: String) { NICT("NICT"), GIBS("GIBS") }

/**
 * The cloud layer's rules, with no Android in them: which pictures to ask NASA GIBS for, which part of them
 * covers the Rain tab's map, how a picture's grey becomes see-through cloud, and which picture goes with a
 * rain frame.
 *
 * The pictures are Himawari's Band 13 ("clean infrared"), served by NASA GIBS with no key. Infrared sees
 * clouds by night as by day: cold cloud tops are bright, warm ground and sea are dark grey. A new picture
 * comes every 10 minutes, but GIBS runs well behind real time: the newest one there is usually about 50
 * minutes old (seen Nov 2026: at 08:21Z the newest was 07:30Z). So the "now" cloud picture lags the rain.
 */
object CloudLogic {

    /** GIBS draws this layer up to zoom 6, where one picture dot is about 2.4 km here. */
    const val ZOOM = 6

    /** The zoom-6 tiles over Singapore are worked out from the crop bounds (see [gibsTileX]/[gibsTileY]). */

    /** A picture every 10 minutes, and two hours of them, like the radar. */
    const val STEP_MS = 10 * 60_000L
    const val FRAMES = 13

    /** How far back to look for the newest picture, in 10-minute steps: GIBS runs about 50 minutes behind,
     * sometimes more, so two hours of candidates leaves plenty of room. */
    const val LOOK_BACK_STEPS = 12

    /**
     * How late GIBS usually is: the newest picture there is about this old. Used to tell when the clouds on
     * screen have gone stale (a newer picture should exist by now) and should be reloaded.
     */
    const val LOOK_BACK_MS = 50 * 60_000L

    /** The first try is this long ago: nothing newer is ever there yet. */
    const val FIRST_TRY_AGO_MS = 10 * 60_000L

    /**
     * The newest 10-minute mark GIBS could plausibly have a picture for at [nowMillis]: now minus the usual
     * ~50-minute lag, floored to a 10-minute mark.
     */
    fun newestExpected(nowMillis: Long): Long = floorToStep(nowMillis - LOOK_BACK_MS)

    /**
     * True when the clouds on screen are stale and should be reloaded: the newest one loaded is at least one
     * step (10 min) older than the newest GIBS could now have. This mirrors the rain's "a newer frame exists"
     * rule. [loadedNewestMillis] is null when nothing is loaded yet (then it is not "stale", just missing).
     */
    fun cloudsStale(loadedNewestMillis: Long?, nowMillis: Long): Boolean {
        if (loadedNewestMillis == null) return false
        return newestExpected(nowMillis) - loadedNewestMillis >= STEP_MS
    }

    /**
     * How far behind the rain the shown cloud picture is, in milliseconds (0 when the cloud is as new as the
     * rain or newer). The time box warns when this passes [CLOUD_BEHIND_WARN_MS].
     */
    fun cloudBehindMs(cloudMillis: Long, rainMillis: Long): Long = (rainMillis - cloudMillis).coerceAtLeast(0L)

    /** Past this much behind the rain, the time box says the cloud picture is older, and by how much. */
    const val CLOUD_BEHIND_WARN_MS = 15 * 60_000L

    /**
     * The part of the pictures the app keeps: wider than the map can show at its least zoom (8.5) around
     * Singapore, so the clouds cover past every screen edge and never stop in a line on screen. At zoom 8.5
     * the viewport reaches about lon 101.7 to 105.9 and lat -3.2 to 5.9; these bounds leave a margin past
     * that on every side.
     */
    const val NORTH = 6.4
    const val SOUTH = -3.8
    const val WEST = 101.0
    const val EAST = 106.4

    /** The kept part is drawn this many times bigger before it is made see-through, so cloud edges are smooth. */
    const val UPSCALE = 3

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /** The zoom-6 GIBS tile columns and rows the crop needs. Derived from the bounds, so widening them here
     * is all it takes. */
    fun gibsTileX(): IntRange {
        val left = Math.floorDiv(tileXOf(WEST), 256)
        val right = Math.floorDiv(tileXOf(EAST - 1e-9), 256)
        return left..right
    }

    fun gibsTileY(): IntRange {
        val top = Math.floorDiv(tileYOf(NORTH), 256)
        val bottom = Math.floorDiv(tileYOf(SOUTH - 1e-9), 256)
        return top..bottom
    }

    private fun tileXOf(lon: Double): Int = Math.floor((lon + 180.0) / 360.0 * (1 shl ZOOM) * 256.0).toInt()
    private fun tileYOf(lat: Double): Int = Math.floor((1 - mercatorY(lat) / Math.PI) / 2 * (1 shl ZOOM) * 256.0).toInt()

    /** One GIBS tile of [frame]. */
    fun tileUrl(frame: CloudFrame, x: Int, y: Int): String =
        "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/Himawari_AHI_Band13_Clean_Infrared/default/" +
            "${stamp.format(Instant.ofEpochMilli(frame.timeMillis))}/GoogleMapsCompatible_Level$ZOOM/$ZOOM/$y/$x.png"


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

    // ---- Where the kept part sits in the GIBS tile block ----
    //
    // The GIBS tiles the crop needs (see [gibsTileX]/[gibsTileY]) are laid out as one block, its top-left at
    // tile (gibsTileX().first, gibsTileY().first). Every dot position below is measured from that corner.

    private fun mercatorY(lat: Double): Double = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2))

    private fun originCol(): Int = gibsTileX().first
    private fun originRow(): Int = gibsTileY().first

    /** Picture dots from the left of the GIBS block, at [lon]. */
    fun pixelX(lon: Double): Double = ((lon + 180.0) / 360.0 * (1 shl ZOOM) - originCol()) * 256.0

    /** Picture dots from the top of the GIBS block, at [lat]. */
    fun pixelY(lat: Double): Double =
        ((1 - mercatorY(lat) / Math.PI) / 2 * (1 shl ZOOM) - originRow()) * 256.0

    /** The kept part, in dots of the GIBS block: left, top, width, height. */
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

    private fun lonAt(px: Double): Double = (px / 256.0 + originCol()) / (1 shl ZOOM) * 360.0 - 180.0

    private fun latAt(py: Double): Double {
        val n = Math.PI - 2.0 * Math.PI * (py / 256.0 + originRow()) / (1 shl ZOOM)
        return Math.toDegrees(Math.atan(Math.sinh(n)))
    }

    // ---- NICT Himawari Real-time: the newer source, a full-disk geostationary view ----
    //
    // NICT serves Band 13 infrared as a black-and-white cloud mask (white = cloud, black = clear sky), a new
    // frame every 10 minutes and usually only about 25 minutes late, much fresher than GIBS. The picture is
    // not a flat map: it is the whole Earth as the satellite sees it from 140.7 degrees east, so a tile must
    // be bent (reprojected) onto the map to line up with the rain. The maths below (the GEOS projection, from
    // the CGMS/LRIT standard) was checked against the Earth's edge and the Singapore, KL, Kuching and Jakarta
    // coasts on 2026-11-24.

    const val NICT_SUB_LON = 140.7
    /** The full-disk 2 km grid is 5500 wide; its centre and scaling (CGMS LRIT constants). */
    const val NICT_FLDK = 5500.0
    const val NICT_COFF = 2750.5
    const val NICT_CFAC = 20466275.0
    /** NICT draws the disk at level n as an n-by-n grid of 550-dot tiles. Level 8 is about 2.5 km a dot here. */
    const val NICT_LEVEL = 8
    const val NICT_TILE = 550

    private val nictStamp = DateTimeFormatter.ofPattern("yyyy/MM/dd/HHmmss").withZone(ZoneOffset.UTC)

    /** The address of NICT's newest-time note. Its JSON is {"date":"YYYY-MM-DD HH:MM:SS"} in UTC. */
    const val NICT_LATEST_URL = "https://himawari8.nict.go.jp/img/FULL_24h/latest.json"

    /** One NICT Band 13 disk tile. The time and tile indices are joined with underscores. */
    fun nictTileUrl(frame: CloudFrame, level: Int, tx: Int, ty: Int): String =
        "https://himawari8.nict.go.jp/img/FULL_24h/B13/${level}d/$NICT_TILE/" +
            "${nictStamp.format(Instant.ofEpochMilli(frame.timeMillis))}_${tx}_${ty}.png"

    /** NICT's {"date":"2026-11-24 12:10:00"} parsed to epoch millis (UTC), or null when it cannot be read. */
    fun parseNictLatest(json: String): Long? {
        val m = Regex("\"date\"\\s*:\\s*\"([0-9]{4})-([0-9]{2})-([0-9]{2}) ([0-9]{2}):([0-9]{2}):([0-9]{2})\"").find(json) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        return try {
            java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }


    private const val SAT_DIST = 42164.0       // satellite distance from Earth centre, km
    private const val R_POL = 6356.7523        // polar radius, km
    private const val E2 = 0.00669438444       // eccentricity squared
    private const val GEO_RATIO = 0.993305616  // (R_pol/R_eq)^2, geodetic to geocentric latitude

    /**
     * A lat/lon turned into a dot in NICT's whole-disk picture frame (the n-by-n grid as one image), or null
     * when the point is round the back of the Earth. [level] is the zoom (8 by default).
     */
    fun nictFramePixel(lon: Double, lat: Double, level: Int = NICT_LEVEL): DoubleArray? {
        val lonR = Math.toRadians(lon)
        val subR = Math.toRadians(NICT_SUB_LON)
        val cLat = Math.atan(GEO_RATIO * Math.tan(Math.toRadians(lat)))
        val rl = R_POL / Math.sqrt(1 - E2 * Math.cos(cLat) * Math.cos(cLat))
        val r1 = SAT_DIST - rl * Math.cos(cLat) * Math.cos(lonR - subR)
        val r2 = -rl * Math.cos(cLat) * Math.sin(lonR - subR)
        val r3 = rl * Math.sin(cLat)
        // Behind the Earth's edge: not visible.
        if (r1 * (r1 - SAT_DIST) + (r2 * r2 + r3 * r3) > 0) return null
        val rn = Math.sqrt(r1 * r1 + r2 * r2 + r3 * r3)
        val x = Math.atan(-r2 / r1)
        val y = Math.asin(-r3 / rn)
        val col = NICT_COFF + Math.toDegrees(x) * TWO_POW_M16 * NICT_CFAC
        val lin = NICT_COFF + Math.toDegrees(y) * TWO_POW_M16 * NICT_CFAC
        val scale = level * NICT_TILE / NICT_FLDK
        return doubleArrayOf(col * scale, lin * scale)
    }

    private const val TWO_POW_M16 = 1.0 / 65536.0

    /** The whole-disk frame width in dots at [level]: level 8 is 4400. */
    fun nictFrameSize(level: Int = NICT_LEVEL): Int = level * NICT_TILE

    /** The lon/lat at the centre of each dot of the Mercator crop, left-to-right then top-to-bottom. */
    fun cropCellLonLat(): Pair<DoubleArray, DoubleArray> {
        val c = crop()
        val lons = DoubleArray(c[2]) { lonAt(c[0] + it + 0.5) }
        val lats = DoubleArray(c[3]) { latAt(c[1] + it + 0.5) }
        return lons to lats
    }

    /** Which NICT disk tiles (x,y pairs) the crop falls in, at [level]. Two tiles over Singapore. */
    fun nictTilesForCrop(level: Int = NICT_LEVEL): List<Pair<Int, Int>> {
        val (lons, lats) = cropCellLonLat()
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for (lat in doubleArrayOf(lats.first(), lats.last())) {
            for (lon in doubleArrayOf(lons.first(), lons.last())) {
                val p = nictFramePixel(lon, lat, level) ?: continue
                val tx = (p[0] / NICT_TILE).toInt(); val ty = (p[1] / NICT_TILE).toInt()
                minX = minOf(minX, tx); maxX = maxOf(maxX, tx)
                minY = minOf(minY, ty); maxY = maxOf(maxY, ty)
            }
        }
        if (minX == Int.MAX_VALUE) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        for (ty in minY..maxY) for (tx in minX..maxX) out.add(tx to ty)
        return out
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
