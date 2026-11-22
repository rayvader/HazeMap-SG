package io.github.rayvader.hazemap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Loads the cloud layer for the Rain tab: the newest Himawari infrared pictures from NASA GIBS for the last
 * two hours, cut to Singapore and made see-through (see [CloudLogic]). A picture never changes once it is
 * published, so each one is kept on the phone and fetched only once.
 */
class CloudLayer(dir: File, private val dark: Boolean, private val failForTest: Boolean = false) {

    /** One loaded cloud frame: its time and its picture on the map. */
    class Loaded(val frame: CloudFrame, val patch: RadarOverlay.Patch, val cover: Float)

    private val cache = RadarCache(dir)

    /**
     * The cloud frames, oldest first. Throws when not one picture could be had, saved or fetched: the
     * Rain tab then says clouds are unavailable, and keeps the rain.
     */
    suspend fun load(nowMillis: Long = System.currentTimeMillis()): List<Loaded> {
        if (failForTest) throw IOException("cloud source turned off by the test")
        val newest = findNewest(nowMillis) ?: throw IOException("no cloud picture in the last two hours")
        val frames = CloudLogic.framesEndingAt(newest)
        val gate = Semaphore(4)
        val loaded = coroutineScope {
            frames.map { f -> async { gate.withPermit { loadFrame(f) } } }.awaitAll()
        }.filterNotNull()
        if (loaded.isEmpty()) throw IOException("no cloud pictures")
        // Pictures older than the last two hours are no longer needed.
        cache.keepOnly(frames.flatMap { f -> CloudLogic.TILE_Y.map { y -> CloudLogic.tileUrl(f, y) } })
        Log.d(TAG, "clouds ready: ${loaded.size} frames, newest ${TimeFmt.clock(newest.timeMillis)}")
        return loaded
    }

    /**
     * The newest picture there is, found by asking for one time after another, newest first, rather than
     * downloading GIBS's 5.8 MB list of every layer. A saved picture counts, so this works offline too.
     */
    private suspend fun findNewest(nowMillis: Long): CloudFrame? {
        var failures = 0
        for (candidate in CloudLogic.newestCandidates(nowMillis)) {
            val url = CloudLogic.tileUrl(candidate, CloudLogic.TILE_Y.first())
            if (withContext(Dispatchers.IO) { cache.read(url) } != null) return candidate
            try {
                val bytes = Http.getBytesOrMissing(url) ?: continue
                withContext(Dispatchers.IO) { cache.write(url, bytes) }
                return candidate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No network: two failures in a row are enough to know.
                Log.w(TAG, "cloud lookup failed at ${TimeFmt.clock(candidate.timeMillis)}", e)
                if (++failures >= 2) throw e
            }
        }
        return null
    }

    /** Both tiles of one frame, stacked, cut and made see-through; null when a tile is missing. */
    private suspend fun loadFrame(frame: CloudFrame): Loaded? {
        val tiles = ArrayList<Bitmap>()
        for (y in CloudLogic.TILE_Y) {
            val bytes = tileBytes(CloudLogic.tileUrl(frame, y)) ?: return null
            val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: return null
            tiles += bitmap
        }
        return withContext(Dispatchers.Default) { build(frame, tiles) }
    }

    private suspend fun tileBytes(url: String): ByteArray? {
        withContext(Dispatchers.IO) { cache.read(url) }?.let { return it }
        return try {
            val bytes = Http.getBytesOrMissing(url) ?: return null
            withContext(Dispatchers.IO) { cache.write(url, bytes) }
            bytes
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cloud tile failed: $url", e)
            null
        }
    }

    private fun build(frame: CloudFrame, tiles: List<Bitmap>): Loaded {
        val stacked = Bitmap.createBitmap(256, 256 * tiles.size, Bitmap.Config.ARGB_8888)
        Canvas(stacked).apply { tiles.forEachIndexed { i, t -> drawBitmap(t, 0f, 256f * i, null) } }
        tiles.forEach { it.recycle() }
        val c = CloudLogic.crop()
        val cut = Bitmap.createBitmap(stacked, c[0], c[1], c[2], c[3])
        // Bigger first, smoothly, then see-through: the cloud edges come out soft rather than in 2.4 km steps.
        val big = Bitmap.createScaledBitmap(cut, c[2] * CloudLogic.UPSCALE, c[3] * CloudLogic.UPSCALE, true)
        if (cut !== big) cut.recycle()
        if (stacked !== cut && stacked !== big) stacked.recycle()
        val pixels = IntArray(big.width * big.height)
        big.getPixels(pixels, 0, big.width, 0, 0, big.width, big.height)
        CloudLogic.toClouds(pixels, dark)
        val out = Bitmap.createBitmap(pixels, big.width, big.height, Bitmap.Config.ARGB_8888)
        big.recycle()
        val e = CloudLogic.cropBounds()
        return Loaded(frame, RadarOverlay.Patch(out, e[0], e[1], e[2], e[3]), CloudLogic.cover(pixels))
    }

    private companion object {
        const val TAG = "CloudLayer"
    }
}
