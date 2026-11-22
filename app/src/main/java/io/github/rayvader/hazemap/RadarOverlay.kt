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

    fun show(tiles: List<Tile>) {
        patches = tiles.map { it.toPatch() }
    }

    fun showPatches(next: List<Patch>) {
        patches = next
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        for (p in patches) {
            projection.toPixels(GeoPoint(p.north, p.west), topLeft)
            projection.toPixels(GeoPoint(p.south, p.east), bottomRight)
            dst.set(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
            canvas.drawBitmap(p.bitmap, null, dst, paint)
        }
    }
}
