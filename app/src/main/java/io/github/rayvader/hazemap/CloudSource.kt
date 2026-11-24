package io.github.rayvader.hazemap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Where the cloud pictures come from. Two of them: NICT (newer, a full-disk view that must be bent onto the
 * map) and NASA GIBS (older but already map-shaped). The Rain tab tries NICT first and falls back to GIBS.
 *
 * A source turns a frame time into a map-shaped picture of the Mercator crop ([CloudLogic.crop]): an ARGB
 * grid, [cropW] by [cropH], warm/clear dark and cloud light, ready for [CloudLogic.toClouds]. It also knows
 * the newest frame it can offer, so the Rain tab can pick the fresher source.
 */
interface CloudSource {
    val id: CloudSourceId

    /** The newest frame time this source has now (epoch millis, UTC), or null when it cannot be reached. */
    suspend fun newest(nowMillis: Long): CloudFrame?

    /** The crop as an ARGB grid for [frame], or null when a needed tile is missing. */
    suspend fun cropPixels(frame: CloudFrame): IntArray?

    val cropW: Int
    val cropH: Int
}

/**
 * NASA GIBS: Web-Mercator tiles that already line up with the map. The crop is cut straight out of the two
 * stacked zoom-6 tiles (see the pre-NICT [CloudLayer] path, kept here as the fallback).
 */
class GibsCloudSource(private val cache: RadarCache) : CloudSource {
    override val id = CloudSourceId.GIBS
    private val c = CloudLogic.crop()
    override val cropW = c[2]
    override val cropH = c[3]

    override suspend fun newest(nowMillis: Long): CloudFrame? {
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
                Log.w(TAG, "GIBS lookup failed at ${TimeFmt.clock(candidate.timeMillis)}", e)
                if (++failures >= 2) throw e
            }
        }
        return null
    }

    override suspend fun cropPixels(frame: CloudFrame): IntArray? {
        val tiles = ArrayList<Bitmap>()
        for (y in CloudLogic.TILE_Y) {
            val bytes = tileBytes(CloudLogic.tileUrl(frame, y)) ?: return null
            val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: return null
            tiles += bmp
        }
        return withContext(Dispatchers.Default) {
            val full = IntArray(256 * 256 * tiles.size)
            tiles.forEachIndexed { i, t ->
                t.getPixels(full, i * 256 * 256, 256, 0, 0, 256, 256)
                t.recycle()
            }
            // Cut the crop rectangle out of the stacked 256-wide pair.
            val out = IntArray(cropW * cropH)
            for (row in 0 until cropH) {
                val srcRow = c[1] + row
                System.arraycopy(full, srcRow * 256 + c[0], out, row * cropW, cropW)
            }
            out
        }
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
            Log.w(TAG, "GIBS tile failed: $url", e)
            null
        }
    }

    /** The GIBS tiles this source keeps for a set of frames, so the cache can drop the rest. */
    fun tileUrls(frames: List<CloudFrame>): List<String> =
        frames.flatMap { f -> CloudLogic.TILE_Y.map { y -> CloudLogic.tileUrl(f, y) } }

    private companion object {
        const val TAG = "GibsCloudSource"
    }
}

/**
 * NICT Himawari Real-time: a black-and-white cloud mask of the whole disk. For each frame the two disk tiles
 * over Singapore are fetched, then every dot of the Mercator crop is read back from them through the GEOS
 * projection ([CloudLogic.nictFramePixel]), so the clouds land where the rain does. Credit: NICT / JMA.
 */
class NictCloudSource(private val cache: RadarCache) : CloudSource {
    override val id = CloudSourceId.NICT
    private val level = CloudLogic.NICT_LEVEL
    private val c = CloudLogic.crop()
    override val cropW = c[2]
    override val cropH = c[3]

    override suspend fun newest(nowMillis: Long): CloudFrame? {
        val json = try {
            Http.getText(CloudLogic.NICT_LATEST_URL, limited = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "NICT latest.json failed", e)
            return null
        }
        val millis = CloudLogic.parseNictLatest(json) ?: return null
        // Snap to the app's 10-minute grid, never into the future.
        return CloudFrame(CloudLogic.floorToStep(minOf(millis, nowMillis)))
    }

    override suspend fun cropPixels(frame: CloudFrame): IntArray? {
        val tiles = HashMap<Pair<Int, Int>, IntArray>()
        for ((tx, ty) in CloudLogic.nictTilesForCrop(level)) {
            val bytes = tileBytes(CloudLogic.nictTileUrl(frame, level, tx, ty)) ?: return null
            val px = withContext(Dispatchers.Default) {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                val arr = IntArray(CloudLogic.NICT_TILE * CloudLogic.NICT_TILE)
                bmp.getPixels(arr, 0, CloudLogic.NICT_TILE, 0, 0, CloudLogic.NICT_TILE, CloudLogic.NICT_TILE)
                bmp.recycle()
                arr
            } ?: return null
            tiles[tx to ty] = px
        }
        if (tiles.isEmpty()) return null
        return withContext(Dispatchers.Default) { reproject(tiles) }
    }

    /** Read each crop dot back from the disk tiles through the GEOS maths. */
    private fun reproject(tiles: Map<Pair<Int, Int>, IntArray>): IntArray {
        val (lons, lats) = CloudLogic.cropCellLonLat()
        val t = CloudLogic.NICT_TILE
        val out = IntArray(cropW * cropH)
        for (row in 0 until cropH) {
            val lat = lats[row]
            val base = row * cropW
            for (col in 0 until cropW) {
                val p = CloudLogic.nictFramePixel(lons[col], lat, level) ?: continue
                val tx = (p[0] / t).toInt(); val ty = (p[1] / t).toInt()
                val tile = tiles[tx to ty] ?: continue
                val ix = (p[0] - tx * t).toInt(); val iy = (p[1] - ty * t).toInt()
                if (ix in 0 until t && iy in 0 until t) out[base + col] = tile[iy * t + ix]
            }
        }
        return out
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
            Log.w(TAG, "NICT tile failed: $url", e)
            null
        }
    }

    fun tileUrls(frames: List<CloudFrame>): List<String> =
        frames.flatMap { f -> CloudLogic.nictTilesForCrop(level).map { (tx, ty) -> CloudLogic.nictTileUrl(f, level, tx, ty) } }

    private companion object {
        const val TAG = "NictCloudSource"
    }
}
