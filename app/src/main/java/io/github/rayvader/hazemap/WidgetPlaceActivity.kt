package io.github.rayvader.hazemap

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import io.github.rayvader.hazemap.databinding.ActivityWidgetPlaceBinding
import io.github.rayvader.hazemap.databinding.ItemPlaceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The screen a widget opens when it is added, and again when it is reconfigured: pick Singapore or
 * one of the saved cities, and the calendars for the next-event line. The launcher starts it with
 * the widget's id.
 */
class WidgetPlaceActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var chosen = false
    private var current: WidgetTarget = WidgetTarget.Singapore
    private lateinit var b: ActivityWidgetPlaceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backing out changes nothing. When the widget was just added, the launcher then drops it.
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Only the launcher should reach this screen, and only for this app's own widgets.
        if (!WidgetUpdater.isOurWidget(this, widgetId)) {
            finish()
            return
        }

        val places = PlaceStore(this).all()
        b = ActivityWidgetPlaceBinding.inflate(layoutInflater)
        setContentView(b.root)
        current = WidgetTargetStore(this).get(widgetId)

        addRow(
            name = SINGAPORE,
            sub = getString(R.string.widget_pick_singapore_sub, WidgetUpdater.savedArea(this).name),
            selected = current is WidgetTarget.Singapore,
            target = WidgetTarget.Singapore
        )
        // A city the widget shows stays in the list even after it was taken off My places.
        val shown = (current as? WidgetTarget.City)?.place
        val cities = if (shown != null && places.none { it.id == shown.id }) listOf(shown) + places else places
        val now = System.currentTimeMillis()
        for (p in cities) {
            addRow(
                name = p.name,
                sub = listOf(p.subtitle, TimeFmt.clockIn(now, p.timezone)).filter { it.isNotEmpty() }.joinToString(" · "),
                selected = shown?.id == p.id,
                target = WidgetTarget.City(p)
            )
        }
        b.widgetPlaceHint.visibility = if (places.isEmpty()) View.VISIBLE else View.GONE
        b.widgetCalendarRow.setOnClickListener { startActivity(Intent(this, CalendarsActivity::class.java)) }
        // Keeps the place that is marked, after a visit to the calendars.
        b.widgetPlaceDone.setOnClickListener { choose(current) }
    }

    override fun onResume() {
        super.onResume()
        if (this::b.isInitialized) showCalendars()
    }

    /** "Personal, Family" under Calendars, or "Off". */
    private fun showCalendars() {
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val calendars = PhoneCalendars.list(this@WidgetPlaceActivity)
                CalendarChoice.summary(
                    calendars,
                    CalendarChoice.chosen(calendars, PhoneCalendars.saved(this@WidgetPlaceActivity))
                )
            }
            b.widgetCalendarSub.text = summary ?: getString(R.string.widget_pick_calendars_off)
        }
    }

    private fun addRow(name: String, sub: String, selected: Boolean, target: WidgetTarget) {
        val row = ItemPlaceBinding.inflate(layoutInflater, b.widgetPlaceList, false)
        row.rowName.text = name
        row.rowSub.text = sub
        // The tint My places uses for the row you are on, plus a tick that TalkBack reads.
        row.rowTemp.text = if (selected) "✓" else ""
        row.rowIcon.visibility = View.GONE
        if (selected) {
            row.root.setBackgroundColor(
                MaterialColors.getColor(row.root, com.google.android.material.R.attr.colorSecondaryContainer)
            )
        }
        row.root.setOnClickListener { choose(target) }
        b.widgetPlaceList.addView(row.root)
    }

    private fun choose(target: WidgetTarget) {
        if (chosen) return
        chosen = true
        WidgetTargetStore(this).set(widgetId, target)
        lifecycleScope.launch {
            // Show the new choice at once from what is already on the phone, then ask for fresh numbers.
            WidgetUpdater.paintFromDisk(applicationContext)
            WidgetUpdater.requestRefresh(applicationContext, widgetId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }

    private companion object {
        const val SINGAPORE = "Singapore"
    }
}
