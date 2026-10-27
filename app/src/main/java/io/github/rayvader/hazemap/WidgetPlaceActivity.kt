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
import kotlinx.coroutines.launch

/**
 * The screen a widget opens when it is added, and again when it is reconfigured: pick Singapore or
 * one of the saved cities. The launcher starts it with the widget's id.
 */
class WidgetPlaceActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var chosen = false

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

        val store = WidgetTargetStore(this)
        val places = PlaceStore(this).all()
        // A new widget with no saved city has nothing to choose from: it shows Singapore, as widgets always did.
        if (!store.has(widgetId) && places.isEmpty()) {
            choose(WidgetTarget.Singapore)
            return
        }

        val b = ActivityWidgetPlaceBinding.inflate(layoutInflater)
        setContentView(b.root)
        val current = store.get(widgetId)

        addRow(
            b,
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
                b,
                name = p.name,
                sub = listOf(p.subtitle, TimeFmt.clockIn(now, p.timezone)).filter { it.isNotEmpty() }.joinToString(" · "),
                selected = shown?.id == p.id,
                target = WidgetTarget.City(p)
            )
        }
        b.widgetPlaceHint.visibility = if (places.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addRow(b: ActivityWidgetPlaceBinding, name: String, sub: String, selected: Boolean, target: WidgetTarget) {
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
