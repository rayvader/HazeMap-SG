package io.github.rayvader.hazemap

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.ZoneId

/**
 * Feeds the Wide widget's scrolling event list. The home screen binds this service to the ListView in
 * [WidgetUpdater] (setRemoteAdapter), then asks the factory below for one row per upcoming event.
 *
 * Only the Wide uses it. The Small and the Strip keep their single line and "+N more" (see
 * [WidgetUpdater.showEvents]); a scrolling list needs a height they do not have.
 */
class WidgetEventsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        WidgetEventsFactory(applicationContext)
}

/**
 * Builds the rows of the Wide's event list: the upcoming events on the ticked calendars (see
 * [EventLogic.rows]), each a bold when-label and a title. Tapping a row opens that event in the calendar
 * app, through the list's pending-intent template and this row's fill-in intent.
 *
 * The home screen calls [onDataSetChanged] on a background thread whenever the widget says the list changed
 * (see [WidgetUpdater.notifyEventListChanged]); it reads the calendars there, so the widget thread never
 * touches the calendar provider.
 */
class WidgetEventsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<EventLogic.EventRow> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        // No calendar access (never granted, or revoked): an empty list, so the widget shows its empty note.
        // upcoming() already orders the events (pickUpcoming), so map them straight to rows.
        val picked = if (PhoneCalendars.hasAccess(context)) PhoneCalendars.upcoming(context, now) else emptyList()
        rows = EventLogic.rowsFromPicked(picked, now, zone)
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_event_list_row)
        val views = RemoteViews(context.packageName, R.layout.widget_event_list_row)
        views.setTextViewText(R.id.wEventWhen, row.whenLabel)
        views.setTextViewText(R.id.wEventText, row.title)
        views.setContentDescription(
            R.id.wEventRow,
            context.getString(R.string.widget_event_row_desc, row.whenLabel, row.title)
        )
        // Each row fills in its own event into the list's template intent (see WidgetUpdater): the event's
        // page in the calendar app, with its begin and end so a repeating event opens the right time.
        val target = CalendarApps.eventTarget(row.event)
        val fillIn = Intent()
            .setData(Uri.parse(target.uri))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, target.beginMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, target.endMillis)
        views.setOnClickFillInIntent(R.id.wEventRow, fillIn)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.event?.eventId ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
