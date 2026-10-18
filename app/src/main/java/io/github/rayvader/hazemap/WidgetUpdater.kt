package io.github.rayvader.hazemap

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

    internal fun views(context: Context, shape: WidgetShape, model: WidgetModel): RemoteViews {
        val v = RemoteViews(context.packageName, shape.layout)
        val condition = Condition.fromText(model.condition)
        val clock = model.recordedMillis?.let { TimeFmt.clock(it) }
        val band = model.band

        v.setTextViewText(R.id.wLocation, model.areaName)
        v.setTextViewText(R.id.wTemp, model.tempText)
        v.setImageViewResource(R.id.wIcon, Icons.forCondition(condition))
        v.setTextViewText(R.id.wCondition, model.condition ?: "--")

        // A wide widget has room for the word; the small ones keep just the clock.
        val time = when {
            clock == null -> "--"
            shape == WidgetShape.WIDE -> context.getString(R.string.widget_recorded, clock)
            else -> clock
        }
        v.setTextViewText(R.id.wTime, time)

        val badgeText = when {
            model.psi == null -> if (shape == WidgetShape.WIDE) "--" else context.getString(R.string.widget_psi_none)
            shape == WidgetShape.WIDE -> model.psi.toString()
            else -> context.getString(R.string.psi_value, model.psi)
        }
        v.setTextViewText(R.id.wPsi, badgeText)
        v.setTextColor(R.id.wPsi, band?.textColor ?: NO_DATA_TEXT)
        v.setInt(R.id.wBadgeBg, "setColorFilter", band?.color ?: NO_DATA_FILL)
        v.setTextViewText(R.id.wBand, band?.label ?: "")

        val summary = context.getString(
            R.string.widget_summary,
            model.areaName,
            model.tempText,
            model.condition ?: "--",
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

    private const val NO_DATA_FILL = 0xFF9E9E9E.toInt()
    private const val NO_DATA_TEXT = 0xFFFFFFFF.toInt()
}
