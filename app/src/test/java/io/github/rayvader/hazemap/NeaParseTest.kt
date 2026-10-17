package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Test

class NeaParseTest {

    private val psiBody = """
        {"code":0,"data":{"items":[{"timestamp":"2026-09-02T16:00:00+08:00","readings":{
        "psi_twenty_four_hourly":{"north":61,"south":65,"east":67,"west":68,"central":75}}}]},"errorMsg":""}
    """.trimIndent()

    private val pm25Body = """
        {"code":0,"data":{"items":[{"timestamp":"2026-06-09T20:00:00+08:00","readings":{
        "pm25_one_hourly":{"central":23,"south":14,"west":17,"east":19,"north":19}}}]},"errorMsg":""}
    """.trimIndent()

    @Test
    fun parsesPsiForAllRegions() {
        val set = NeaApi.parse(Metric.PSI, psiBody)
        assertEquals(Metric.PSI, set.metric)
        assertEquals(5, set.values.size)
        assertEquals(75, set.values["central"])
        assertEquals(61, set.values["north"])
    }

    @Test
    fun parsesPm25ForAllRegions() {
        val set = NeaApi.parse(Metric.PM25, pm25Body)
        assertEquals(5, set.values.size)
        assertEquals(23, set.values["central"])
        assertEquals(14, set.values["south"])
    }

    @Test
    fun parsesIsoTimestamp() {
        val set = NeaApi.parse(Metric.PSI, psiBody)
        // 2026-09-02T16:00:00+08:00 == 08:00:00 UTC
        val expected = java.time.OffsetDateTime.parse("2026-09-02T16:00:00+08:00")
            .toInstant().toEpochMilli()
        assertEquals(expected, set.timestampMillis)
    }
}
