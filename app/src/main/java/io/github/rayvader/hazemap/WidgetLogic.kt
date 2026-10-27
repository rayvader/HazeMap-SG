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

    /** The weather in words, "Partly Cloudy", or "--". */
    val conditionText: String get() = WidgetLogic.conditionLabel(condition) ?: "--"
}

/**
 * The sky colour and moving picture a widget shows. [key] names the drawables widget_sky_<key> and
 * scene_<key>, and is what the smoke test's "widget_scene" extra takes.
 */
enum class SkyScene(val key: String) {
    SUN("sun"),
    PARTLY("partly"),
    CLOUD("cloud"),
    RAIN("rain"),
    STORM("storm"),
    HAZE("haze"),
    WIND("wind"),
    NIGHT("night");

    companion object {
        fun fromKey(key: String?): SkyScene? = entries.firstOrNull { it.key == key }
    }
}

object WidgetLogic {

    private const val NIGHT_FROM_HOUR = 19
    private const val NIGHT_UNTIL_HOUR = 7
    private val DAY_OR_NIGHT = Regex("""\s*\((day|night)\)""", RegexOption.IGNORE_CASE)

    /** The widget sky is dark from 7 pm to 7 am, Singapore time. */
    fun isNight(atMillis: Long): Boolean {
        val hour = TimeFmt.hourOfDay(atMillis)
        return hour >= NIGHT_FROM_HOUR || hour < NIGHT_UNTIL_HOUR
    }

    /**
     * The sky for an NEA forecast line at a time. After dark a clear sky shows the night and a partly
     * cloudy one shows clouds, as NEA's own "(Night)" wording does, so no sun shines at midnight.
     */
    fun scene(condition: String?, atMillis: Long): SkyScene {
        val night = isNight(atMillis)
        return when (Condition.fromText(condition)) {
            Condition.SUN -> if (night) SkyScene.NIGHT else SkyScene.SUN
            Condition.MOON -> SkyScene.NIGHT
            Condition.PARTLY -> if (night) SkyScene.CLOUD else SkyScene.PARTLY
            Condition.CLOUD, Condition.SNOW -> SkyScene.CLOUD
            Condition.RAIN -> SkyScene.RAIN
            Condition.STORM -> SkyScene.STORM
            Condition.HAZE -> SkyScene.HAZE
            Condition.WIND -> SkyScene.WIND
        }
    }

    /** NEA's words without "(Day)" or "(Night)", which the sky already shows. Null when there are none. */
    fun conditionLabel(text: String?): String? =
        text?.replace(DAY_OR_NIGHT, "")?.trim()?.takeIf { it.isNotEmpty() }

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
