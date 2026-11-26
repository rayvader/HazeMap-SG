package io.github.rayvader.hazemap

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log

/**
 * Opens one event in the phone's calendar app. A row of the Wide widget's scrolling event list taps through
 * here (see [WidgetUpdater.eventRowTemplate] and [WidgetEventsFactory]): the list's template points at this
 * activity, and the tapped row fills in the event's URI and its begin/end time. A trampoline is needed
 * because Android 14+ forbids the mutable template a collection needs from carrying an implicit intent, so
 * the widget points at this activity (explicit) and the actual VIEW on the calendar happens here.
 *
 * It shows nothing: it starts the calendar and finishes at once.
 */
class EventTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data != null) {
            val view = Intent(Intent.ACTION_VIEW, data).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Carry the begin/end so a repeating event opens the right occurrence.
            intent.extras?.let { extras ->
                if (extras.containsKey(CalendarContract.EXTRA_EVENT_BEGIN_TIME)) {
                    view.putExtra(
                        CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                        extras.getLong(CalendarContract.EXTRA_EVENT_BEGIN_TIME)
                    )
                }
                if (extras.containsKey(CalendarContract.EXTRA_EVENT_END_TIME)) {
                    view.putExtra(
                        CalendarContract.EXTRA_EVENT_END_TIME,
                        extras.getLong(CalendarContract.EXTRA_EVENT_END_TIME)
                    )
                }
            }
            try {
                startActivity(view)
            } catch (e: ActivityNotFoundException) {
                // No app opens an event page: fall back to this app, so the tap never does nothing.
                Log.w(TAG, "no calendar app for the event", e)
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        finish()
    }

    private companion object {
        const val TAG = "HazeWidget"
    }
}
