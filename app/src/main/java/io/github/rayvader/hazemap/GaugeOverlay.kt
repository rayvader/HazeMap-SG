package io.github.rayvader.hazemap

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * One dot per NEA rain gauge for a single 5-minute frame. Wet gauges are filled by intensity,
 * dry ones are small hollow rings. A tap on a dot reports the gauge and its reading.
 */
class GaugeOverlay(
    private val density: Float,
    night: Boolean,
    private val onTap: (Station, Double) -> Unit
) : Overlay() {

    private var stations: List<Station> = emptyList()
    private var frame: GaugeFrame? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 1.5f * density
    }
    private val dryRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = if (night) 0xFFB8BCC4.toInt() else 0xFF6B6B72.toInt()
        strokeWidth = 1f * density
    }
    private val point = Point()

    fun show(stations: List<Station>, frame: GaugeFrame?) {
        this.stations = stations
        this.frame = frame
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val f = frame ?: return
        // Dry gauges first so wet ones are never hidden under them.
        for (pass in 0..1) {
            for (s in stations) {
                val mm = f.mm[s.id] ?: continue // this gauge did not report
                val level = RainLevel.forFiveMinutes(mm)
                if ((level == RainLevel.DRY) != (pass == 0)) continue
                projection.toPixels(GeoPoint(s.lat, s.lon), point)
                val x = point.x.toFloat()
                val y = point.y.toFloat()
                if (level == RainLevel.DRY) {
                    canvas.drawCircle(x, y, 2.5f * density, dryRing)
                } else {
                    fill.color = RainPalette.gauge(level)
                    canvas.drawCircle(x, y, 5.5f * density, fill)
                    canvas.drawCircle(x, y, 5.5f * density, ring)
                }
            }
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val f = frame ?: return false
        val projection = mapView.projection
        var best: Station? = null
        var bestDistance = sq(28.0 * density)
        for (s in stations) {
            if (!f.mm.containsKey(s.id)) continue
            projection.toPixels(GeoPoint(s.lat, s.lon), point)
            val d = sq((point.x - e.x).toDouble()) + sq((point.y - e.y).toDouble())
            if (d < bestDistance) {
                bestDistance = d
                best = s
            }
        }
        val hit = best ?: return false
        onTap(hit, f.mm[hit.id] ?: 0.0)
        return true
    }
}
