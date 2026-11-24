package io.github.rayvader.hazemap

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException

/**
 * Loads the cloud layer for the Rain tab: the last two hours of Himawari infrared, cut to Singapore and made
 * see-through (see [CloudLogic]). Two sources: NICT (newer, bent onto the map) first, NASA GIBS as the
 * fallback when NICT fails or is no newer. A picture never changes once published, so each tile is kept on
 * the phone and fetched only once.
 */
class CloudLayer(dir: File, private val dark: Boolean, private val failForTest: Boolean = false) {

    /** One loaded cloud frame: its time and its picture on the map. */
    class Loaded(val frame: CloudFrame, val patch: RadarOverlay.Patch, val cover: Float)

    /** The frames plus which source they came from, so the Rain tab can name it. */
    class Result(val frames: List<Loaded>, val source: CloudSourceId)

    private val cache = RadarCache(dir)
    private val nict = NictCloudSource(cache)
    private val gibs = GibsCloudSource(cache)

    /**
     * The cloud frames and their source. Throws when neither source can give one picture: the Rain tab then
     * says clouds are unavailable, and keeps the rain.
     */
    suspend fun load(nowMillis: Long = System.currentTimeMillis()): Result {
        if (failForTest) throw IOException("cloud source turned off by the test")

        val nictNewest = try { nict.newest(nowMillis) } catch (e: Exception) { null }
        val gibsNewest = try { gibs.newest(nowMillis) } catch (e: Exception) { null }

        // Prefer NICT unless GIBS is actually newer (NICT is usually fresher). Fall back either way.
        val order = pickOrder(nictNewest, gibsNewest)
        var lastError: Exception? = null
        for (choice in order) {
            val (source, newest) = choice
            if (newest == null) continue
            try {
                val frames = loadFrom(source, newest)
                if (frames.isNotEmpty()) {
                    Log.i(TAG, "clouds from ${source.id.label}: ${frames.size} frames, newest ${TimeFmt.clock(newest.timeMillis)}")
                    return Result(frames, source.id)
                }
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "clouds from ${source.id.label} failed", e)
            }
        }
        throw lastError ?: IOException("no cloud picture from NICT or GIBS")
    }

    /** NICT first, then GIBS, unless GIBS's newest is more than one step fresher than NICT's. */
    private fun pickOrder(
        nictNewest: CloudFrame?,
        gibsNewest: CloudFrame?
    ): List<Pair<CloudSource, CloudFrame?>> {
        val nictFirst = when {
            nictNewest == null -> false
            gibsNewest == null -> true
            else -> gibsNewest.timeMillis - nictNewest.timeMillis < CloudLogic.STEP_MS
        }
        return if (nictFirst) listOf(nict to nictNewest, gibs to gibsNewest)
        else listOf(gibs to gibsNewest, nict to nictNewest)
    }

    private suspend fun loadFrom(source: CloudSource, newest: CloudFrame): List<Loaded> {
        val frames = CloudLogic.framesEndingAt(newest)
        val gate = Semaphore(4)
        val loaded = coroutineScope {
            frames.map { f -> async { gate.withPermit { loadFrame(source, f) } } }.awaitAll()
        }.filterNotNull()
        if (loaded.isNotEmpty()) {
            // Drop tiles older than the last two hours.
            val keep = when (source) {
                is NictCloudSource -> source.tileUrls(frames)
                is GibsCloudSource -> source.tileUrls(frames)
                else -> emptyList()
            }
            if (keep.isNotEmpty()) cache.keepOnly(keep)
        }
        return loaded
    }

    private suspend fun loadFrame(source: CloudSource, frame: CloudFrame): Loaded? {
        val cropPixels = source.cropPixels(frame) ?: return null
        return withContext(Dispatchers.Default) { build(source, frame, cropPixels) }
    }

    /** The crop grid, upscaled smoothly then made see-through: soft cloud edges rather than hard dots. */
    private fun build(source: CloudSource, frame: CloudFrame, cropPixels: IntArray): Loaded {
        val w = source.cropW
        val h = source.cropH
        val small = Bitmap.createBitmap(cropPixels, w, h, Bitmap.Config.ARGB_8888)
        val big = Bitmap.createScaledBitmap(small, w * CloudLogic.UPSCALE, h * CloudLogic.UPSCALE, true)
        if (small !== big) small.recycle()
        val pixels = IntArray(big.width * big.height)
        big.getPixels(pixels, 0, big.width, 0, 0, big.width, big.height)
        big.recycle()
        CloudLogic.toClouds(pixels, dark)
        val out = Bitmap.createBitmap(pixels, big.width, big.height, Bitmap.Config.ARGB_8888)
        val e = CloudLogic.cropBounds()
        return Loaded(frame, RadarOverlay.Patch(out, e[0], e[1], e[2], e[3]), CloudLogic.cover(pixels))
    }

    private companion object {
        const val TAG = "CloudLayer"
    }
}
