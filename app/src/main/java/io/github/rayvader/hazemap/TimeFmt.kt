package io.github.rayvader.hazemap

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * All weather in the app is shown in Singapore time, whatever the phone's zone is. Every time is
 * 24-hour, "18:00", in the app and on the widgets, whatever the phone is set to.
 */
object TimeFmt {
    val SGT: ZoneId = ZoneId.of("Asia/Singapore")

    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /** NEA timestamps look like 2026-10-18T10:45:00+08:00. */
    fun parseIso(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    /** "10:45", "18:00" */
    fun clock(millis: Long): String = clockFmt.format(Instant.ofEpochMilli(millis).atZone(SGT))

    /** The zone for an IANA id. An unknown id falls back to the phone's zone. */
    fun zoneOrDefault(zoneId: String): ZoneId =
        try {
            ZoneId.of(zoneId)
        } catch (e: Exception) {
            ZoneId.systemDefault()
        }

    /** The clock at a place, "21:15". An unknown zone id falls back to the phone's zone. */
    fun clockIn(millis: Long, zoneId: String): String =
        clockFmt.format(Instant.ofEpochMilli(millis).atZone(zoneOrDefault(zoneId)))

    /** "Mon" */
    fun dayShort(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(SGT).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    /** "2026-10-18", the form the data.gov.sg date filter takes. */
    fun isoDate(millis: Long): String = Instant.ofEpochMilli(millis).atZone(SGT).toLocalDate().toString()

    fun hourOfDay(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(SGT).hour

    /** Whole days since 1970 in Singapore, to compare calendar days. */
    fun dayIndex(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(SGT).toLocalDate().toEpochDay()
}
