package io.github.rayvader.hazemap

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/** The three widget shapes. Each has its own entry in the phone's widget picker. */
enum class WidgetShape(
    val key: String,
    val layout: Int,
    val provider: Class<out AppWidgetProvider>,
    /** Has room for the next event from the phone's calendars. */
    val hasEventLine: Boolean
) {
    SMALL("small", R.layout.widget_small, HazeWidgetSmall::class.java, true),
    WIDE("wide", R.layout.widget_wide, HazeWidgetWide::class.java, true),
    STRIP("strip", R.layout.widget_strip, HazeWidgetStrip::class.java, false);

    companion object {
        fun fromKey(key: String?): WidgetShape? = entries.firstOrNull { it.key == key }
    }
}

abstract class HazeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // The receiver may be gone before the network answers, so keep it alive for the update.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(WidgetUpdater.BUDGET_MS) { WidgetUpdater.update(context) }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetTargetStore(context).remove(appWidgetIds)
    }
}

class HazeWidgetSmall : HazeWidgetProvider()
class HazeWidgetWide : HazeWidgetProvider()
class HazeWidgetStrip : HazeWidgetProvider()

/** Redraws every widget: when the shown event starts or ends, and after the calendars are ticked. */
open class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(WidgetUpdater.BUDGET_MS) { WidgetUpdater.update(context) }
            } finally {
                pending.finish()
            }
        }
    }
}

/** The calendar provider tells every app when a calendar changes. Only matters once calendars may be read. */
class CalendarChangedReceiver : WidgetRefreshReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PROVIDER_CHANGED || !PhoneCalendars.hasAccess(context)) return
        super.onReceive(context, intent)
    }
}

/**
 * Draws every widget on the home screen from the newest data: saved copy first, then a fresh fetch.
 * Each widget shows what [WidgetTargetStore] says: Singapore from NEA, or one saved city from Open-Meteo.
 */
object WidgetUpdater {
    /** A receiver may run about 10 s; stay under it. */
    const val BUDGET_MS = 9_000L

    private const val TAG = "HazeWidget"
    private const val MINUTE_MS = 60_000L

    /** Past this, the 2-hour forecast is over by the event's time: NEA's 24-hour forecast is needed. */
    private const val PAST_TWO_HOUR_MS = 90 * MINUTE_MS

    /** Past this, the 24-hour forecast may not reach either: the 4-day outlook is needed too. */
    private const val PAST_ONE_DAY_MS = 18 * 60 * MINUTE_MS

    /** Keeps the event line's tap targets apart from each widget's own. */
    private const val EVENT_REQUEST_BASE = 1_000_000

    private class Docs(
        val forecast: Forecast2h?,
        val temperature: StationReadings?,
        val psi: ReadingSet?
    )

    /**
     * Everything known for the places the widgets show: Singapore's documents, each city's weather,
     * and the longer NEA forecasts an event further off needs.
     */
    private class Data(
        val singapore: Docs?,
        val cities: Map<Long, Loaded<PlaceWeather>>,
        val nextDay: Forecast24?,
        val outlook: List<DayForecast>?
    )

    /** What to load, decided once per refresh, so nothing is asked for that no widget shows. */
    private class Needs(
        val singapore: Boolean,
        val places: List<Place>,
        val nextDay: Boolean,
        val outlook: Boolean
    )

    /** One widget on the home screen. */
    private class Placed(val id: Int, val shape: WidgetShape)

    /** The text and colours of the pill beside the temperature. */
    private class Badge(val text: String, val textColor: Int, val background: Int)

    /** The event line on one widget. */
    internal class EventLine(
        val event: CalendarEvent,
        /** "3 pm Lunch". */
        val text: String,
        /** The weather at the event's place and time, or null when no forecast reaches it. */
        val condition: Condition?
    )

    suspend fun update(context: Context) = refresh(context, fetch = true)

    /** Draws every widget from what is already saved on the phone, with no request to the network. */
    suspend fun paintFromDisk(context: Context) = refresh(context, fetch = false)

    private suspend fun refresh(context: Context, fetch: Boolean) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val placed = WidgetShape.entries.flatMap { shape ->
            idsOf(manager, app, shape).map { Placed(it, shape) }
        }
        if (placed.isEmpty()) return

