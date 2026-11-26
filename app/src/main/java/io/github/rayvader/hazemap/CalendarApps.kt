package io.github.rayvader.hazemap

/** What a tap on a widget's event line opens. */
sealed interface CalendarTap {
    /** The calendar app on today ("content://com.android.calendar/time/<now>"). */
    data object Today : CalendarTap

    /** The phone's calendar app by its category, at whatever view it opens on. */
    data object MainCalendar : CalendarTap

    /** A known calendar app, opened by its launcher entry. */
    data class Package(val name: String) : CalendarTap

    /** No calendar app found: the tap opens this app, like the rest of the widget. */
    data object App : CalendarTap
}

/**
 * Picks the calendar app for the widget's event-line tap: the app itself, never one event's page.
 * No Android in it, so it is tested on the JVM.
 */
object CalendarApps {
    const val SAMSUNG = "com.samsung.android.calendar"
    const val GOOGLE = "com.google.android.calendar"

    /** The calendar apps tried by name, in order. The manifest's <queries> names each one. */
    val PACKAGES = listOf(SAMSUNG, GOOGLE)

    /**
     * Today's view first, then the calendar category, then Samsung's Calendar, then Google's, then this app.
     * [opensToday] / [hasCalendarApp]: something handles that intent. [launchable]: apps with a launcher entry.
     */
    fun choose(opensToday: Boolean, hasCalendarApp: Boolean, launchable: Set<String>): CalendarTap {
        if (opensToday) return CalendarTap.Today
        if (hasCalendarApp) return CalendarTap.MainCalendar
        val pkg = PACKAGES.firstOrNull { it in launchable } ?: return CalendarTap.App
        return CalendarTap.Package(pkg)
    }

    /** The events table URI, so the widget opens one event's page: content://com.android.calendar/events. */
    const val EVENTS_URI = "content://com.android.calendar/events"

    /**
     * Where a tapped row in the Wide's scrolling list points: that event's page in the calendar app. The row
     * reuses one template intent ([CalendarContract.ACTION_VIEW] on the events table) and each row fills in
     * its own event id and begin/end time; this returns those three parts, with no Android, so they are
     * tested on the JVM.
     */
    data class EventTarget(val uri: String, val beginMillis: Long, val endMillis: Long)

    /** The event's own page target: content://com.android.calendar/events/<id>, with its begin and end. */
    fun eventTarget(e: CalendarEvent): EventTarget =
        EventTarget("$EVENTS_URI/${e.eventId}", e.beginMillis, e.endMillis)
}
