package io.github.rayvader.hazemap

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The three widget shapes. Each has its own entry in the phone's widget picker. */
enum class WidgetShape(val key: String, val layout: Int, val provider: Class<out AppWidgetProvider>) {
    SMALL("small", R.layout.widget_small, HazeWidgetSmall::class.java),
    WIDE("wide", R.layout.widget_wide, HazeWidgetWide::class.java),
    STRIP("strip", R.layout.widget_strip, HazeWidgetStrip::class.java);

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
}

class HazeWidgetSmall : HazeWidgetProvider()
class HazeWidgetWide : HazeWidgetProvider()
class HazeWidgetStrip : HazeWidgetProvider()

/** Draws every widget on the home screen from the newest data: saved copy first, then a fresh fetch. */
object WidgetUpdater {
    /** A receiver may run about 10 s; stay under it. */
    const val BUDGET_MS = 9_000L

    private class Docs(
        val forecast: Forecast2h?,
        val temperature: StationReadings?,
        val psi: ReadingSet?
    )

    suspend fun update(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        if (WidgetShape.entries.none { idsOf(manager, app, it).isNotEmpty() }) return

        val repo = WeatherRepository.get(app)
        val area = savedArea(app)

        // Paint what is already on disk, so the widget is never empty while the network answers.
        val cached = withContext(Dispatchers.IO) {
            Docs(
                repo.peek(Endpoints.FORECAST_2H)?.value,
                repo.peek(Endpoints.TEMPERATURE)?.value,
                repo.peek(Endpoints.haze(Metric.PSI))?.value
            )
        }
        draw(app, manager, WidgetLogic.build(area, cached.forecast, cached.temperature, cached.psi))

        val fresh = coroutineScope {
            val f = async { runCatching { repo.load(Endpoints.FORECAST_2H).value }.getOrNull() ?: cached.forecast }
            val t = async { runCatching { repo.load(Endpoints.TEMPERATURE).value }.getOrNull() ?: cached.temperature }
            val p = async { runCatching { repo.load(Endpoints.haze(Metric.PSI)).value }.getOrNull() ?: cached.psi }
            Docs(f.await(), t.await(), p.await())
        }
        draw(app, manager, WidgetLogic.build(area, fresh.forecast, fresh.temperature, fresh.psi))
    }

    /** Asks the home screen to offer adding a widget. Used by the emulator smoke test. */
    fun requestPin(context: Context, shape: WidgetShape) {
        val manager = AppWidgetManager.getInstance(context)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(context, shape.provider), null, null)
        }
    }

    private fun savedArea(context: Context): Area {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return Areas.byName(prefs.getString(Prefs.KEY_AREA, null)) ?: Areas.default
    }

    private fun idsOf(manager: AppWidgetManager, context: Context, shape: WidgetShape): IntArray =
        manager.getAppWidgetIds(ComponentName(context, shape.provider))

    private fun draw(context: Context, manager: AppWidgetManager, model: WidgetModel) {
        for (shape in WidgetShape.entries) {
            val ids = idsOf(manager, context, shape)
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, shape, model))
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
        nowMillis: Long = System.currentTimeMillis()
    ): RemoteViews {
        val v = RemoteViews(context.packageName, shape.layout)
        val scene = sceneOverride ?: WidgetLogic.scene(model.condition, nowMillis)
        val clock = model.recordedMillis?.let { TimeFmt.clock(it) }
        val band = model.band

        // The sky colour, and the one moving picture for this weather. The clock and date tick by themselves.
        v.setInt(R.id.wRoot, "setBackgroundResource", skyOf(scene))
        for (s in SkyScene.entries) {
            v.setViewVisibility(pictureOf(s), if (s == scene) View.VISIBLE else View.GONE)
        }

        v.setTextViewText(R.id.wTemp, model.tempText)
        v.setTextViewText(R.id.wCondition, model.conditionText)

        // The wide widget puts the PSI time after the area; the others show it beside the PSI.
        if (shape == WidgetShape.WIDE) {
            v.setTextViewText(R.id.wPlace, placeLine(context, model, clock))
        } else {
            v.setTextViewText(R.id.wPlace, model.areaName)
            v.setTextViewText(R.id.wWhen, clock ?: "--")
        }

        val badgeText = model.psi?.let { context.getString(R.string.psi_value, it) }
            ?: context.getString(R.string.widget_psi_none)
        v.setTextViewText(R.id.wPsi, badgeText)
        v.setTextColor(R.id.wPsi, band?.textColor ?: NO_DATA_TEXT)
        v.setInt(R.id.wPsi, "setBackgroundResource", badgeBackground(band))

        val summary = context.getString(
            R.string.widget_summary,
            model.areaName,
            model.tempText,
            model.conditionText,
            model.psi?.toString() ?: "--",
            clock ?: "--"
        )
        v.setContentDescription(R.id.wRoot, summary)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        v.setOnClickPendingIntent(R.id.wRoot, open)
        return v
    }

    /** "Bedok · PSI at 2:00 pm". Without a PSI, the time is when the temperature was recorded. */
    private fun placeLine(context: Context, model: WidgetModel, clock: String?): String = when {
        clock == null -> model.areaName
        model.psi != null -> context.getString(R.string.widget_place_psi_time, model.areaName, clock)
        else -> context.getString(R.string.widget_place_time, model.areaName, clock)
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
