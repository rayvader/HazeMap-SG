package io.github.rayvader.hazemap

import android.content.Context

/**
 * Fetches the current readings from NEA, saves each one into on-device history,
 * and reads history back for the chart. Fetch happens only when the app asks
 * (no background work).
 */
class HazeRepository(context: Context) {

    private val dao = HistoryDb.get(context).readingDao()

    /** Keep roughly a month of history to bound the DB. */
    private val retentionMillis = 31L * 24 * 60 * 60 * 1000

    /** Blocking. Call off the main thread. Returns the fresh snapshot after storing it. */
    fun refresh(metric: Metric): ReadingSet {
        val set = NeaApi.fetch(metric)
        store(set)
        dao.pruneOlderThan(System.currentTimeMillis() - retentionMillis)
        return set
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

    /** Stored history for one region+metric, oldest first. Blocking. */
    fun history(region: Region, metric: Metric): List<ReadingEntity> =
        dao.history(region.key, metric.apiPath)

    fun hasAnyHistory(): Boolean = dao.count() > 0
}