        val store = WidgetTargetStore(app)
        store.keepOnly(placed.map { it.id })
        val targets = placed.associate { it.id to store.get(it.id) }
        val area = savedArea(app)
        val now = System.currentTimeMillis()

        // The next event is the same on every widget. Its weather comes from the place it names,
        // else from the place the widget shows.
        val event = if (placed.any { it.shape.hasEventLine }) {
            withContext(Dispatchers.IO) { PhoneCalendars.nextEvent(app, now) }
        } else {
            null
        }
        val eventPlaces: Map<Int, EventPlace> = if (event == null) {
            emptyMap()
        } else {
            val cities = (PlaceStore(app).all() + targets.values.filterIsInstance<WidgetTarget.City>().map { it.place })
                .distinctBy { it.id }
            placed.filter { it.shape.hasEventLine }
                .associate { w -> w.id to eventPlace(event, targets.getValue(w.id), cities, area) }
        }
        val needs = needsFor(targets.values, event, eventPlaces.values, now)

        val repo = WeatherRepository.get(app)
        // Paint what is already on disk, so a widget is never empty while the network answers.
        val cached = withContext(Dispatchers.IO) { readDisk(repo, needs) }
        draw(app, manager, placed, targets, area, cached, event, eventPlaces, now)
        if (fetch) draw(app, manager, placed, targets, area, fetchAll(repo, needs, cached), event, eventPlaces, now)
        scheduleNextChange(app, event, now)
    }

    private fun eventPlace(event: CalendarEvent, target: WidgetTarget, cities: List<Place>, area: Area): EventPlace =
        EventLogic.placeFor(event.location, cities) ?: when (target) {
            is WidgetTarget.Singapore -> EventPlace.SingaporeArea(area)
            is WidgetTarget.City -> EventPlace.City(target.place)
        }

    /**
     * Only what some widget shows is loaded: no NEA request for city widgets, no city request for
     * Singapore ones, and the longer NEA forecasts only for an event that is further off.
     */
    private fun needsFor(
        targets: Collection<WidgetTarget>,
        event: CalendarEvent?,
        eventPlaces: Collection<EventPlace>,
        now: Long
    ): Needs {
        val singaporeEvent = event != null && eventPlaces.any { it is EventPlace.SingaporeArea }
        val later = event != null && (event.allDay || event.beginMillis > now + PAST_TWO_HOUR_MS)
        val muchLater = event != null && (event.allDay || event.beginMillis > now + PAST_ONE_DAY_MS)
        val places = targets.filterIsInstance<WidgetTarget.City>().map { it.place } +
            eventPlaces.filterIsInstance<EventPlace.City>().map { it.place }
        return Needs(
            singapore = targets.any { it is WidgetTarget.Singapore } || singaporeEvent,
            places = places.distinctBy { it.id },
            nextDay = singaporeEvent && later,
            outlook = singaporeEvent && muchLater
        )
    }

    private fun readDisk(repo: WeatherRepository, needs: Needs): Data = Data(
        singapore = if (needs.singapore) {
            Docs(
                repo.peek(Endpoints.FORECAST_2H)?.value,
                repo.peek(Endpoints.TEMPERATURE)?.value,
                repo.peek(Endpoints.haze(Metric.PSI))?.value
            )
        } else {
            null
        },
        cities = needs.places.mapNotNull { p -> repo.peek(Endpoints.placeForecast(p))?.let { p.id to it } }.toMap(),
        nextDay = if (needs.nextDay) repo.peek(Endpoints.FORECAST_24H)?.value else null,
        outlook = if (needs.outlook) repo.peek(Endpoints.OUTLOOK)?.value else null
    )

    private suspend fun fetchAll(repo: WeatherRepository, needs: Needs, cached: Data): Data = coroutineScope {
        val singapore = if (needs.singapore) {
            async {
                val old = cached.singapore
                val f = async { tryLoad(repo, Endpoints.FORECAST_2H)?.value ?: old?.forecast }
                val t = async { tryLoad(repo, Endpoints.TEMPERATURE)?.value ?: old?.temperature }
                val p = async { tryLoad(repo, Endpoints.haze(Metric.PSI))?.value ?: old?.psi }
                Docs(f.await(), t.await(), p.await())
            }
        } else {
            null
        }
        // With these two, at most five data.gov.sg requests: the most it takes in 10 seconds.
        val nextDay = if (needs.nextDay) async { tryLoad(repo, Endpoints.FORECAST_24H)?.value ?: cached.nextDay } else null
        val outlook = if (needs.outlook) async { tryLoad(repo, Endpoints.OUTLOOK)?.value ?: cached.outlook } else null
        val cities = needs.places.map { place ->
            async { place.id to (tryLoad(repo, Endpoints.placeForecast(place)) ?: cached.cities[place.id]) }
        }
        Data(
            singapore = singapore?.await(),
            cities = cities.awaitAll().mapNotNull { (id, loaded) -> loaded?.let { id to it } }.toMap(),
            nextDay = nextDay?.await(),
            outlook = outlook?.await()
        )
    }

    /**
     * Redraws the widgets when the shown event starts or ends, or at midnight, so the line never
     * shows a finished event for long. Not a wake-up alarm: the line only matters with the screen on.
     */
    private fun scheduleNextChange(context: Context, event: CalendarEvent?, now: Long) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val redraw = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WidgetRefreshReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (event == null) {
            alarms.cancel(redraw)
            return
        }
        alarms.set(AlarmManager.RTC, EventLogic.nextChangeMillis(event, now, ZoneId.systemDefault()) + 1_000L, redraw)
    }

    /** Asks for one fresh update of every widget, with the time a receiver is given for it. */
    internal fun requestRefreshAll(context: Context) {
        context.sendBroadcast(Intent(context, WidgetRefreshReceiver::class.java))
    }

    /** The answer, or null when there is neither a network nor a saved copy. */
    private suspend fun <T> tryLoad(repo: WeatherRepository, endpoint: Endpoint<T>): Loaded<T>? =
        try {
            repo.load(endpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** Asks the home screen to offer adding a widget. Used by the emulator smoke test. */
    fun requestPin(context: Context, shape: WidgetShape) {
        val manager = AppWidgetManager.getInstance(context)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(context, shape.provider), null, null)
        }
    }

    /** True when [widgetId] is a widget of this app on the home screen. */
    internal fun isOurWidget(context: Context, widgetId: Int): Boolean {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return false
        val manager = AppWidgetManager.getInstance(context)
        return WidgetShape.entries.any { widgetId in idsOf(manager, context, it) }
    }

    /** Asks the widget's own provider for a fresh update, with the time a receiver is given for it. */
    internal fun requestRefresh(context: Context, widgetId: Int) {
        val manager = AppWidgetManager.getInstance(context)
        val shape = WidgetShape.entries.firstOrNull { widgetId in idsOf(manager, context, it) } ?: return
        context.sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setComponent(ComponentName(context, shape.provider))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
        )
    }

    internal fun savedArea(context: Context): Area {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return Areas.byName(prefs.getString(Prefs.KEY_AREA, null)) ?: Areas.default
    }

    private fun idsOf(manager: AppWidgetManager, context: Context, shape: WidgetShape): IntArray =
        manager.getAppWidgetIds(ComponentName(context, shape.provider))

    private fun draw(
        context: Context,
        manager: AppWidgetManager,
        placed: List<Placed>,
        targets: Map<Int, WidgetTarget>,
        area: Area,
        data: Data,
        event: CalendarEvent?,
        eventPlaces: Map<Int, EventPlace>,
        now: Long
    ) {
        for (w in placed) {
            val target = targets.getValue(w.id)
            val model = modelFor(target, area, data)
            val place = eventPlaces[w.id]
            val line = if (event != null && place != null) eventLine(event, place, data, now) else null
            // The smoke test reads these lines to find the id of each widget. No event details: they stay private.
            Log.i(TAG, "draw id=${w.id} shape=${w.shape.key} place=${model.placeName} zone=${model.zoneId} event=${line != null}")
            manager.updateAppWidget(w.id, views(context, w.shape, model, w.id, line, now))
        }
    }

    private fun eventLine(event: CalendarEvent, place: EventPlace, data: Data, now: Long): EventLine {
        val condition = when (place) {
            is EventPlace.SingaporeArea ->
                EventLogic.singaporeWeather(event, place.area, now, data.singapore?.forecast, data.nextDay, data.outlook)
                    ?.let { Condition.fromText(it) }
            is EventPlace.City -> EventLogic.cityWeather(event, data.cities[place.place.id]?.value, now)
        }
        // Times follow the phone's clock, as the Calendar app shows them.
        return EventLine(event, EventLogic.lineText(event, now, ZoneId.systemDefault()), condition)
    }

    private fun modelFor(target: WidgetTarget, area: Area, data: Data): WidgetModel = when (target) {
        is WidgetTarget.Singapore ->
            WidgetLogic.build(area, data.singapore?.forecast, data.singapore?.temperature, data.singapore?.psi)
        is WidgetTarget.City -> {
            val loaded = data.cities[target.place.id]
            WidgetLogic.buildCity(target.place, loaded?.value, loaded?.savedAtMs)
        }
    }

    /**
     * Draws every widget with this sky whatever the weather, until the app process ends.
     * Set only by the emulator smoke test, to look at all eight pictures.
     */
    @Volatile
    internal var sceneOverride: SkyScene? = null

    internal fun views(
        context: Context,
        shape: WidgetShape,
        model: WidgetModel,
        widgetId: Int,
        event: EventLine? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): RemoteViews {
        val v = RemoteViews(context.packageName, shape.layout)
        val scene = sceneOverride ?: model.scene(nowMillis)
        val clock = model.recordedMillis?.let { TimeFmt.clockIn(it, model.zoneId) }

        // The sky colour, and the one moving picture for this weather.
        v.setInt(R.id.wRoot, "setBackgroundResource", skyOf(scene))
        for (s in SkyScene.entries) {
            v.setViewVisibility(pictureOf(s), if (s == scene) View.VISIBLE else View.GONE)
        }

        // The clock and date tick by themselves, in the time of the place the widget shows.
        v.setString(R.id.wClock, "setTimeZone", model.zoneId)
        v.setString(R.id.wDate, "setTimeZone", model.zoneId)

        v.setTextViewText(R.id.wTemp, model.tempText)
        v.setTextViewText(R.id.wCondition, model.conditionText)

        // The wide widget puts the time after the place; the others show it beside the pill.
        if (shape == WidgetShape.WIDE) {
            v.setTextViewText(R.id.wPlace, placeLine(context, model, clock))
        } else {
            v.setTextViewText(R.id.wPlace, model.placeName)
            v.setTextViewText(R.id.wWhen, clock ?: "--")
        }

        val badge = badgeOf(context, model)
        v.setTextViewText(R.id.wBadge, badge.text)
        v.setTextColor(R.id.wBadge, badge.textColor)
        v.setInt(R.id.wBadge, "setBackgroundResource", badge.background)

        v.setContentDescription(R.id.wRoot, summaryOf(context, model, clock))
        v.setOnClickPendingIntent(R.id.wRoot, openIntent(context, model, widgetId))
        // The strip has no event line in its layout: setting one there would stop it drawing.
        if (shape.hasEventLine) showEvent(context, v, shape, event, widgetId)
        return v
    }

    /** "3 pm Lunch", after a small picture of its weather; the wide widget adds the weather in words. */
    private fun showEvent(context: Context, v: RemoteViews, shape: WidgetShape, line: EventLine?, widgetId: Int) {
        if (line == null) {
            v.setViewVisibility(R.id.wEventRow, View.GONE)
            return
        }
        v.setViewVisibility(R.id.wEventRow, View.VISIBLE)
        val condition = line.condition
        v.setImageViewResource(R.id.wEventIcon, if (condition == null) R.drawable.ic_event else Icons.forCondition(condition))
        v.setTextViewText(R.id.wEventText, line.text)
        val word = if (condition != null && shape == WidgetShape.WIDE) EventLogic.word(condition) else null
        v.setViewVisibility(R.id.wEventWeather, if (word == null) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.wEventWeather, if (word == null) "" else " · $word")
        val spoken = listOfNotNull(line.text, condition?.let { EventLogic.word(it) }).joinToString(". ")
        v.setContentDescription(R.id.wEventRow, context.getString(R.string.widget_event_desc, spoken))
        eventIntent(context, line.event, widgetId)?.let { v.setOnClickPendingIntent(R.id.wEventRow, it) }
    }

    /** Opens the event in the phone's Calendar app. Null when no app can, so a tap opens this app instead. */
    private fun eventIntent(context: Context, event: CalendarEvent, widgetId: Int): PendingIntent? {
        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMillis)
        if (intent.resolveActivity(context.packageManager) == null) return null
        return PendingIntent.getActivity(
            context,
            EVENT_REQUEST_BASE + widgetId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Tapping a widget opens its place: the main screen for Singapore, the city's page for a city. */
    private fun openIntent(context: Context, model: WidgetModel, widgetId: Int): PendingIntent {
        val intent = when (model) {
            is SingaporeModel -> Intent(context, MainActivity::class.java)
            is CityModel -> PlaceActivity.intent(context, model.place)
        }
        // One request code per widget: two city widgets must not share a PendingIntent, or both would open one city.
        return PendingIntent.getActivity(
            context,
            widgetId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** "Bedok · PSI at 2:00 pm". Without a PSI, or in a city, it is the place and the time of the reading. */
    private fun placeLine(context: Context, model: WidgetModel, clock: String?): String = when {
        clock == null -> model.placeName
        model is SingaporeModel && model.psi != null ->
            context.getString(R.string.widget_place_psi_time, model.placeName, clock)
        else -> context.getString(R.string.widget_place_time, model.placeName, clock)
    }

    /** The pill: the PSI in its band colour for Singapore, the humidity for a city (it has no PSI). */
    private fun badgeOf(context: Context, model: WidgetModel): Badge = when (model) {
        is SingaporeModel -> Badge(
            text = model.psi?.let { context.getString(R.string.psi_value, it) }
                ?: context.getString(R.string.widget_psi_none),
            textColor = model.band?.textColor ?: NO_DATA_TEXT,
            background = badgeBackground(model.band)
        )
        is CityModel -> Badge(
            text = model.humidity?.let { context.getString(R.string.chip_humid, it) }
                ?: context.getString(R.string.widget_humid_none),
            textColor = NO_DATA_TEXT,
            background = R.drawable.widget_badge_city
        )
    }

    private fun summaryOf(context: Context, model: WidgetModel, clock: String?): String = when (model) {
        is SingaporeModel -> context.getString(
            R.string.widget_summary,
            model.areaName,
            model.tempText,
            model.conditionText,
            model.psi?.toString() ?: "--",
            clock ?: "--"
        )
        is CityModel -> context.getString(
            R.string.widget_summary_city,
            model.placeName,
            model.tempText,
            model.conditionText,
            model.humidity?.let { "$it%" } ?: "--",
            clock ?: "--"
        )
    }

    private fun skyOf(scene: SkyScene): Int = when (scene) {
        SkyScene.SUN -> R.drawable.widget_sky_sun
        SkyScene.PARTLY -> R.drawable.widget_sky_partly
        SkyScene.CLOUD -> R.drawable.widget_sky_cloud
        SkyScene.RAIN -> R.drawable.widget_sky_rain
        SkyScene.STORM -> R.drawable.widget_sky_storm
        SkyScene.HAZE -> R.drawable.widget_sky_haze
        SkyScene.WIND -> R.drawable.widget_sky_wind
        SkyScene.NIGHT -> R.drawable.widget_sky_night
    }

    /** Every widget layout holds all eight pictures; each is a progress bar that plays its scene. */
    private fun pictureOf(scene: SkyScene): Int = when (scene) {
        SkyScene.SUN -> R.id.wSceneSun
        SkyScene.PARTLY -> R.id.wScenePartly
        SkyScene.CLOUD -> R.id.wSceneCloud
        SkyScene.RAIN -> R.id.wSceneRain
        SkyScene.STORM -> R.id.wSceneStorm
        SkyScene.HAZE -> R.id.wSceneHaze
        SkyScene.WIND -> R.id.wSceneWind
        SkyScene.NIGHT -> R.id.wSceneNight
    }

    /** A RemoteViews cannot tint a drawable on every Android version, so each band has its own rounded fill. */
    private fun badgeBackground(band: Band?): Int = when (band) {
        Band.GOOD -> R.drawable.widget_badge_good
        Band.MODERATE -> R.drawable.widget_badge_moderate
        Band.UNHEALTHY -> R.drawable.widget_badge_unhealthy
        Band.VERY_UNHEALTHY -> R.drawable.widget_badge_very_unhealthy
        Band.HAZARDOUS -> R.drawable.widget_badge_hazardous
        null -> R.drawable.widget_badge_none
    }

    private const val NO_DATA_TEXT = 0xFFFFFFFF.toInt()
}
