package io.github.rayvader.hazemap

import android.Manifest
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.lifecycleScope
import io.github.rayvader.hazemap.databinding.ActivityCalendarsBinding
import io.github.rayvader.hazemap.databinding.ItemCalendarBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which phone calendars the widget line reads. Before the first tick it uses the calendars the
 * Calendar app shows. Each tick is saved at once and shows on the widgets.
 */
class CalendarsActivity : AppCompatActivity() {

    private lateinit var b: ActivityCalendarsBinding
    private var renderJob: Job? = null

    /** "Don't allow" was pressed here. If Android then stops asking, only Settings can turn access on. */
    private var refused = false

    /** Something changed that the widgets should fetch fresh weather for once this screen closes. */
    private var changed = false

    private val askAccess = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            changed = true
            repaintWidgets()
        } else {
            refused = true
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCalendarsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
        b.calendarsAllow.setOnClickListener { allow() }
    }

    override fun onResume() {
        super.onResume()
        // Access can be turned on or off in Settings while this screen is away.
        render()
    }

    override fun onPause() {
        super.onPause()
        if (changed) {
            changed = false
            WidgetUpdater.requestRefreshAll(applicationContext)
        }
    }

    private fun mustUseSettings(): Boolean =
        refused && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)

    private fun allow() {
        if (mustUseSettings()) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        } else {
            askAccess.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    private fun render() {
        val access = PhoneCalendars.hasAccess(this)
        b.calendarsAccess.visibility = if (access) View.GONE else View.VISIBLE
        b.calendarsAllow.setText(if (mustUseSettings()) R.string.calendars_open_settings else R.string.calendars_allow)
        renderJob?.cancel()
        if (!access) {
            b.calendarsList.removeAllViews()
            b.calendarsEmpty.visibility = View.GONE
            return
        }
        renderJob = lifecycleScope.launch {
            val calendars = withContext(Dispatchers.IO) { PhoneCalendars.list(this@CalendarsActivity) }
            val chosen = CalendarChoice.chosen(calendars, PhoneCalendars.saved(this@CalendarsActivity)).toMutableSet()
            b.calendarsList.removeAllViews()
            b.calendarsEmpty.visibility = if (calendars.isEmpty()) View.VISIBLE else View.GONE
            var group: String? = null
            for (cal in calendars) {
                val label = if (cal.phoneOnly || cal.account.isBlank()) getString(R.string.calendars_this_phone) else cal.account
                if (label != group) {
                    group = label
                    addHeader(label)
                }
                addRow(cal, chosen)
            }
        }
    }

    private fun addHeader(text: String) {
        val header = layoutInflater.inflate(R.layout.item_calendar_header, b.calendarsList, false) as TextView
        header.text = text
        b.calendarsList.addView(header)
    }

    private fun addRow(cal: PhoneCalendar, chosen: MutableSet<Long>) {
        val row = ItemCalendarBinding.inflate(layoutInflater, b.calendarsList, false)
        row.calendarName.text = cal.name
        row.calendarDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(cal.color or OPAQUE)
        }
        if (cal.switchedOff) {
            // Its events are not on the phone, so there is nothing to tick.
            row.calendarSub.visibility = View.VISIBLE
            row.calendarSub.setText(R.string.calendars_switched_off)
            row.calendarCheck.visibility = View.GONE
            row.root.alpha = DIMMED
            row.root.isClickable = false
            row.root.isFocusable = false
        } else {
            row.calendarCheck.isChecked = cal.id in chosen
            // TalkBack reads the row as one tick box, with its name.
            ViewCompat.setAccessibilityDelegate(row.root, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = true
                    info.isChecked = row.calendarCheck.isChecked
                    info.className = CheckBox::class.java.name
                }
            })
            row.root.setOnClickListener {
                val on = !row.calendarCheck.isChecked
                row.calendarCheck.isChecked = on
                if (on) chosen.add(cal.id) else chosen.remove(cal.id)
                PhoneCalendars.save(this, chosen.toSet())
                changed = true
                repaintWidgets()
            }
        }
        b.calendarsList.addView(row.root)
    }

    /** Shows the new choice on the widgets at once, from what is already saved on the phone. */
    private fun repaintWidgets() {
        lifecycleScope.launch { WidgetUpdater.paintFromDisk(applicationContext) }
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
        const val DIMMED = 0.5f
    }
}
