package io.github.rayvader.hazemap

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One JSON document the app reads: where it is, how long a saved copy stays fresh, how to parse it. */
class Endpoint<T>(
    val url: String,
    val cacheKey: String,
    val ttlMs: Long,
    val limited: Boolean = true,
    val parse: (String) -> T
)

/** A parsed document and when it was saved. [stale] is true when this is older data kept after a failed fetch. */
class Loaded<out T>(val value: T, val savedAtMs: Long, val stale: Boolean)

object Endpoints {
    private const val BASE = "https://api-open.data.gov.sg/v2/real-time/api/"
    private const val MIN = 60_000L

    val FORECAST_2H = nea("two-hr-forecast", 5 * MIN, NeaParsers::forecast2h)
    val FORECAST_24H = nea("twenty-four-hr-forecast", 30 * MIN, NeaParsers::forecast24)
    val OUTLOOK = nea("four-day-outlook", 60 * MIN, NeaParsers::outlook)
    val TEMPERATURE = nea("air-temperature", 3 * MIN, NeaParsers::stationReadings)
    val HUMIDITY = nea("relative-humidity", 3 * MIN, NeaParsers::stationReadings)
    val WIND_SPEED = nea("wind-speed", 3 * MIN, NeaParsers::stationReadings)
    val WIND_DIRECTION = nea("wind-direction", 3 * MIN, NeaParsers::stationReadings)
    val UV = nea("uv", 15 * MIN, NeaParsers::uv)

    /** RainViewer lives on its own host, so it does not count against the data.gov.sg limit. */
    val RADAR_INDEX = Endpoint(
        url = "https://api.rainviewer.com/public/weather-maps.json",
        cacheKey = "rainviewer-index",
        ttlMs = 2 * MIN,
        limited = false,
        parse = NeaParsers::radarIndex
    )

    private const val OPEN_METEO = "https://api.open-meteo.com/v1/forecast"
    private const val OPEN_METEO_CURRENT = "current=temperature_2m,weather_code,is_day&timezone=auto"

    /** Current weather and the next days for one place. Open-Meteo is not rate limited like data.gov.sg. */
    fun placeForecast(p: Place): Endpoint<PlaceWeather> = Endpoint(
        url = "$OPEN_METEO?latitude=${coord(p.lat)}&longitude=${coord(p.lon)}" +
            "&$OPEN_METEO_CURRENT&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=5",
        cacheKey = "place-forecast-${p.id}",
        ttlMs = 15 * MIN,
        limited = false,
        parse = PlaceParsers::forecast
    )

    /** Temperature and weather of every saved place in one request. */
    fun placeGlance(places: List<Place>): Endpoint<List<PlaceGlance>> = Endpoint(
        url = "$OPEN_METEO?latitude=${places.joinToString(",") { coord(it.lat) }}" +
            "&longitude=${places.joinToString(",") { coord(it.lon) }}&$OPEN_METEO_CURRENT",
        cacheKey = "place-glance-${places.joinToString(",") { it.id.toString() }.hashCode()}",
        ttlMs = 10 * MIN,
        limited = false,
        parse = PlaceParsers::glance
    )

    /** Two decimals is about 1 km, plenty for a city forecast. */
    private fun coord(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    fun haze(metric: Metric): Endpoint<ReadingSet> =
        nea(metric.apiPath, 15 * MIN) { NeaApi.parse(metric, it) }

    /** The rainfall readings of one Singapore calendar day, newest page first. */
    fun rainfallDay(isoDate: String): Endpoint<RainfallPage> =
        nea("rainfall?date=$isoDate", 2 * MIN, NeaParsers::rainfall)

    private fun <T> nea(path: String, ttlMs: Long, parse: (String) -> T): Endpoint<T> =
        Endpoint(BASE + path, path, ttlMs, true, parse)
}

/** Fetches endpoints with a disk cache in front: fresh copy first, then network, then any old copy. */
class WeatherRepository private constructor(private val cache: JsonCache) {

    suspend fun <T> load(endpoint: Endpoint<T>, force: Boolean = false): Loaded<T> {
        val saved = withContext(Dispatchers.IO) { cache.read(endpoint.cacheKey) }
        if (!force && saved != null && System.currentTimeMillis() - saved.savedAtMs < endpoint.ttlMs) {
            val parsed = tryParse(endpoint, saved.body)
            if (parsed != null) return Loaded(parsed, saved.savedAtMs, stale = false)
        }
        try {
            val body = Http.getText(endpoint.url, endpoint.limited)
            val parsed = withContext(Dispatchers.Default) { endpoint.parse(body) }
            withContext(Dispatchers.IO) { cache.write(endpoint.cacheKey, body) }
            return Loaded(parsed, System.currentTimeMillis(), stale = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val old = saved?.let { s -> tryParse(endpoint, s.body)?.let { Loaded(it, s.savedAtMs, stale = true) } }
            return old ?: throw e
        }
    }

    /** Whatever is on disk, however old, without touching the network. */
    fun <T> peek(endpoint: Endpoint<T>): Loaded<T>? {
        val saved = cache.read(endpoint.cacheKey) ?: return null
        val parsed = tryParse(endpoint, saved.body) ?: return null
        return Loaded(parsed, saved.savedAtMs, stale = true)
    }

    private fun <T> tryParse(endpoint: Endpoint<T>, body: String): T? =
        try {
            endpoint.parse(body)
        } catch (e: Exception) {
            null
        }

    companion object {
        @Volatile
        private var instance: WeatherRepository? = null

        fun get(context: Context): WeatherRepository = instance ?: synchronized(this) {
            instance ?: WeatherRepository(JsonCache(File(context.applicationContext.cacheDir, "nea")))
                .also { instance = it }
        }
    }
}
