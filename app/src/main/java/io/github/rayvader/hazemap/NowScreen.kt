package io.github.rayvader.hazemap

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import io.github.rayvader.hazemap.databinding.ItemDayBinding
import io.github.rayvader.hazemap.databinding.ItemPeriodBinding
import io.github.rayvader.hazemap.databinding.ScreenNowBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The "Now" tab: temperature and condition for the chosen area, today's periods, haze, next 4 days.
 *
 * The raw documents are kept island-wide; [render] picks the nearest station and the area's region,
 * so changing the area needs no new request.
 */
class NowScreen(
    private val activity: AppCompatActivity,
    private val b: ScreenNowBinding,
    private val currentArea: () -> Area,
    private val openHaze: () -> Unit
) {
    private val repo = WeatherRepository.get(activity)
    private val density = activity.resources.displayMetrics.density

    private var f2h: Loaded<Forecast2h>? = null
    private var f24: Loaded<Forecast24>? = null
    private var outlook: Loaded<List<DayForecast>>? = null
    private var temperature: Loaded<StationReadings>? = null
    private var humidity: Loaded<StationReadings>? = null
    private var windSpeed: Loaded<StationReadings>? = null
    private var windDirection: Loaded<StationReadings>? = null
    private var uv: Loaded<List<UvReading>>? = null
    private var psi: Loaded<ReadingSet>? = null

    private var job: Job? = null
    private var loading = false
    private var failures = 0

    init {
        b.nowSwipe.setOnRefreshListener { load(force = true) }
        b.hazeCard.setOnClickListener { openHaze() }
        render()
    }

    fun onShown() = load(force = false)

    /** The area changed: every document is island-wide, so just redraw. */
    fun onAreaChanged() = render()

    fun load(force: Boolean) {
        job?.cancel()
        job = activity.lifecycleScope.launch {
            loading = true
            failures = 0
            b.nowSwipe.isRefreshing = true
            if (!hasData()) paintFromDisk()
            // Launch order is priority order: NEA allows 5 calls per 10 s here, so the last few wait.
            coroutineScope {
                launch { step(Endpoints.FORECAST_2H, force) { f2h = it } }
                launch { step(Endpoints.TEMPERATURE, force) { temperature = it } }
                launch { step(Endpoints.FORECAST_24H, force) { f24 = it } }
                launch { step(Endpoints.HUMIDITY, force) { humidity = it } }
                launch { step(Endpoints.UV, force) { uv = it } }
                launch { step(Endpoints.WIND_SPEED, force) { windSpeed = it } }
                launch { step(Endpoints.WIND_DIRECTION, force) { windDirection = it } }
                launch { step(Endpoints.haze(Metric.PSI), force) { psi = it } }
                launch { step(Endpoints.OUTLOOK, force) { outlook = it } }
            }
            loading = false
            b.nowSwipe.isRefreshing = false
            render()
        }
    }

    private suspend fun <T> step(endpoint: Endpoint<T>, force: Boolean, set: (Loaded<T>) -> Unit) {
        try {
            set(repo.load(endpoint, force))
            render()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures++
        }
    }

    private fun hasData() = f2h != null || temperature != null || f24 != null

    private suspend fun <T> peek(endpoint: Endpoint<T>): Loaded<T>? = withContext(Dispatchers.IO) { repo.peek(endpoint) }

    private suspend fun paintFromDisk() {
        f2h = peek(Endpoints.FORECAST_2H)
        temperature = peek(Endpoints.TEMPERATURE)
        f24 = peek(Endpoints.FORECAST_24H)
        humidity = peek(Endpoints.HUMIDITY)
        uv = peek(Endpoints.UV)
        windSpeed = peek(Endpoints.WIND_SPEED)
        windDirection = peek(Endpoints.WIND_DIRECTION)
        psi = peek(Endpoints.haze(Metric.PSI))
        outlook = peek(Endpoints.OUTLOOK)
        render()
    }

    private fun render() {
        val area = currentArea()
        val regionKey = area.region.key
        val now = System.currentTimeMillis()

        // Hero
        val condition = f2h?.value?.forArea(area.name)
        val tempC = temperature?.value?.nearestValue(area.lat, area.lon)
        b.nowTemp.text = tempC?.let { "${it.roundToInt()}°" } ?: "--°"
        b.nowCondition.text = condition ?: "--"
        b.nowIcon.setImageResource(Icons.forCondition(Condition.fromText(condition)))
        b.nowUntil.text = f2h?.value?.let { activity.getString(R.string.till_time, TimeFmt.clock(it.validUntilMillis)) }.orEmpty()

        val summary = ArrayList<String>()
        f24?.value?.let { f ->
            f.high?.let { summary.add("H $it°") }
            f.low?.let { summary.add("L $it°") }
            ForecastText.laterNote(f.periods, regionKey, now, condition)?.let { summary.add(it) }
        }
        b.nowHiLo.text = summary.joinToString(" · ")

        // Chips
        val hum = humidity?.value?.nearestValue(area.lat, area.lon)
        setChip(b.chipHumidity, hum?.let { activity.getString(R.string.chip_humid, it.roundToInt()) })
        val speed = windSpeed?.value?.nearestValue(area.lat, area.lon)
        val bearing = windDirection?.value?.nearestValue(area.lat, area.lon)
        val windText = when {
            speed == null -> null
            bearing == null -> activity.getString(R.string.chip_wind_only, WindFmt.kmh(speed))
            else -> activity.getString(R.string.chip_wind, WindFmt.compass(bearing), WindFmt.kmh(speed))
        }
        setChip(b.chipWind, windText)
        val uvIndex = uv?.value?.firstOrNull()?.index
        setChip(b.chipUv, uvIndex?.takeIf { it > 0 }?.let { activity.getString(R.string.chip_uv, it) })

        renderToday(regionKey, now)
        renderHaze(area, regionKey)
        renderDays()
        renderFooter()
    }

    private fun renderToday(regionKey: String, now: Long) {
        b.todayRow.removeAllViews()
        val periods = f24?.value?.periods.orEmpty().take(3)
        b.todayCard.visibility = if (periods.isEmpty()) View.GONE else View.VISIBLE
        for (p in periods) {
            val item = ItemPeriodBinding.inflate(activity.layoutInflater, b.todayRow, false)
            val text = p.byRegion[regionKey]
            item.periodName.text = ForecastText.periodLabel(p.startMillis, now)
            item.periodIcon.setImageResource(Icons.forCondition(Condition.fromText(text)))
            item.periodText.text = text ?: "--"
            val extra = ForecastText.rainElsewhere(p, regionKey)
            item.periodExtra.text = extra.orEmpty()
            item.periodExtra.visibility = if (extra == null) View.GONE else View.VISIBLE
            b.todayRow.addView(item.root)
        }
    }

    private fun renderHaze(area: Area, regionKey: String) {
        val values = psi?.value?.values
        val mine = values?.get(regionKey)
        if (values == null || mine == null) {
            b.hazeCard.visibility = View.GONE
            return
        }
        b.hazeCard.visibility = View.VISIBLE
        val band = Band.forPsi(mine)
        b.hazeBadge.text = activity.getString(R.string.psi_value, mine)
        b.hazeBadge.setBadge(band.color, band.textColor, density)
        b.hazeBand.text = band.label
        val worst = values.maxByOrNull { it.value }
        b.hazeDetail.text = if (worst == null || worst.key == regionKey) {
            activity.getString(R.string.haze_detail_highest, area.region.label)
        } else {
            val worstName = Region.fromKey(worst.key)?.label ?: worst.key
            activity.getString(R.string.haze_detail, area.region.label, worstName, worst.value)
        }
    }

    private fun renderDays() {
        b.daysList.removeAllViews()
        val days = outlook?.value.orEmpty()
        b.daysCard.visibility = if (days.isEmpty()) View.GONE else View.VISIBLE
        for (d in days) {
            val row = ItemDayBinding.inflate(activity.layoutInflater, b.daysList, false)
            row.dayName.text = TimeFmt.dayShort(d.dateMillis)
            row.dayIcon.setImageResource(Icons.forCondition(Condition.fromText(d.forecast)))
            row.dayText.text = d.forecast
            row.dayRange.text = if (d.low != null && d.high != null) "${d.low}–${d.high}°" else ""
            b.daysList.addView(row.root)
        }
    }

    private fun renderFooter() {
        val all = listOfNotNull(f2h, temperature, f24, humidity, windSpeed, windDirection, uv, outlook, psi)
        val newest = all.maxOfOrNull { it.savedAtMs }
        val status = if (newest == null) {
            if (loading) "" else activity.getString(R.string.error_load)
        } else if (all.any { it.stale }) {
            activity.getString(R.string.saved_data, TimeFmt.clock(newest))
        } else {
            activity.getString(R.string.updated_at, TimeFmt.clock(newest))
        }
        val note = if (!loading && failures > 0 && newest != null) activity.getString(R.string.some_failed) else ""
        b.nowFooter.text = listOf(status, note, activity.getString(R.string.attribution_nea))
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun setChip(chip: Chip, text: String?) {
        chip.visibility = if (text == null) View.GONE else View.VISIBLE
        chip.text = text.orEmpty()
    }
}
