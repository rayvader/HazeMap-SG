package io.github.rayvader.hazemap

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads PSI and PM2.5 from NEA, saves each reading into on-device history,
 * and reads history back for the chart.
 */
class HazeRepository(context: Context) {

    private val dao = HistoryDb.get(context).readingDao()
    private val weather = WeatherRepository.get(context)

    /** Keep roughly a month of history to bound the DB. */
    private val retentionMillis = 31L * 24 * 60 * 60 * 1000

    /**
     * Loads both metrics and stores them in history. One metric failing does not hide the other;
     * if both fail, the first error is thrown.
     */
    suspend fun refreshBoth(force: Boolean = false): Map<Metric, Loaded<ReadingSet>> {
        val out = LinkedHashMap<Metric, Loaded<ReadingSet>>()
        var firstError: Exception? = null
        for (metric in Metric.entries) {
            try {
                out[metric] = weather.load(Endpoints.haze(metric), force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        if (out.isEmpty()) throw firstError ?: IllegalStateException("no haze data")
        withContext(Dispatchers.IO) {
            out.values.forEach { store(it.value) }
            dao.pruneOlderThan(System.currentTimeMillis() - retentionMillis)
        }
        return out
    }

    /** The saved copy of both metrics for an instant first paint, or null if either is missing. */
    fun peekBoth(): Map<Metric, Loaded<ReadingSet>>? {
        val out = LinkedHashMap<Metric, Loaded<ReadingSet>>()
        for (metric in Metric.entries) {
            out[metric] = weather.peek(Endpoints.haze(metric)) ?: return null
        }
        return out
    }

    private fun store(set: ReadingSet) {
        val rows = set.values.map { (region, value) ->
            ReadingEntity(
                region = region,
                metric = set.metric.apiPath,
                value = value,
                timestampMillis = set.timestampMillis
            )
        }
        if (rows.isNotEmpty()) dao.insertAll(rows)
    }

    /** Stored history for one region+metric, oldest first. */
    suspend fun history(region: Region, metric: Metric): List<ReadingEntity> =
        withContext(Dispatchers.IO) { dao.history(region.key, metric.apiPath) }
}
