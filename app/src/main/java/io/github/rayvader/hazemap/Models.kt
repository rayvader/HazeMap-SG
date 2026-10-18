package io.github.rayvader.hazemap

internal fun sq(v: Double): Double = v * v

/** A weather station and where it stands. */
data class Station(val id: String, val name: String, val lat: Double, val lon: Double)

/** One reading per station at one moment: temperature, humidity, wind speed or wind direction. */
data class StationReadings(
    val stations: List<Station>,
    val values: Map<String, Double>,
    val timestampMillis: Long,
    val unit: String
) {
    /** The value at the closest station that reported, or null if none did. */
    fun nearestValue(lat: Double, lon: Double): Double? {
        var best: Station? = null
        var bestDistance = Double.MAX_VALUE
        for (s in stations) {
            if (!values.containsKey(s.id)) continue
            val d = sq(s.lat - lat) + sq(s.lon - lon)
            if (d < bestDistance) {
                bestDistance = d
                best = s
            }
        }
        return best?.let { values[it.id] }
    }
}

/** NEA 2-hour forecast: one line per area, valid for a short window. */
data class Forecast2h(val validFromMillis: Long, val validUntilMillis: Long, val byArea: Map<String, String>) {
    fun forArea(name: String): String? =
        byArea.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** One slice of the 24-hour forecast, with a forecast per region (keys are [Region.key]). */
data class ForecastPeriod(val startMillis: Long, val endMillis: Long, val byRegion: Map<String, String>)

data class Forecast24(val general: String, val low: Int?, val high: Int?, val periods: List<ForecastPeriod>)

/** One day of the 4-day outlook. */
data class DayForecast(
    val dateMillis: Long,
    val forecast: String,
    val summary: String,
    val low: Int?,
    val high: Int?
)

data class UvReading(val hourMillis: Long, val index: Int)

/** Rain collected by every gauge in the 5 minutes up to [timeMillis]. Values are mm. */
data class GaugeFrame(val timeMillis: Long, val mm: Map<String, Double>)

data class GaugeData(val stations: List<Station>, val frames: List<GaugeFrame>)

/** One page of the rainfall API, oldest frame first. */
data class RainfallPage(val stations: List<Station>, val frames: List<GaugeFrame>, val nextToken: String?) {
    /**
     * The newest frame is often still filling in (a handful of gauges out of 90).
     * Keep frames where at least half the gauges have reported.
     */
    fun completeFrames(): List<GaugeFrame> = frames.filter { it.mm.size * 2 >= stations.size }
}

data class RadarFrame(val timeMillis: Long, val path: String)

data class RadarIndex(val host: String, val frames: List<RadarFrame>)
