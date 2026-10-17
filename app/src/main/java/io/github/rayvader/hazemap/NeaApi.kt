package io.github.rayvader.hazemap

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One snapshot of readings for all regions for a single metric. */
data class ReadingSet(
    val metric: Metric,
    /** region key -> value */
    val values: Map<String, Int>,
    /** epoch millis of the NEA reading timestamp */
    val timestampMillis: Long
)

/**
 * Pulls PSI (24-hour) or PM2.5 (1-hour) from the free data.gov.sg real-time API.
 * No API key required.
 */
object NeaApi {
    private const val PSI_URL = "https://api-open.data.gov.sg/v2/real-time/api/psi"
    private const val PM25_URL = "https://api-open.data.gov.sg/v2/real-time/api/pm25"

    /** Blocking network call. Run off the main thread. */
    fun fetch(metric: Metric): ReadingSet {
        val url = if (metric == Metric.PSI) PSI_URL else PM25_URL
        val body = get(url)
        return parse(metric, body)
    }

    internal fun parse(metric: Metric, body: String): ReadingSet {
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 0) {
            throw IllegalStateException("NEA API error: ${root.optString("errorMsg")}")
        }
        val item = root.getJSONObject("data")
            .getJSONArray("items")
            .getJSONObject(0)
        val readings = item.getJSONObject("readings")

        val field = if (metric == Metric.PSI) "psi_twenty_four_hourly" else "pm25_one_hourly"
        val obj = readings.getJSONObject(field)

        val values = HashMap<String, Int>()
        for (region in Region.entries) {
            if (obj.has(region.key)) {
                values[region.key] = obj.getInt(region.key)
            }
        }

        val ts = item.optString("timestamp").ifBlank { item.optString("updatedTimestamp") }
        return ReadingSet(metric, values, parseIsoMillis(ts))
    }

    /** NEA timestamps look like 2026-09-02T16:00:00+08:00. */
    internal fun parseIsoMillis(iso: String): Long = try {
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }

    private fun get(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
