package io.github.rayvader.hazemap

import org.json.JSONObject

/** One snapshot of readings for all regions for a single metric. */
data class ReadingSet(
    val metric: Metric,
    /** region key -> value */
    val values: Map<String, Int>,
    /** epoch millis of the NEA reading timestamp */
    val timestampMillis: Long
)

/** Parses the haze (PSI and PM2.5) endpoints of the data.gov.sg real-time API. */
object NeaApi {

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
        TimeFmt.parseIso(iso)
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}
