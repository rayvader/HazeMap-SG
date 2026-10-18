package io.github.rayvader.hazemap

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import io.github.rayvader.hazemap.databinding.ItemHazeRowBinding
import io.github.rayvader.hazemap.databinding.ScreenHazeBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ViewMode { ZONES, DOTS }

/**
 * The "Haze" tab: PSI and PM2.5 for NEA's five regions as zones or dots on a map, a ranked list,
 * and a stored history chart for each region.
 */
class HazeScreen(
    private val activity: AppCompatActivity,
    private val b: ScreenHazeBinding
) {
    private val repo = HazeRepository(activity)
    private val density = activity.resources.displayMetrics.density

    private var metric: Metric = Metric.PSI
    private var viewMode: ViewMode = ViewMode.ZONES
    // Both metrics are loaded together; the toggle only picks which one colours the map.
    private var sets: Map<Metric, ReadingSet> = emptyMap()
    private var loadedOnce = false
    private val copyright = CopyrightOverlay(activity).apply { setTextSize(10) }

    init {
        setupMap()
        setupToggles()
        buildLegend()
        b.hazeSwipe.setOnRefreshListener { load(force = true) }
        b.viewToggle.check(b.btnZones.id)
        b.metricToggle.check(b.btnPsi.id)
    }

    fun setVisible(show: Boolean) {
        b.root.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun onShown() {
        if (!loadedOnce) {
            loadedOnce = true
            load(force = false)
        }
    }

    fun mapResume() = b.hazeMap.onResume()

    fun mapPause() = b.hazeMap.onPause()

    fun destroy() = b.hazeMap.onDetach()

    // ---- Setup ----

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        val map = b.hazeMap
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setTilesScaledToDpi(true)
        map.setMinZoomLevel(8.0)
        map.setMaxZoomLevel(15.0)
        map.controller.setZoom(10.0)
        map.controller.setCenter(GeoPoint(1.3521, 103.8198)) // Singapore
        // The map sits inside a scrolling page: while a finger is on it, the map pans and the page stays put.
        map.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> v.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun setupToggles() {
        b.viewToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewMode = if (checkedId == b.btnZones.id) ViewMode.ZONES else ViewMode.DOTS
            drawMap()
        }
        b.metricToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            metric = if (checkedId == b.btnPsi.id) Metric.PSI else Metric.PM25
            // Both numbers are already loaded; just recolour.
            drawMap()
        }
    }

    // ---- Loading ----

    fun load(force: Boolean) {
        activity.lifecycleScope.launch {
            b.hazeSwipe.isRefreshing = true
            try {
                if (sets.isEmpty()) {
                    val cached = withContext(Dispatchers.IO) { repo.peekBoth() }
                    if (cached != null) show(cached)
                }
                show(repo.refreshBoth(force))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sets.isEmpty()) Toast.makeText(activity, R.string.error_fetch, Toast.LENGTH_SHORT).show()
            } finally {
                b.hazeSwipe.isRefreshing = false
            }
        }
    }

    private fun show(loaded: Map<Metric, Loaded<ReadingSet>>) {
        sets = loaded.mapValues { it.value.value }
        val stamp = (sets[metric] ?: sets.values.firstOrNull())?.timestampMillis
        b.updatedLabel.text = if (stamp == null) {
            ""
        } else if (loaded.values.any { it.stale }) {
            activity.getString(R.string.saved_data, TimeFmt.clock(stamp))
        } else {
            activity.getString(R.string.updated_at, TimeFmt.clock(stamp))
        }
        drawMap()
        drawList()
    }

    // ---- List ----

    private fun drawList() {
        b.hazeList.removeAllViews()
        val psi = sets[Metric.PSI]?.values
        val pm = sets[Metric.PM25]?.values
        if (psi != null) {
            for ((key, value) in psi.entries.sortedByDescending { it.value }) {
                val region = Region.fromKey(key) ?: continue
                val band = Band.forPsi(value)
                val row = ItemHazeRowBinding.inflate(activity.layoutInflater, b.hazeList, false)
                row.rowBadge.text = value.toString()
                row.rowBadge.setBadge(band.color, band.textColor, density)
                row.rowName.text = region.label
                row.rowBand.text = band.label
                row.rowPm.text = pm?.get(key)?.let { activity.getString(R.string.pm_value, it) }.orEmpty()
                row.root.setOnClickListener { showHistory(region) }
                b.hazeList.addView(row.root)
            }
        }
        b.hazeListCard.visibility = if (b.hazeList.childCount == 0) View.GONE else View.VISIBLE
    }

    // ---- Map drawing ----

    private fun drawMap() {
        if (sets.isEmpty()) return
        val colorSet = sets[metric] ?: return
        b.hazeMap.overlays.clear()
        for (region in Region.entries) {
            // The toggled metric drives the colour/band.
            val colorValue = colorSet.values[region.key] ?: continue
            val band = Band.forMetric(metric, colorValue)

            val psi = sets[Metric.PSI]?.values?.get(region.key)
            val pm25 = sets[Metric.PM25]?.values?.get(region.key)
            val titleText = "${region.label} — PSI ${psi ?: "-"}, PM2.5 ${pm25 ?: "-"} (${band.label})"

            val overlay: Overlay = if (viewMode == ViewMode.ZONES) {
                zoneOverlay(region, band, titleText)
            } else {
                dotOverlay(region, band, titleText)
            }
            b.hazeMap.overlays.add(overlay)
            // Always-on label showing BOTH numbers on top of the zone/dot.
            b.hazeMap.overlays.add(labelOverlay(region, psi, pm25))
        }
        b.hazeMap.overlays.add(copyright)
        b.hazeMap.invalidate()
    }

    /** A non-tappable text marker showing both readings at the region point. */
    private fun labelOverlay(region: Region, psi: Int?, pm25: Int?): Overlay {
        val lines = listOf("PSI ${psi ?: "-"}", "PM ${pm25 ?: "-"}")
        return Marker(b.hazeMap).apply {
            position = GeoPoint(region.lat, region.lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = BitmapDrawable(activity.resources, numberBitmap(lines))
            setInfoWindow(null)
            isDraggable = false
            // Let taps fall through to the polygon (history) underneath.
            setOnMarkerClickListener { _, _ -> false }
        }
    }

    /** Draws the text lines as white text with a dark outline, on a transparent bitmap. */
    private fun numberBitmap(lines: List<String>): Bitmap {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * density // sized in dp so it reads the same on every screen
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val strokePaint = Paint(textPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Color.parseColor("#CC000000")
        }
        val pad = (3 * density).toInt()
        val fm = textPaint.fontMetrics
        val lineHeight = fm.bottom - fm.top
        val w = (lines.maxOf { textPaint.measureText(it) } + pad * 2).toInt().coerceAtLeast(1)
        val h = (lineHeight * lines.size + pad * 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val x = w / 2f
        lines.forEachIndexed { i, line ->
            val y = pad - fm.top + i * lineHeight
            canvas.drawText(line, x, y, strokePaint)
            canvas.drawText(line, x, y, textPaint)
        }
        return bmp
    }

    /** A translucent square zone centred on the region label point. */
    private fun zoneOverlay(region: Region, band: Band, titleText: String): Overlay {
        // North, Central and South are only 0.061 degrees apart, so the zones are wide rather than tall.
        val dLat = 0.029
        val dLon = 0.05
        val poly = Polygon().apply {
            points = listOf(
                GeoPoint(region.lat + dLat, region.lon - dLon),
                GeoPoint(region.lat + dLat, region.lon + dLon),
                GeoPoint(region.lat - dLat, region.lon + dLon),
                GeoPoint(region.lat - dLat, region.lon - dLon)
            )
            fillPaint.color = (0x66 shl 24) or (band.color and 0x00FFFFFF)
            outlinePaint.color = band.color
            outlinePaint.strokeWidth = 3f
            title = titleText
        }
        poly.setOnClickListener { _, _, _ ->
            showHistory(region)
            true
        }
        return poly
    }

    /** A filled circle on the region point. */
    private fun dotOverlay(region: Region, band: Band, titleText: String): Overlay {
        val center = GeoPoint(region.lat, region.lon)
        val circle = Polygon().apply {
            points = Polygon.pointsAsCircle(center, 3000.0) // 3 km radius
            fillPaint.color = (0xCC shl 24) or (band.color and 0x00FFFFFF)
            outlinePaint.color = Color.WHITE
            outlinePaint.strokeWidth = 3f
            title = titleText
        }
        circle.setOnClickListener { _, _, _ ->
            showHistory(region)
            true
        }
        return circle
    }

    // ---- Legend ----

    private fun buildLegend() {
        b.legend.removeAllViews()
        for (band in Band.entries) {
            val chip = TextView(activity).apply {
                text = "  ${band.label}  "
                setTextColor(band.textColor)
                setBackgroundColor(band.color)
                textSize = 10f
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(4, 0, 4, 0)
                layoutParams = lp
                setPadding(8, 4, 8, 4)
            }
            b.legend.addView(chip)
        }
    }

    // ---- History chart ----

    private fun showHistory(region: Region) {
        activity.lifecycleScope.launch {
            val rows = repo.history(region, metric)
            val view = activity.layoutInflater.inflate(R.layout.dialog_history, null)
            val title = view.findViewById<TextView>(R.id.historyTitle)
            val chart = view.findViewById<LineChart>(R.id.chart)
            val empty = view.findViewById<TextView>(R.id.historyEmpty)

            title.text = "${region.label} — ${metric.display} ${activity.getString(R.string.history_title)}"

            if (rows.size < 2) {
                chart.visibility = View.GONE
                empty.visibility = View.VISIBLE
            } else {
                renderChart(chart, rows)
            }

            AlertDialog.Builder(activity)
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun renderChart(chart: LineChart, rows: List<ReadingEntity>) {
        val entries = rows.mapIndexed { i, r -> Entry(i.toFloat(), r.value.toFloat()) }
        val set = LineDataSet(entries, metric.display).apply {
            color = Color.parseColor("#00796B")
            setCircleColor(Color.parseColor("#00796B"))
            lineWidth = 2f
            circleRadius = 3f
            setDrawValues(false)
        }
        chart.data = LineData(set)
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        val labelFmt = SimpleDateFormat("d/M HH:mm", Locale.getDefault())
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt().coerceIn(0, rows.lastIndex)
                return labelFmt.format(Date(rows[idx].timestampMillis))
            }
        }
        chart.xAxis.granularity = 1f
        chart.xAxis.labelRotationAngle = -45f
        chart.invalidate()
    }
}
