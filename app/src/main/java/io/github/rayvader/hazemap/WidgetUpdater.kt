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
import android.content.res.Configuration
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import android.util.TypedValue
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
import kotlin.math.roundToInt

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

    /** The home screen says how big a widget really is, once it is placed or resized: draw it to fit. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        WidgetUpdater.requestRefreshAll(context)
    }
}

class HazeWidgetSmall : HazeWidgetProvider()
class HazeWidgetWide : HazeWidgetProvider()
class HazeWidgetStrip : HazeWidgetProvider()

/**
 * Redraws every widget from what is saved on the phone, with no request to the network: when the shown
 * event starts or ends, after the calendars are ticked, and when a calendar changes. Only the event
 * line changes, so there is no weather to fetch.
 */
open class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(WidgetUpdater.BUDGET_MS) { WidgetUpdater.paintFromDisk(context) }
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

    /** Keeps the event line's tap targets apart from each widget's own. */
    private const val EVENT_REQUEST_BASE = 1_000_000

    /** Keeps the clock's tap targets apart from the rest. */
    private const val CLOCK_REQUEST_BASE = 2_000_000

    private class Docs(
        val forecast: Forecast2h?,
        val temperature: StationReadings?,
        val psi: ReadingSet?
    )

    /** Everything known for the places the widgets show: Singapore's documents, and each city's weather. */
    private class Data(
        val singapore: Docs?,
        val cities: Map<Long, Loaded<PlaceWeather>>
    )

    /** One widget on the home screen. */
    private class Placed(val id: Int, val shape: WidgetShape)

    /** The text and colours of the pill beside the temperature. */
    private class Badge(val text: String, val textColor: Int, val background: Int)

    /** The event line on one widget. */
    internal class EventLine(
        val event: CalendarEvent,
        /** "15:00 Lunch". */
        val text: String
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
        // Only what some widget shows is loaded: no NEA request for city widgets, no city request for Singapore ones.
        val wantSingapore = targets.values.any { it is WidgetTarget.Singapore }
        val places = targets.values.filterIsInstance<WidgetTarget.City>().map { it.place }.distinctBy { it.id }
        // "Follow my location": move to where the phone already knows it is. No new request, no GPS start.
        if (fetch && wantSingapore) FollowLocation.forWidgets(app)
        val area = savedArea(app)
        val now = System.currentTimeMillis()

        // The upcoming events on the ticked calendars: the same on every widget that has room for them.
        val events = if (placed.any { it.shape.hasEventLine }) {
            withContext(Dispatchers.IO) { PhoneCalendars.upcoming(app, now) }
        } else {
            emptyList()
        }

        val repo = WeatherRepository.get(app)
        // Paint what is already on disk, so a widget is never empty while the network answers.
        val cached = withContext(Dispatchers.IO) { readDisk(repo, wantSingapore, places) }
        draw(app, manager, placed, targets, area, cached, events, now)
        if (fetch) draw(app, manager, placed, targets, area, fetchAll(repo, wantSingapore, places, cached), events, now)
        scheduleNextChange(app, events, now)
    }

    private fun readDisk(repo: WeatherRepository, wantSingapore: Boolean, places: List<Place>): Data = Data(
        singapore = if (wantSingapore) {
            Docs(
                repo.peek(Endpoints.FORECAST_2H)?.value,
                repo.peek(Endpoints.TEMPERATURE)?.value,
                repo.peek(Endpoints.haze(Metric.PSI))?.value
            )
        } else {
            null
        },
        cities = places.mapNotNull { p -> repo.peek(Endpoints.placeForecast(p))?.let { p.id to it } }.toMap()
    )

    private suspend fun fetchAll(
        repo: WeatherRepository,
        wantSingapore: Boolean,
        places: List<Place>,
        cached: Data
    ): Data = coroutineScope {
        val singapore = if (wantSingapore) {
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
        val cities = places.map { place ->
            async { place.id to (tryLoad(repo, Endpoints.placeForecast(place)) ?: cached.cities[place.id]) }
        }
        Data(
            singapore = singapore?.await(),
            cities = cities.awaitAll().mapNotNull { (id, loaded) -> loaded?.let { id to it } }.toMap()
        )
    }

    /**
     * Redraws the widgets when a listed event starts or ends, or at midnight, so the lines never show a
     * finished event for long. Not a wake-up alarm: the lines only matter with the screen on, and the redraw
     * needs no network.
     */
    private fun scheduleNextChange(context: Context, events: List<CalendarEvent>, now: Long) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val redraw = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WidgetRefreshReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (events.isEmpty()) {
            alarms.cancel(redraw)
            return
        }
        val zone = ZoneId.systemDefault()
        val next = events.minOf { EventLogic.nextChangeMillis(it, now, zone) }
        alarms.set(AlarmManager.RTC, next + 1_000L, redraw)
    }

    /** Asks for one redraw of every widget from the phone's saved data, with the time a receiver is given. */
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
        events: List<CalendarEvent>,
        now: Long
    ) {
        // Times follow the phone's clock, as the Calendar app shows them.
        val zone = ZoneId.systemDefault()
        val allLines = events.map { EventLine(it, EventLogic.lineText(it, now, zone)) }
        for (w in placed) {
            val target = targets.getValue(w.id)
            val model = modelFor(target, area, data)
            val lines = if (w.shape.hasEventLine) allLines else emptyList()
            val heightDp = heightOf(context, manager, w.id)
            val widthDp = widthOf(context, manager, w.id)
            // The smoke test reads these lines to find the id of each widget. No event details: they stay private.
            Log.i(
                TAG,
                "draw id=${w.id} shape=${w.shape.key} place=${model.placeName} zone=${model.zoneId} " +
                    "events=${lines.size} h=$heightDp w=$widthDp"
            )
            manager.updateAppWidget(w.id, views(context, w.shape, model, w.id, lines, now, heightDp, widthDp = widthDp))
        }
    }

    /**
     * The widget's height in dp as the home screen reports it, or 0 when it has not said yet. The home
     * screen gives two: the max height is the one with the phone upright, the min height the one on its side.
     */
    private fun heightOf(context: Context, manager: AppWidgetManager, widgetId: Int): Int {
        val options = manager.getAppWidgetOptions(widgetId)
        return WidgetFits.heightFor(
            uprightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
            sidewaysDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
            onItsSide = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        )
    }

    /**
     * The widget's width in dp as the home screen reports it, or 0 when it has not said yet. The min width
     * is the one with the phone upright, the max width the one on its side.
     */
    private fun widthOf(context: Context, manager: AppWidgetManager, widgetId: Int): Int {
        val options = manager.getAppWidgetOptions(widgetId)
        return WidgetFits.widthFor(
            uprightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
            sidewaysDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
            onItsSide = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        )
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
        events: List<EventLine> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
        heightDp: Int = 0,
        /** Draws this sky whatever the weather. Only the emulator test's preview sets it. */
        forceScene: SkyScene? = null,
        /** How wide the home screen made the widget, in dp; 0 when it has not said. */
        widthDp: Int = 0
    ): RemoteViews {
        val v = RemoteViews(context.packageName, shape.layout)
        val clock = model.recordedMillis?.let { TimeFmt.clockIn(it, model.zoneId) }
        // A short widget drops the event line (and, if even that is not enough, the date) and shrinks the rest;
        // the Strip has no fit.
        val fontScale = context.resources.configuration.fontScale
        val fit = WidgetFits.of(shape, heightDp, events.isNotEmpty(), fontScale, widthDp)
        // Where the date goes, and how many events get a line.
        val plan = WidgetRows.plan(shape, heightDp, widthDp, fontScale, fit, events.size)
        if (fit != null) applyFit(context, v, shape, fit, plan)
        placeDate(v, plan)
        val scene = forceScene ?: sceneOverride ?: model.scene(nowMillis)
        if (shape == WidgetShape.WIDE) placeWideParts(context, v, plan, WidgetRows.pictureScale(shape, heightDp, fit, fontScale, scene))

        // The sky colour, and the one moving picture for this weather.
        v.setInt(R.id.wRoot, "setBackgroundResource", skyOf(scene))
        for (s in SkyScene.entries) {
            v.setViewVisibility(pictureOf(s), if (s == scene) View.VISIBLE else View.GONE)
        }

        // The clock and date tick by themselves, in the time of the place the widget shows.
        v.setString(R.id.wClock, "setTimeZone", model.zoneId)
        v.setString(R.id.wDate, "setTimeZone", model.zoneId)
        v.setString(R.id.wDateWrapped, "setTimeZone", model.zoneId)

        v.setTextViewText(R.id.wTemp, model.tempText)
        v.setTextViewText(R.id.wCondition, model.conditionText)

        // The Square and the Strip show the time of the reading beside the pill. The Wide leaves it out:
        // its place shares a line with the temperature and the weather, and the room goes to the clock.
        v.setTextViewText(R.id.wPlace, model.placeLabel)
        if (shape != WidgetShape.WIDE) v.setTextViewText(R.id.wWhen, clock ?: "--")

        val badge = badgeOf(context, model)
        v.setTextViewText(R.id.wBadge, badge.text)
        v.setTextColor(R.id.wBadge, badge.textColor)
        v.setInt(R.id.wBadge, "setBackgroundResource", badge.background)

        v.setContentDescription(R.id.wRoot, summaryOf(context, model, clock))
        v.setOnClickPendingIntent(R.id.wRoot, openIntent(context, model, widgetId))
        // The clock and the date open the phone's Clock app; the rest of the widget opens this app.
        v.setOnClickPendingIntent(R.id.wClockTap, clockIntent(context, model, widgetId))
        // The strip has no event lines in its layout: setting them there would stop it drawing.
        if (shape.hasEventLine) showEvents(context, v, shape, events, plan, widgetId, model)
        return v
    }

    /** The plan a widget of this size is drawn with: the emulator test's preview logs it and checks the drawing against it. */
    internal fun planOf(context: Context, shape: WidgetShape, heightDp: Int, widthDp: Int, eventCount: Int): RowPlan {
        val fontScale = context.resources.configuration.fontScale
        val fit = WidgetFits.of(shape, heightDp, eventCount > 0, fontScale, widthDp)
        return WidgetRows.plan(shape, heightDp, widthDp, fontScale, fit, eventCount)
    }

    /** Sizes the clock and the numbers, trims the space at the top and bottom, and shows the date when there is room. */
    private fun applyFit(context: Context, v: RemoteViews, shape: WidgetShape, fit: WidgetFit, plan: RowPlan) {
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt()
        v.setTextViewTextSize(R.id.wClock, TypedValue.COMPLEX_UNIT_SP, plan.clockSp.toFloat())
        v.setTextViewTextSize(R.id.wTemp, TypedValue.COMPLEX_UNIT_SP, fit.tempSp.toFloat())
        // The side space is what each layout has in XML (start and end); only the top and the bottom change.
        // setViewPadding takes left and right, so a right-to-left phone gets start and end the other way round.
        val start = px(if (shape == WidgetShape.WIDE) 16 else 14)
        val end = px(if (shape == WidgetShape.WIDE) 14 else 12)
        val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        v.setViewPadding(R.id.wTopBlock, if (rtl) 0 else start, 0, if (rtl) start else 0, 0)
        // The top space sits inside the clock's tap area, so a short widget's clock is still easy to tap.
        v.setViewPadding(R.id.wClockTap, 0, px(fit.padTopDp), 0, 0)
        v.setViewPadding(
            R.id.wBottomBlock,
            if (rtl) end else start,
            0,
            if (rtl) start else end,
            px(fit.padBottomDp)
        )
    }

    /**
     * The Wide's own spacing: the space above the event lines (none beside the clock, 4 dp under the date), and
     * the sky picture, shrunk towards the top right corner on a short widget so its art stays above the PSI pill.
     */
    private fun placeWideParts(context: Context, v: RemoteViews, plan: RowPlan, pictureScale: Float) {
        val density = context.resources.displayMetrics.density
        v.setViewPadding(R.id.wEvents, 0, (WidgetRows.eventsTopDp(plan.dateSpot) * density).roundToInt(), 0, 0)
        v.setFloat(R.id.wPicture, "setPivotX", WidgetRows.PICTURE_BOX_DP * density)
        v.setFloat(R.id.wPicture, "setPivotY", 0f)
        v.setFloat(R.id.wPicture, "setScaleX", pictureScale)
        v.setFloat(R.id.wPicture, "setScaleY", pictureScale)
    }

    /** The date on the clock's row, or under the clock (see [WidgetRows]); hidden only when there is no room for it. */
    private fun placeDate(v: RemoteViews, plan: RowPlan) {
        val beside = plan.dateSpot == DateSpot.BESIDE
        v.setViewVisibility(R.id.wDate, if (plan.showDate && beside) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.wDateWrapped, if (plan.showDate && !beside) View.VISIBLE else View.GONE)
    }

    /**
     * "15:00 Lunch" behind a small calendar picture, one line for each of [plan]'s events. When some are left
     * over, a small chip at the end of the last line counts them ("+4 more"). The list stays hidden when there is
     * nothing to show.
     */
    private fun showEvents(
        context: Context,
        v: RemoteViews,
        shape: WidgetShape,
        events: List<EventLine>,
        plan: RowPlan,
        widgetId: Int,
        model: WidgetModel
    ) {
        // A redraw may reuse the views the home screen already has: clear the old lines first.
        v.removeAllViews(R.id.wEvents)
        if (plan.lines == 0) {
            v.setViewVisibility(R.id.wEvents, View.GONE)
            return
        }
        v.setViewVisibility(R.id.wEvents, View.VISIBLE)
        // Every line opens the calendar app on today, not the event (see calendarIntent).
        val open = calendarIntent(context, model, widgetId)
        val shown = events.take(plan.events)
        shown.forEachIndexed { i, line ->
            val row = RemoteViews(context.packageName, R.layout.widget_event_row)
            row.setTextViewText(R.id.wEventText, line.text)
            val last = i == shown.lastIndex
            if (last && plan.more > 0) {
                // The Square is narrow: its chip is just "+4", so the title keeps its room.
                val chip = context.getString(
                    if (shape == WidgetShape.SMALL) R.string.widget_event_more_short else R.string.widget_event_more,
                    plan.more
                )
                row.setViewVisibility(R.id.wEventMore, View.VISIBLE)
                row.setTextViewText(R.id.wEventMore, chip)
                // The Square has 154 dp for the row: there the line drops its calendar picture and the chip is
                // tighter, so the event's text keeps its room (at 115% text, "Sun 15:30 Dentist" and "+4" fit).
                val density = context.resources.displayMetrics.density
                fun px(dp: Int) = (dp * density).roundToInt()
                val small = shape == WidgetShape.SMALL
                if (small) row.setViewVisibility(R.id.wEventIcon, View.GONE)
                row.setViewPadding(R.id.wEventText, 0, 0, px(if (small) 3 else 6), 0)
                val pad = px(if (small) 3 else 5)
                row.setViewPadding(R.id.wEventMore, pad, px(1), pad, px(1))
                row.setContentDescription(R.id.wEventRow, context.getString(R.string.widget_event_desc_more, line.text, plan.more))
            } else {
                row.setContentDescription(
                    R.id.wEventRow,
                    context.getString(if (i == 0) R.string.widget_event_desc else R.string.widget_event_other_desc, line.text)
                )
            }
            row.setOnClickPendingIntent(R.id.wEventRow, open)
            v.addView(R.id.wEvents, row)
        }
    }

    /**
     * The phone's calendar app at its usual view on today, never one event's page: the time view, else the
     * calendar category, else Samsung's or Google's Calendar by name, else this app (see [CalendarApps.choose]).
     * Each widget has its own request code, apart from the clock's and the widget's own.
     */
    private fun calendarIntent(context: Context, model: WidgetModel, widgetId: Int): PendingIntent {
        val pm = context.packageManager
        val today = Intent(
            Intent.ACTION_VIEW,
            ContentUris.withAppendedId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build(), System.currentTimeMillis())
        )
        val main = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
        val launch = CalendarApps.PACKAGES.mapNotNull { pkg -> pm.getLaunchIntentForPackage(pkg)?.let { pkg to it } }.toMap()
        val choice = CalendarApps.choose(
            opensToday = today.resolveActivity(pm) != null,
            hasCalendarApp = main.resolveActivity(pm) != null,
            launchable = launch.keys
        )
        val intent = when (choice) {
            CalendarTap.Today -> today
            CalendarTap.MainCalendar -> main
            is CalendarTap.Package -> launch.getValue(choice.name)
            CalendarTap.App -> return openIntent(context, model, widgetId)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            EVENT_REQUEST_BASE + widgetId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Tapping a widget opens its place: the main screen for Singapore, the city's page for a city. */
    /**
     * The phone's Clock app: its alarms screen, else Samsung's or Google's Clock by name, else this app
     * (see [ClockApps.choose]). Each widget has its own request code, so taps on two widgets never clash.
     */
    private fun clockIntent(context: Context, model: WidgetModel, widgetId: Int): PendingIntent {
        val pm = context.packageManager
        val alarms = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        val launch = ClockApps.PACKAGES.mapNotNull { pkg -> pm.getLaunchIntentForPackage(pkg)?.let { pkg to it } }.toMap()
        val intent = when (val choice = ClockApps.choose(alarms.resolveActivity(pm) != null, launch.keys)) {
            ClockChoice.Alarms -> alarms
            is ClockChoice.Package -> launch.getValue(choice.name)
            ClockChoice.App -> return openIntent(context, model, widgetId)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            CLOCK_REQUEST_BASE + widgetId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

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
            model.placeLabel,
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
        SkyScene.PARTLY_NIGHT -> R.drawable.widget_sky_partly_night
        SkyScene.CLOUD_NIGHT -> R.drawable.widget_sky_cloud_night
        SkyScene.RAIN_NIGHT -> R.drawable.widget_sky_rain_night
        SkyScene.STORM_NIGHT -> R.drawable.widget_sky_storm_night
        SkyScene.HAZE_NIGHT -> R.drawable.widget_sky_haze_night
        SkyScene.WIND_NIGHT -> R.drawable.widget_sky_wind_night
    }

    /** Every widget layout holds every picture; each is a progress bar that plays its scene. */
    private fun pictureOf(scene: SkyScene): Int = when (scene) {
        SkyScene.SUN -> R.id.wSceneSun
        SkyScene.PARTLY -> R.id.wScenePartly
        SkyScene.CLOUD -> R.id.wSceneCloud
        SkyScene.RAIN -> R.id.wSceneRain
        SkyScene.STORM -> R.id.wSceneStorm
        SkyScene.HAZE -> R.id.wSceneHaze
        SkyScene.WIND -> R.id.wSceneWind
        SkyScene.NIGHT -> R.id.wSceneNight
        SkyScene.PARTLY_NIGHT -> R.id.wScenePartlyNight
        SkyScene.CLOUD_NIGHT -> R.id.wSceneCloudNight
        SkyScene.RAIN_NIGHT -> R.id.wSceneRainNight
        SkyScene.STORM_NIGHT -> R.id.wSceneStormNight
        SkyScene.HAZE_NIGHT -> R.id.wSceneHazeNight
        SkyScene.WIND_NIGHT -> R.id.wSceneWindNight
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
