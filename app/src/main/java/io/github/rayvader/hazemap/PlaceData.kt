package io.github.rayvader.hazemap

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** A city outside the home screens, found by Open-Meteo's place search. */
data class Place(
    val id: Long,
    val name: String,
    /** State or province, to tell apart cities with the same name. May be empty. */
    val region: String,
    val country: String,
    val countryCode: String,
    val lat: Double,
    val lon: Double,
    /** IANA zone id such as "Asia/Tokyo". */
    val timezone: String
) {
    /** "Kyoto, Japan"-style line under the name. */
    val subtitle: String
        get() = listOf(region, country).filter { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
            .distinct().joinToString(", ")
}

/** One day of a place's forecast. [date] is the calendar date at the place. */
data class PlaceDay(val date: LocalDate, val code: Int?, val low: Int?, val high: Int?)

/** Current weather and the days ahead for one place. [days] starts with today. */
data class PlaceWeather(
    val tempC: Double?,
    /** Relative humidity in percent, or null when the service did not report it. */
    val humidity: Int?,
    val code: Int?,
    val isDay: Boolean,
    val timezone: String,
    val days: List<PlaceDay>
)

/** The short reading shown beside a place in the list. */
data class PlaceGlance(val tempC: Double?, val code: Int?, val isDay: Boolean)

/** WMO weather codes, as Open-Meteo reports them, mapped to the app's icons and words. */
object Wmo {
    fun condition(code: Int?, isDay: Boolean): Condition = when (code) {
        0, 1 -> if (isDay) Condition.SUN else Condition.MOON
        // The app has no night version of the partly cloudy icon; NEA text maps the same way.
        2 -> if (isDay) Condition.PARTLY else Condition.CLOUD
        3 -> Condition.CLOUD
        45, 48 -> Condition.HAZE
        in 51..67 -> Condition.RAIN
        in 71..77 -> Condition.SNOW
        in 80..82 -> Condition.RAIN
        85, 86 -> Condition.SNOW
        95, 96, 99 -> Condition.STORM
        else -> Condition.CLOUD
    }

    fun label(code: Int?): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "--"
    }
}

object PlaceParsers {

    /**
     * Open-Meteo place search. The response has no "results" key when nothing matched.
     * Singapore is dropped: it is the home screen and has its own NEA data.
     */
    fun geocoding(body: String): List<Place> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val code = r.optString("country_code")
            if (code.equals("SG", ignoreCase = true)) continue
            out.add(
                Place(
                    id = r.getLong("id"),
                    name = r.getString("name"),
                    region = r.optString("admin1"),
                    country = r.optString("country"),
                    countryCode = code,
                    lat = r.getDouble("latitude"),
                    lon = r.getDouble("longitude"),
                    timezone = r.optString("timezone").ifBlank { "UTC" }
                )
            )
        }
        return out
    }

    /** One place: current temperature and weather code, plus a daily forecast starting today. */
    fun forecast(body: String): PlaceWeather {
        val root = JSONObject(body)
        check(!root.optBoolean("error", false)) { "Open-Meteo error: ${root.optString("reason")}" }
        return weatherOf(root)
    }

    /** Several places in one call. Open-Meteo answers a single place with an object, several with an array. */
    fun glance(body: String): List<PlaceGlance> {
        val text = body.trimStart()
        val objects = if (text.startsWith("[")) {
            val arr = JSONArray(text)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } else {
            listOf(JSONObject(text))
        }
        return objects.map { o ->
            val c = o.optJSONObject("current")
            PlaceGlance(
                tempC = c?.numberOrNull("temperature_2m"),
                code = c?.intOrNull("weather_code"),
                isDay = c?.optInt("is_day", 1) != 0
            )
        }
    }

    private fun weatherOf(root: JSONObject): PlaceWeather {
        val current = root.optJSONObject("current")
        val daily = root.optJSONObject("daily")
        val days = ArrayList<PlaceDay>()
        if (daily != null) {
            val times = daily.getJSONArray("time")
            val codes = daily.optJSONArray("weather_code")
            val highs = daily.optJSONArray("temperature_2m_max")
            val lows = daily.optJSONArray("temperature_2m_min")
            for (i in 0 until times.length()) {
                days.add(
                    PlaceDay(
                        date = LocalDate.parse(times.getString(i)),
                        code = codes?.intAt(i),
                        low = lows?.numberAt(i)?.let { Math.round(it).toInt() },
                        high = highs?.numberAt(i)?.let { Math.round(it).toInt() }
                    )
                )
            }
        }
        return PlaceWeather(
            tempC = current?.numberOrNull("temperature_2m"),
            humidity = current?.numberOrNull("relative_humidity_2m")?.let { Math.round(it).toInt() },
            code = current?.intOrNull("weather_code"),
            isDay = current?.optInt("is_day", 1) != 0,
            timezone = root.optString("timezone").ifBlank { "UTC" },
            days = days
        )
    }

    private fun JSONObject.numberOrNull(key: String): Double? =
        if (isNull(key) || !has(key)) null else getDouble(key)

    private fun JSONObject.intOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else getInt(key)

    private fun JSONArray.numberAt(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    private fun JSONArray.intAt(i: Int): Int? = if (isNull(i)) null else getInt(i)
}

/** The saved places, kept as one JSON array in the app's settings. */
object PlaceList {
    const val MAX = 30

    fun parse(json: String?): List<Place> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Place(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    region = o.optString("region"),
                    country = o.optString("country"),
                    countryCode = o.optString("cc"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    timezone = o.optString("tz").ifBlank { "UTC" }
                )
            }
        } catch (e: Exception) {
            // A damaged setting should not stop the app: start with an empty list.
            emptyList()
        }
    }

    fun toJson(places: List<Place>): String {
        val arr = JSONArray()
        for (p in places) {
            arr.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("region", p.region)
                    .put("country", p.country).put("cc", p.countryCode)
                    .put("lat", p.lat).put("lon", p.lon).put("tz", p.timezone)
            )
        }
        return arr.toString()
    }

    /** Adds [place] at the end. A place already in the list, or a full list, leaves it unchanged. */
    fun add(list: List<Place>, place: Place): List<Place> =
        if (list.any { it.id == place.id } || list.size >= MAX) list else list + place

    fun remove(list: List<Place>, id: Long): List<Place> = list.filterNot { it.id == id }
}
