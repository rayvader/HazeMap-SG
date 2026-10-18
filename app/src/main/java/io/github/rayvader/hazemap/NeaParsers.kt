package io.github.rayvader.hazemap

import org.json.JSONArray
import org.json.JSONObject

/** Pure parsers for the data.gov.sg real-time API and the RainViewer index. No network, no Android. */
object NeaParsers {

    private fun data(body: String): JSONObject {
        val root = JSONObject(body)
        check(root.optInt("code", -1) == 0) { "NEA error: ${root.optString("errorMsg")}" }
        return root.getJSONObject("data")
    }

    private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)

    private fun stations(arr: JSONArray): List<Station> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        val loc = o.getJSONObject("location")
        Station(
            id = o.getString("id"),
            name = o.optString("name", o.getString("id")),
            lat = loc.getDouble("latitude"),
            lon = loc.getDouble("longitude")
        )
    }

    private fun values(arr: JSONArray): Map<String, Double> {
        val out = HashMap<String, Double>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!o.isNull("value")) out[o.getString("stationId")] = o.getDouble("value")
        }
        return out
    }

    /** Air temperature, humidity, wind speed or wind direction: the newest reading per station. */
    fun stationReadings(body: String): StationReadings {
        val d = data(body)
        val readings = d.getJSONArray("readings")
        var newest: JSONObject? = null
        var newestTs = Long.MIN_VALUE
        for (i in 0 until readings.length()) {
            val r = readings.getJSONObject(i)
            val ts = TimeFmt.parseIso(r.getString("timestamp"))
            if (ts > newestTs) {
                newest = r
                newestTs = ts
            }
        }
        val latest = newest ?: error("no readings in response")
        return StationReadings(
            stations = stations(d.getJSONArray("stations")),
            values = values(latest.getJSONArray("data")),
            timestampMillis = newestTs,
            unit = d.optString("readingUnit")
        )
    }

    /** Rainfall (5-minute totals per gauge). Works for the latest reading and for a ?date= page. */
    fun rainfall(body: String): RainfallPage {
        val d = data(body)
        val arr = d.getJSONArray("readings")
        val frames = ArrayList<GaugeFrame>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            frames.add(GaugeFrame(TimeFmt.parseIso(r.getString("timestamp")), values(r.getJSONArray("data"))))
        }
        frames.sortBy { it.timeMillis }
        return RainfallPage(
            stations = stations(d.getJSONArray("stations")),
            frames = frames,
            nextToken = d.optString("paginationToken").ifBlank { null }
        )
    }

    fun forecast2h(body: String): Forecast2h {
        val item = data(body).getJSONArray("items").getJSONObject(0)
        val valid = item.getJSONObject("valid_period")
        val arr = item.getJSONArray("forecasts")
        val byArea = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            byArea[o.getString("area")] = o.getString("forecast")
        }
        return Forecast2h(
            validFromMillis = TimeFmt.parseIso(valid.getString("start")),
            validUntilMillis = TimeFmt.parseIso(valid.getString("end")),
            byArea = byArea
        )
    }

    fun forecast24(body: String): Forecast24 {
        val rec = data(body).getJSONArray("records").getJSONObject(0)
        val general = rec.getJSONObject("general")
        val temperature = general.optJSONObject("temperature")
        val periodsJson = rec.getJSONArray("periods")
        val periods = ArrayList<ForecastPeriod>(periodsJson.length())
        for (i in 0 until periodsJson.length()) {
            val p = periodsJson.getJSONObject(i)
            val time = p.getJSONObject("timePeriod")
            val regions = p.getJSONObject("regions")
            val byRegion = LinkedHashMap<String, String>()
            for (key in regions.keys()) byRegion[key] = regions.getJSONObject(key).getString("text")
            periods.add(
                ForecastPeriod(
                    TimeFmt.parseIso(time.getString("start")),
                    TimeFmt.parseIso(time.getString("end")),
                    byRegion
                )
            )
        }
        return Forecast24(
            general = general.getJSONObject("forecast").getString("text"),
            low = temperature?.intOrNull("low"),
            high = temperature?.intOrNull("high"),
            periods = periods
        )
    }

    fun outlook(body: String): List<DayForecast> {
        val arr = data(body).getJSONArray("records").getJSONObject(0).getJSONArray("forecasts")
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val f = o.getJSONObject("forecast")
            val t = o.optJSONObject("temperature")
            DayForecast(
                dateMillis = TimeFmt.parseIso(o.getString("timestamp")),
                forecast = f.getString("text"),
                summary = f.optString("summary").trim(),
                low = t?.intOrNull("low"),
                high = t?.intOrNull("high")
            )
        }
    }

    /** UV index by hour, newest first. Empty when NEA has nothing (night). */
    fun uv(body: String): List<UvReading> {
        val record = data(body).getJSONArray("records").optJSONObject(0) ?: return emptyList()
        val index = record.optJSONArray("index") ?: return emptyList()
        return List(index.length()) { i ->
            val o = index.getJSONObject(i)
            UvReading(TimeFmt.parseIso(o.getString("hour")), o.getInt("value"))
        }
    }

    /** RainViewer's weather-maps.json: where the radar frames are, oldest first. */
    fun radarIndex(body: String): RadarIndex {
        val root = JSONObject(body)
        val past = root.getJSONObject("radar").getJSONArray("past")
        val frames = List(past.length()) { i ->
            val o = past.getJSONObject(i)
            RadarFrame(timeMillis = o.getLong("time") * 1000, path = o.getString("path"))
        }
        return RadarIndex(root.getString("host"), frames.sortedBy { it.timeMillis })
    }
}
