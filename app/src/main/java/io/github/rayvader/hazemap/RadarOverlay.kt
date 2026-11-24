package io.github.rayvader.hazemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * Draws one frame of pictures stretched onto the map: the RainViewer radar tiles for one moment, or the
 * cloud picture for one moment (see [CloudLayer]). Frames are swapped by [show]; the caller invalidates the map.
 * [alpha] is the whole layer's see-through: the radar's colours are drawn at 80%, the clouds carry their own.
 */
open class RadarOverlay(alpha: Int = 205) : Overlay() {

    /** One picture and the map edges it covers. Both edges are Mercator-linear, so two corners place it. */
    class Patch(val bitmap: Bitmap, val north: Double, val west: Double, val south: Double, val east: Double)

    /** A radar tile at [TileMath.ZOOM]. */
    class Tile(val x: Int, val y: Int, val bitmap: Bitmap) {
        fun toPatch() = Patch(
            bitmap,
            TileMath.latTop(y), TileMath.lonLeft(x), TileMath.latTop(y + 1), TileMath.lonLeft(x + 1)
        )
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
    private var patches: List<Patch> = emptyList()
    private val topLeft = Point()
    private val bottomRight = Point()
    private val dst = Rect()
    /** Stitched pictures kept per frame's tile list, so swapping frames in playback does not re-stitch. */
    private val stitched = HashMap<List<Tile>, List<Patch>>()

    fun show(tiles: List<Tile>) {
        patches = if (tiles.isEmpty()) emptyList() else stitched.getOrPut(tiles) { stitch(tiles) }
    }

    fun showPatches(next: List<Patch>) {
        patches = next
    }

    /** Drops the stitched pictures (and frees them) when a fresh set of radar frames is loaded. */
    fun clearStitched() {
        stitched.values.flatten().forEach { it.bitmap.recycle() }
        stitched.clear()
    }

    /**
     * Joins the radar tiles of one frame into a single picture, so there is no seam where two tiles meet.
     * Drawing each tile as its own stretched, bitmap-filtered rectangle left a hard line at the join (the
     * edges rounded to pixels on their own and the filter could not read across the gap). The tiles are a
     * neat grid at one zoom, so one bitmap covers them all; its map edges are the outer tile edges.
     */
    private fun stitch(tiles: List<Tile>): List<Patch> {
        if (tiles.isEmpty()) return emptyList()
        if (tiles.size == 1) return listOf(tiles.first().toPatch())
        val minX = tiles.minOf { it.x }
        val maxX = tiles.maxOf { it.x }
        val minY = tiles.minOf { it.y }
        val maxY = tiles.maxOf { it.y }
        val cols = maxX - minX + 1
        val rows = maxY - minY + 1
        val size = TILE_PX
        val combined = Bitmap.createBitmap(cols * size, rows * size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        for (t in tiles) {
            canvas.drawBitmap(t.bitmap, ((t.x - minX) * size).toFloat(), ((t.y - minY) * size).toFloat(), null)
        }
        return listOf(
            Patch(
                combined,
                TileMath.latTop(minY), TileMath.lonLeft(minX),
                TileMath.latTop(maxY + 1), TileMath.lonLeft(maxX + 1)
            )
        )
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        for (p in patches) {
            projection.toPixels(GeoPoint(p.north, p.west), topLeft)
            projection.toPixels(GeoPoint(p.south, p.east), bottomRight)
            dst.set(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
            canvas.drawBitmap(p.bitmap, null, dst, paint)
        }
    }

    private companion object {
        /** RainViewer radar tiles are 256 px (see [TileMath.radarUrl]). */
        const val TILE_PX = 256
    }
}
