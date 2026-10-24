package io.github.rayvader.hazemap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.rayvader.hazemap.databinding.ActivityPlaceBinding
import io.github.rayvader.hazemap.databinding.ItemDayBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The weather page of one saved place: now, and the next four days. Singapore keeps the main
 * screen with all its features; this page only needs temperature, weather and forecast.
 */
class PlaceActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlaceBinding
    private lateinit var place: Place
    private lateinit var store: PlaceStore
    private val repo by lazy { WeatherRepository.get(this) }

    private var weather: Loaded<PlaceWeather>? = null
    private var loading = false
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = placeFrom(intent)
        if (found == null) {
            finish()
            return
        }
        place = found
        store = PlaceStore(this)
        b = ActivityPlaceBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.placeTitle.text = place.name
        b.btnBack.setOnClickListener { finish() }
        b.btnStar.setOnClickListener { toggleSaved() }
        b.btnPlaceRefresh.setOnClickListener { load(force = true) }
        b.placeSwipe.setOnRefreshListener { load(force = true) }
        showStar()
        render()
        load(force = false)
    }

    private fun load(force: Boolean) {
        job?.cancel()
        job = lifecycleScope.launch {
            loading = true
            b.placeSwipe.isRefreshing = true
            val endpoint = Endpoints.placeForecast(place)
            if (weather == null) {
                weather = withContext(Dispatchers.IO) { repo.peek(endpoint) }
                render()
            }
            try {
                weather = repo.load(endpoint, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep whatever is already shown; the footer says if there is nothing at all.
            }
            loading = false
            b.placeSwipe.isRefreshing = false
            render()
        }
    }

    private fun toggleSaved() {
        if (store.contains(place.id)) store.remove(place.id) else store.add(place)
        showStar()
    }

    private fun showStar() {
        val saved = store.contains(place.id)
        b.btnStar.setImageResource(if (saved) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        b.btnStar.contentDescription = getString(if (saved) R.string.place_remove else R.string.place_save)
    }

    private fun render() {
        val w = weather?.value
        val zone = w?.timezone ?: place.timezone
        val today = w?.days?.firstOrNull()

        b.placeTemp.text = w?.tempC?.let { "${it.roundToInt()}°" } ?: getString(R.string.no_temp)
        b.placeIcon.setImageResource(Icons.forCondition(Wmo.condition(w?.code, w?.isDay ?: true)))
        b.placeCondition.text = if (w == null) "" else Wmo.label(w.code)

        val parts = ArrayList<String>()
        if (today?.high != null && today.low != null) parts.add(getString(R.string.place_hilo, today.high, today.low))
        if (place.country.isNotBlank()) parts.add(place.country)
        if (w != null) parts.add(TimeFmt.clockIn(System.currentTimeMillis(), zone))
        b.placeDetail.text = parts.joinToString(" · ")

        val humidity = w?.humidity
        b.placeChipHumidity.visibility = if (humidity == null) android.view.View.GONE else android.view.View.VISIBLE
        if (humidity != null) b.placeChipHumidity.text = getString(R.string.chip_humid, humidity)

        b.placeDaysList.removeAllViews()
        val days = w?.days.orEmpty().drop(1).take(4)
        b.placeDaysCard.visibility = if (days.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        for (d in days) {
            val row = ItemDayBinding.inflate(layoutInflater, b.placeDaysList, false)
            row.dayName.text = d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            row.dayIcon.setImageResource(Icons.forCondition(Wmo.condition(d.code, true)))
            row.dayText.text = Wmo.label(d.code)
            row.dayRange.text = if (d.low != null && d.high != null) "${d.low}–${d.high}°" else ""
            b.placeDaysList.addView(row.root)
        }

        val loaded = weather
        val status = when {
            loaded == null -> if (loading) "" else getString(R.string.error_load)
            loaded.stale -> getString(R.string.saved_data, TimeFmt.clockIn(loaded.savedAtMs, zone))
            else -> getString(R.string.updated_at, TimeFmt.clockIn(loaded.savedAtMs, zone))
        }
        b.placeFooter.text = listOf(status, getString(R.string.attribution_open_meteo))
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    companion object {
        private const val ID = "id"
        private const val NAME = "name"
        private const val REGION = "region"
        private const val COUNTRY = "country"
        private const val CODE = "cc"
        private const val LAT = "lat"
        private const val LON = "lon"
        private const val ZONE = "tz"

        fun intent(context: Context, p: Place): Intent = Intent(context, PlaceActivity::class.java)
            .putExtra(ID, p.id).putExtra(NAME, p.name).putExtra(REGION, p.region)
            .putExtra(COUNTRY, p.country).putExtra(CODE, p.countryCode)
            .putExtra(LAT, p.lat).putExtra(LON, p.lon).putExtra(ZONE, p.timezone)

        private fun placeFrom(i: Intent): Place? {
            val id = i.getLongExtra(ID, -1L)
            val name = i.getStringExtra(NAME)
            if (id == -1L || name == null || !i.hasExtra(LAT) || !i.hasExtra(LON)) return null
            return Place(
                id = id,
                name = name,
                region = i.getStringExtra(REGION).orEmpty(),
                country = i.getStringExtra(COUNTRY).orEmpty(),
                countryCode = i.getStringExtra(CODE).orEmpty(),
                lat = i.getDoubleExtra(LAT, 0.0),
                lon = i.getDoubleExtra(LON, 0.0),
                timezone = i.getStringExtra(ZONE).orEmpty().ifBlank { "UTC" }
            )
        }
    }
}
