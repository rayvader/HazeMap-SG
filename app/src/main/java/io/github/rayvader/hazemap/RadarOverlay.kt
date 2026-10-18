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
 * Draws one radar frame: the RainViewer tiles for that moment, stretched onto the map.
 * Frames are swapped by [show]; the caller invalidates the map.
 */
class RadarOverlay : Overlay() {

    class Tile(val x: Int, val y: Int, val bitmap: Bitmap)

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 205 }
    private var tiles: List<Tile> = emptyList()
    private val topLeft = Point()
    private val bottomRight = Point()
    private val dst = Rect()

    fun show(tiles: List<Tile>) {
        this.tiles = tiles
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        for (t in tiles) {
            // Both edges are Mercator-linear, so two corners place the whole tile.
            projection.toPixels(GeoPoint(TileMath.latTop(t.y), TileMath.lonLeft(t.x)), topLeft)
            projection.toPixels(GeoPoint(TileMath.latTop(t.y + 1), TileMath.lonLeft(t.x + 1)), bottomRight)
            dst.set(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
            canvas.drawBitmap(t.bitmap, null, dst, paint)
        }
    }
}
