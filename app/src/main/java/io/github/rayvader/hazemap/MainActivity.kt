package io.github.rayvader.hazemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
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
import io.github.rayvader.hazemap.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ViewMode { ZONES, DOTS }

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: HazeRepository

    private var metric: Metric = Metric.PSI
    private var viewMode: ViewMode = ViewMode.ZONES
    // Both metrics are fetched each refresh; the toggle only picks which colors the map.
    private var sets: Map<Metric, ReadingSet> = emptyMap()

    private val timeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid needs a user-agent and cache path set before any MapView inflates.
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = HazeRepository(this)

        setupMap()
        setupToggles()
        buildLegend()

        binding.swipe.setOnRefreshListener { refresh() }
        binding.fabRefresh.setOnClickListener { refresh() }

        // Default selection.
        binding.viewToggle.check(binding.btnZones.id)
        binding.metricToggle.check(binding.btnPsi.id)

        refresh()
    }

    private fun setupMap() {
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(11.0)
        binding.map.controller.setCenter(GeoPoint(1.3521, 103.8198)) // Singapore
    }

    private fun setupToggles() {
        binding.viewToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewMode = if (checkedId == binding.btnZones.id) ViewMode.ZONES else ViewMode.DOTS
            drawMap()
        }
        binding.metricToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            metric = if (checkedId == binding.btnPsi.id) Metric.PSI else Metric.PM25
            buildLegend()
            // Both numbers are already loaded; just recolor the map.
            drawMap()
        }
    }

    private fun refresh() {
        binding.swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { repo.refreshBoth() }
                sets = loaded
                val stamp = loaded[metric]?.timestampMillis ?: loaded.values.firstOrNull()?.timestampMillis
                if (stamp != null) {
                    binding.updatedLabel.text =
                        getString(R.string.updated_prefix) + timeFmt.format(Date(stamp))
                }
                drawMap()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.error_fetch, Toast.LENGTH_SHORT).show()
            } finally {
                binding.swipe.isRefreshing = false
            }
        }
    }

    // ---- Map drawing ----

    private fun drawMap() {
        if (sets.isEmpty()) return
        val colorSet = sets[metric] ?: return
        binding.map.overlays.clear()
        for (region in Region.entries) {
            // The toggled metric drives the color/band.
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
            binding.map.overlays.add(overlay)
            // Always-on label showing BOTH numbers on top of the zone/dot.
            binding.map.overlays.add(labelOverlay(region, psi, pm25))
        }
        binding.map.invalidate()
    }

    /** A non-tappable text marker showing both readings at the region point. */
    private fun labelOverlay(region: Region, psi: Int?, pm25: Int?): Overlay {
        val lines = listOf("PSI ${psi ?: "-"}", "PM ${pm25 ?: "-"}")
        val marker = Marker(binding.map).apply {
            position = GeoPoint(region.lat, region.lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = BitmapDrawable(resources, numberBitmap(lines))
            setInfoWindow(null)
            isDraggable = false
            // Let taps fall through to the polygon (history) underneath.
            setOnMarkerClickListener { _, _ -> false }
        }
        return marker
    }

    /** Draws the text lines as white text with a dark outline, on a transparent bitmap. */
    private fun numberBitmap(lines: List<String>): Bitmap {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val strokePaint = Paint(textPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.parseColor("#CC000000")
        }
        val pad = 8
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

    /** A translucent square zone centered on the region label point. */
    private fun zoneOverlay(region: Region, band: Band, titleText: String): Overlay {
        val d = 0.045 // ~5km half-side in degrees
        val poly = Polygon().apply {
            points = listOf(
                GeoPoint(region.lat + d, region.lon - d),
                GeoPoint(region.lat + d, region.lon + d),
                GeoPoint(region.lat - d, region.lon + d),
                GeoPoint(region.lat - d, region.lon - d)
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

    /** A filled circle marker on the region point. */
    private fun dotOverlay(region: Region, band: Band, titleText: String): Overlay {
        val center = GeoPoint(region.lat, region.lon)
        val circle = Polygon().apply {
            points = Polygon.pointsAsCircle(center, 2500.0) // 2.5km radius
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
        binding.legend.removeAllViews()
        for (band in Band.entries) {
            val chip = TextView(this).apply {
                text = "  ${band.label}  "
                setTextColor(Color.WHITE)
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
            binding.legend.addView(chip)
        }
    }

    // ---- History chart ----

    private fun showHistory(region: Region) {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { repo.history(region, metric) }
            val view = LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.dialog_history, null)
            val title = view.findViewById<TextView>(R.id.historyTitle)
            val chart = view.findViewById<LineChart>(R.id.chart)
            val empty = view.findViewById<TextView>(R.id.historyEmpty)

            title.text = "${region.label} — ${metric.display} ${getString(R.string.history_title)}"

            if (rows.size < 2) {
                chart.visibility = android.view.View.GONE
                empty.visibility = android.view.View.VISIBLE
            } else {
                renderChart(chart, rows)
            }

            AlertDialog.Builder(this@MainActivity)
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

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
    }
}
