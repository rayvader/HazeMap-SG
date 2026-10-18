package io.github.rayvader.hazemap

import kotlin.math.roundToInt

/** What a home screen widget shows. Any part can be missing when its data could not be loaded. */
data class WidgetModel(
    val areaName: String,
    /** Mean of every NEA station that reported, in °C. */
    val avgTempC: Double?,
    /** NEA 2-hour forecast text for the area, such as "Partly Cloudy (Day)". */
    val condition: String?,
    /** PSI for the area's region. */
    val psi: Int?,
    /** When NEA recorded the PSI reading, or the temperature if there is no PSI. */
    val recordedMillis: Long?
) {
    val tempText: String get() = avgTempC?.let { "${it.roundToInt()}°" } ?: "--°"
    val band: Band? get() = psi?.let { Band.forPsi(it) }
}

object WidgetLogic {

    /** Mean over all stations with a real number. Null when none reported. */
    fun averageTemp(readings: StationReadings?): Double? =
        readings?.values?.values?.filter { it.isFinite() }?.takeIf { it.isNotEmpty() }?.average()

    fun build(
        area: Area,
        forecast: Forecast2h?,
        temperature: StationReadings?,
        psi: ReadingSet?
    ): WidgetModel {
        val psiValue = psi?.values?.get(area.region.key)
        val recorded = if (psiValue != null) psi.timestampMillis else temperature?.timestampMillis
        return WidgetModel(
            areaName = area.name,
            avgTempC = averageTemp(temperature),
            condition = forecast?.forArea(area.name),
            psi = psiValue,
            recordedMillis = recorded
        )
    }
}
