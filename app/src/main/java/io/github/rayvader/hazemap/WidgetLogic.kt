package io.github.rayvader.hazemap

import kotlin.math.roundToInt

/** What a home screen widget shows. Any part can be missing when its data could not be loaded. */
sealed interface WidgetModel {
    /** The place's name. */
    val placeName: String

    /**
     * What the widget's place line says. Singapore says its forecast area ("Bedok"). Any other place comes
     * with its country ("Kyoto, Japan"), so it is clear where the weather is from.
     */
    val placeLabel: String

    /** IANA zone of the place. The widget's clock, its date and its "recorded" time all use it. */
    val zoneId: String

    val tempText: String

    /** The weather in words, or "--". */
    val conditionText: String

    /** When the data was recorded (Singapore) or fetched (a city). */
    val recordedMillis: Long?

    /** The sky for this weather. Singapore also looks at [nowMillis]: its night is a fixed 7 pm to 7 am. */
    fun scene(nowMillis: Long): SkyScene
}

/** Singapore: the average of all NEA stations, the NEA forecast for one area, and the PSI. */
data class SingaporeModel(
    val areaName: String,
    /** Mean of every NEA station that reported, in °C. */
    val avgTempC: Double?,
    /** NEA 2-hour forecast text for the area, such as "Partly Cloudy (Day)". */
    val condition: String?,
    /** PSI for the area's region. */
    val psi: Int?,
    /** When NEA recorded the PSI reading, or the temperature if there is no PSI. */
    override val recordedMillis: Long?
) : WidgetModel {
    override val placeName: String get() = areaName
    override val placeLabel: String get() = areaName
    override val zoneId: String get() = TimeFmt.SGT.id
    override val tempText: String get() = avgTempC?.let { "${it.roundToInt()}°" } ?: "--°"
    override val conditionText: String get() = WidgetLogic.conditionLabel(condition) ?: "--"
    val band: Band? get() = psi?.let { Band.forPsi(it) }

    override fun scene(nowMillis: Long): SkyScene = WidgetLogic.scene(condition, nowMillis)
}

/** A saved city: Open-Meteo's temperature, weather and humidity there. There is no PSI outside Singapore. */
data class CityModel(
    val place: Place,
    val tempC: Double?,
    /** WMO weather code. */
    val code: Int?,
    /** Whether the sun is up at the place, as Open-Meteo reports it. */
    val isDay: Boolean,
    /** Relative humidity in percent. */
    val humidity: Int?,
    override val zoneId: String,
    override val recordedMillis: Long?
) : WidgetModel {
    override val placeName: String get() = place.name
    override val placeLabel: String get() = place.nameAndCountry
    override val tempText: String get() = tempC?.let { "${it.roundToInt()}°" } ?: "--°"
    override val conditionText: String get() = Wmo.label(code)

    /**
     * A city's night is when the sun is down there, not a fixed hour. The condition is asked for by day, since
     * the app's icons turn partly cloudy into cloud after dark and the sky has a night version of its own.
     */
    override fun scene(nowMillis: Long): SkyScene = WidgetLogic.sceneOf(Wmo.condition(code, isDay = true), night = !isDay)
}

/**
 * The sky colour and moving picture a widget shows. [key] names the drawables widget_sky_<key> and
 * scene_<key>, and is what the smoke test's "widget_scene" extra takes. After dark every weather has its
 * own night sky, lit by the glow of the moon; [NIGHT] itself is the clear one.
 */
enum class SkyScene(val key: String) {
    SUN("sun"),
    PARTLY("partly"),
    CLOUD("cloud"),
    RAIN("rain"),
    STORM("storm"),
    HAZE("haze"),
    WIND("wind"),
    NIGHT("night"),
    PARTLY_NIGHT("partly_night"),
    CLOUD_NIGHT("cloud_night"),
    RAIN_NIGHT("rain_night"),
    STORM_NIGHT("storm_night"),
    HAZE_NIGHT("haze_night"),
    WIND_NIGHT("wind_night");

    /** True for the skies of the night. */
    val isNight: Boolean get() = key.endsWith("night")

    companion object {
        fun fromKey(key: String?): SkyScene? = entries.firstOrNull { it.key == key }
    }
}

object WidgetLogic {

    private const val NIGHT_FROM_HOUR = 19
    private const val NIGHT_UNTIL_HOUR = 7
    private val DAY_OR_NIGHT = Regex("""\s*\((day|night)\)""", RegexOption.IGNORE_CASE)

    /** The Singapore widget sky is dark from 7 pm to 7 am, Singapore time. */
    fun isNight(atMillis: Long): Boolean {
        val hour = TimeFmt.hourOfDay(atMillis)
        return hour >= NIGHT_FROM_HOUR || hour < NIGHT_UNTIL_HOUR
    }

    /**
     * The sky for an NEA forecast line at a time. After dark every weather shows its night sky, so no sun
     * shines at midnight. NEA's own "(Night)" wording also keeps the night sky a little past 7 am.
     */
    fun scene(forecast: String?, atMillis: Long): SkyScene {
        val condition = Condition.fromText(forecast)
        // The app's icons have no night version of partly cloudy, so for them NEA's "Partly Cloudy (Night)"
        // is plain cloud. The sky has one.
        if (condition == Condition.CLOUD && forecast.orEmpty().contains("partly", ignoreCase = true)) {
            return SkyScene.PARTLY_NIGHT
        }
        return sceneOf(condition, night = isNight(atMillis))
    }

    /** The sky for a condition. [night] gives the night version of it: a clear sky shows the moon, and so on. */
    fun sceneOf(condition: Condition, night: Boolean): SkyScene = when (condition) {
        Condition.SUN -> if (night) SkyScene.NIGHT else SkyScene.SUN
        Condition.MOON -> SkyScene.NIGHT
        Condition.PARTLY -> if (night) SkyScene.PARTLY_NIGHT else SkyScene.PARTLY
        Condition.CLOUD, Condition.SNOW -> if (night) SkyScene.CLOUD_NIGHT else SkyScene.CLOUD
        Condition.RAIN -> if (night) SkyScene.RAIN_NIGHT else SkyScene.RAIN
        Condition.STORM -> if (night) SkyScene.STORM_NIGHT else SkyScene.STORM
        Condition.HAZE -> if (night) SkyScene.HAZE_NIGHT else SkyScene.HAZE
        Condition.WIND -> if (night) SkyScene.WIND_NIGHT else SkyScene.WIND
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
    ): SingaporeModel {
        val psiValue = psi?.values?.get(area.region.key)
        val recorded = if (psiValue != null) psi.timestampMillis else temperature?.timestampMillis
        return SingaporeModel(
            areaName = area.name,
            avgTempC = averageTemp(temperature),
            condition = forecast?.forArea(area.name),
            psi = psiValue,
            recordedMillis = recorded
        )
    }

    /** A saved city. [weather] is null when nothing has loaded yet; [fetchedAtMillis] is when it was fetched. */
    fun buildCity(place: Place, weather: PlaceWeather?, fetchedAtMillis: Long?): CityModel = CityModel(
        place = place,
        tempC = weather?.tempC,
        code = weather?.code,
        isDay = weather?.isDay ?: true,
        humidity = weather?.humidity,
        zoneId = TimeFmt.zoneOrDefault(weather?.timezone ?: place.timezone).id,
        recordedMillis = fetchedAtMillis
    )
}
