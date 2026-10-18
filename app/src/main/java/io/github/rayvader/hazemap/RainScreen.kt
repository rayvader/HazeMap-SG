package io.github.rayvader.hazemap

import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.rayvader.hazemap.databinding.ScreenRainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.CopyrightOverlay
import java.util.Locale

enum class RainSource(val key: String) { RADAR("radar"), GAUGES("gauges") }

/**
 * The "Rain" tab: a map of where it is raining, with the past two hours to scrub or play back.
 * RADAR shows RainViewer's radar images (10-minute steps); GAUGES shows NEA's 90 rain gauges (5-minute steps).
 */
class RainScreen(
    private val activity: AppCompatActivity,
    private val b: ScreenRainBinding,
    private val prefs: SharedPreferences,
    state: Bundle?
) {
    private val repo = WeatherRepository.get(activity)
    private val density = activity.resources.displayMetrics.density
    private val night = activity.isNightMode()
    private val scope get() = activity.lifecycleScope

    private var source = RainSource.RADAR
    private var radarFrames: List<RadarFrame> = emptyList()
    private var radarTiles: List<List<RadarOverlay.Tile>> = emptyList()
    private var gauge: GaugeData? = null

    private var index = 0
    private var playing = false
    private var visible = false
    private var playJob: Job? = null
    private var radarJob: Job? = null
    private var gaugeJob: Job? = null
    /** True after the user paused or scrubbed; the loop then stays still until they press Play or refresh. */
    private var userPaused = false
    private val statuses = HashMap<RainSource, String?>()

    private val radarOverlay = RadarOverlay()
    private val gaugeOverlay = GaugeOverlay(density, night) { station, mm ->
        val text = activity.getString(R.string.gauge_reading, station.name, String.format(Locale.US, "%.1f", mm))
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    init {
        val wanted = activity.intent?.getStringExtra(MainActivity.EXTRA_SOURCE) ?: prefs.getString(KEY_SOURCE, null)
        source = RainSource.entries.firstOrNull { it.key == wanted } ?: RainSource.RADAR
        setupMap(state)
        setupControls()
        applySource()
    }

    // ---- Lifecycle ----

    fun setVisible(show: Boolean) {
        b.root.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun onShown() {
        visible = true
        ensureLoaded(force = false)
        maybeAutoplay()
    }

    fun onHidden() {
        visible = false
        stopPlay()
    }

    fun refresh() {
        userPaused = false
        stopPlay()
        ensureLoaded(force = true)
    }

    /** Keeps where the user has panned and zoomed, for when the activity is recreated. */
    fun saveMap(out: Bundle) {
        val map = b.rainMap
        if (map.width == 0) return // never shown, so the user has not moved it
        val centre = map.mapCenter
        out.putDoubleArray(KEY_VIEW, doubleArrayOf(map.zoomLevelDouble, centre.latitude, centre.longitude))
    }

    fun mapResume() = b.rainMap.onResume()

    fun mapPause() = b.rainMap.onPause()

    fun destroy() {
        playJob?.cancel()
        radarJob?.cancel()
        gaugeJob?.cancel()
        b.rainMap.onDetach()
    }

    // ---- Setup ----

    private fun setupMap(state: Bundle?) {
        val map = b.rainMap
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setTilesScaledToDpi(true)
        // The radar tiles cover lon 101.25 to 106.9 and lat 0 to 2.8. At zoom 8.5 a phone screen centred on
        // Singapore still sits inside that strip; zooming out further would show the tiles' hard edge.
        map.setMinZoomLevel(8.5)
        map.setMaxZoomLevel(14.0)
        map.styleForTheme(night)
        val view = state?.getDoubleArray(KEY_VIEW)
        if (view != null && view.size == 3) {
            // Back from a theme switch: the map stays where the user left it.
            map.controller.setZoom(view[0])
            map.controller.setCenter(GeoPoint(view[1], view[2]))
        } else {
            val wanted = activity.intent?.getFloatExtra(MainActivity.EXTRA_ZOOM, 0f) ?: 0f
            map.controller.setZoom(if (wanted > 0f) wanted.toDouble() else DEFAULT_ZOOM)
            map.controller.setCenter(GeoPoint(1.3521, 103.8198))
        }
        map.overlays.add(radarOverlay)
        map.overlays.add(gaugeOverlay)
        map.overlays.add(CopyrightOverlay(activity).apply {
            setTextSize(10)
            if (night) setTextColor(Color.WHITE)
        })
    }

    private fun setupControls() {
        b.sourceToggle.check(if (source == RainSource.GAUGES) b.btnGauges.id else b.btnRadar.id)
        b.sourceToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) switchSource(if (checkedId == b.btnGauges.id) RainSource.GAUGES else RainSource.RADAR)
        }
        b.btnPlay.setOnClickListener {
            Log.d(TAG, "play button tapped: playing=$playing, frames=${frameCount()}")
            if (playing) {
                userPaused = true
                stopPlay()
            } else {
                startPlay()
            }
        }
        b.rainScrub.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    userPaused = true
                    stopPlay()
                    showFrame(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userPaused = true
                stopPlay()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        b.rainSource.setOnClickListener {
            if (source == RainSource.RADAR) {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.rainviewer.com/")))
            }
        }
        b.rainStatus.setOnClickListener { ensureLoaded(force = true) }
    }

    // ---- Source switch ----

    private fun switchSource(next: RainSource) {
        if (next == source) return
        source = next
        prefs.edit().putString(KEY_SOURCE, next.key).apply()
        userPaused = false
        stopPlay()
        applySource()
        if (visible) {
            ensureLoaded(force = false)
            maybeAutoplay()
        }
    }

    private fun applySource() {
        radarOverlay.isEnabled = source == RainSource.RADAR
        gaugeOverlay.isEnabled = source == RainSource.GAUGES
        buildLegend()
        applyStatus()
        showCurrentData()
    }

    private fun buildLegend() {
        b.legendSwatches.removeAllViews()
        val colors = if (source == RainSource.RADAR) RainPalette.RADAR else RainPalette.GAUGE
        val w = (14 * density).toInt()
        val h = (6 * density).toInt()
        val gap = (1 * density).toInt() + 1
        for (c in colors) {
            val swatch = View(activity)
            swatch.setBackgroundColor(c)
            b.legendSwatches.addView(swatch, LinearLayout.LayoutParams(w, h).apply { setMargins(gap, 0, gap, 0) })
        }
        b.legendDry.visibility = if (source == RainSource.GAUGES) View.VISIBLE else View.GONE
        b.rainSource.setText(if (source == RainSource.RADAR) R.string.source_radar_label else R.string.source_gauges_label)
        // The radar credit is a link to RainViewer, as its terms ask.
        b.rainSource.paintFlags = if (source == RainSource.RADAR) {
            b.rainSource.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        } else {
            b.rainSource.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
    }

    private fun setStatus(src: RainSource, text: String?) {
        statuses[src] = text
        if (src == source) applyStatus()
    }

    private fun applyStatus() {
        val text = statuses[source]
        b.rainStatus.text = text.orEmpty()
        b.rainStatus.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ---- Loading ----

    private fun ensureLoaded(force: Boolean) {
        when (source) {
            RainSource.RADAR ->
                if (radarJob?.isActive != true && (force || radarFrames.isEmpty())) {
                    radarJob = scope.launch { loadRadar(force) }
                }
            RainSource.GAUGES ->
                if (gaugeJob?.isActive != true && (force || gauge == null)) {
                    gaugeJob = scope.launch { loadGauges(force) }
                }
        }
    }

    private suspend fun loadRadar(force: Boolean) {
        setStatus(RainSource.RADAR, activity.getString(R.string.loading_radar))
        try {
            val radarIndex = repo.load(Endpoints.RADAR_INDEX, force).value
            val frames = radarIndex.frames.takeLast(MAX_RADAR_FRAMES)
            check(frames.isNotEmpty()) { "no radar frames" }
            var done = 0
            val gate = Semaphore(4)
            val tiles = coroutineScope {
                frames.map { frame ->
                    async {
                        val t = gate.withPermit { loadTiles(radarIndex, frame) }
                        done++
                        setStatus(RainSource.RADAR, activity.getString(R.string.loading_radar_progress, done, frames.size))
                        t
                    }
                }.awaitAll()
            }
            check(tiles.any { it.isNotEmpty() }) { "no radar tiles" }
            radarFrames = frames
            radarTiles = tiles
            setStatus(RainSource.RADAR, null)
            if (source == RainSource.RADAR) {
                showCurrentData()
                maybeAutoplay()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "radar load failed", e)
            setStatus(RainSource.RADAR, activity.getString(R.string.error_radar))
        }
    }

    /** Both Singapore tiles of one radar frame. A tile that fails to load is left out. */
    private suspend fun loadTiles(index: RadarIndex, frame: RadarFrame): List<RadarOverlay.Tile> = coroutineScope {
        TileMath.SINGAPORE_X.map { x ->
            async {
                try {
                    val bytes = Http.getBytes(TileMath.radarUrl(index, frame, x, TileMath.SINGAPORE_Y))
                    val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    bitmap?.let { RadarOverlay.Tile(x, TileMath.SINGAPORE_Y, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "radar tile $x failed", e)
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun loadGauges(force: Boolean) {
        setStatus(RainSource.GAUGES, activity.getString(R.string.loading_gauges))
        try {
            val now = System.currentTimeMillis()
            val today = attempt { repo.load(Endpoints.rainfallDay(TimeFmt.isoDate(now)), force).value }
            var stations = today?.stations.orEmpty()
            var frames = today?.completeFrames().orEmpty()
            if (frames.size < GAUGE_FRAMES - 1) {
                // Early in the day today holds under two hours of readings, so borrow the end of yesterday.
                val yesterday = attempt { repo.load(Endpoints.rainfallDay(TimeFmt.isoDate(now - DAY_MS)), force).value }
                if (yesterday != null) {
                    frames = (yesterday.completeFrames() + frames).distinctBy { it.timeMillis }.sortedBy { it.timeMillis }
                    if (stations.isEmpty()) stations = yesterday.stations
                }
            }
            frames = frames.takeLast(GAUGE_FRAMES)
            check(frames.isNotEmpty() && stations.isNotEmpty()) { "no gauge readings" }
            gauge = GaugeData(stations, frames)
            setStatus(RainSource.GAUGES, null)
            if (source == RainSource.GAUGES) {
                showCurrentData()
                maybeAutoplay()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "gauge load failed", e)
            setStatus(RainSource.GAUGES, activity.getString(R.string.error_gauges))
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    // ---- Frames and playback ----

    private fun frameCount(): Int = if (source == RainSource.RADAR) radarFrames.size else gauge?.frames?.size ?: 0

    /** Shows the newest frame of the current source and sets up the scrub bar. */
    private fun showCurrentData() {
        val n = frameCount()
        b.rainScrub.max = maxOf(n - 1, 0)
        b.rainScrub.isEnabled = n > 1
        b.btnPlay.isEnabled = n > 1
        if (n > 0) {
            showFrame(n - 1)
        } else {
            radarOverlay.show(emptyList())
            gaugeOverlay.show(emptyList(), null)
            b.rainClock.text = ""
            b.rainBadge.text = "--"
            b.rainMap.invalidate()
        }
    }

    private fun showFrame(i: Int) {
        val n = frameCount()
        if (n == 0) return
        index = i.coerceIn(0, n - 1)
        if (b.rainScrub.progress != index) b.rainScrub.progress = index
        val time: Long
        if (source == RainSource.RADAR) {
            radarOverlay.show(radarTiles[index])
            time = radarFrames[index].timeMillis
        } else {
            val g = gauge ?: return
            gaugeOverlay.show(g.stations, g.frames[index])
            time = g.frames[index].timeMillis
        }
        val label = TimeFmt.clock(time)
        b.rainClock.text = label
        b.rainBadge.text = label
        b.rainMap.invalidate()
    }

    /**
     * Starts the loop by itself whenever the Rain tab is on screen, unless the user paused it.
     * Skipped when the phone has animations off, which is the setting for people who want less motion.
     */
    private fun maybeAutoplay() {
        if (!visible || playing || userPaused || frameCount() < 2) return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        startPlay()
    }

    /** Plays the past two hours, waits a moment on the newest picture, then starts over until stopped. */
    private fun startPlay() {
        val n = frameCount()
        if (n < 2) return
        userPaused = false
        if (index >= n - 1) showFrame(0)
        playing = true
        updatePlayButton()
        Log.d(TAG, "play start: $source, $n frames")
        val step = if (source == RainSource.RADAR) RADAR_STEP_MS else GAUGE_STEP_MS
        playJob?.cancel()
        playJob = scope.launch {
            var loops = 0
            while (isActive) {
                while (isActive && index < n - 1) {
                    delay(step)
                    showFrame(index + 1)
                }
                delay(LOOP_PAUSE_MS)
                loops++
                Log.d(TAG, "play loop $loops: $source")
                showFrame(0)
            }
        }
    }

    private fun stopPlay() {
        playJob?.cancel()
        playing = false
        updatePlayButton()
    }

    private fun updatePlayButton() {
        b.btnPlay.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        b.btnPlay.contentDescription = activity.getString(if (playing) R.string.pause else R.string.play)
    }

    companion object {
        private const val TAG = "RainScreen"
        private const val DEFAULT_ZOOM = 10.0
        private const val KEY_SOURCE = "rain_source"
        private const val KEY_VIEW = "rain_view"
        private const val MAX_RADAR_FRAMES = 13
        private const val GAUGE_FRAMES = 25
        private const val DAY_MS = 86_400_000L
        private const val RADAR_STEP_MS = 450L
        private const val GAUGE_STEP_MS = 220L
        private const val LOOP_PAUSE_MS = 1500L
    }
}
