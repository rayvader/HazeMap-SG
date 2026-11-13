package io.github.rayvader.hazemap

/** What a tap on a widget's event line opens. */
sealed interface CalendarChoice {
    /** The calendar app on today ("content://com.android.calendar/time/<now>"). */
    data object Today : CalendarChoice

    /** The phone's calendar app by its category, at whatever view it opens on. */
    data object MainCalendar : CalendarChoice

    /** A known calendar app, opened by its launcher entry. */
    data class Package(val name: String) : CalendarChoice

    /** No calendar app found: the tap opens this app, like the rest of the widget. */
    data object App : CalendarChoice
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
    fun choose(opensToday: Boolean, hasCalendarApp: Boolean, launchable: Set<String>): CalendarChoice {
        if (opensToday) return CalendarChoice.Today
        if (hasCalendarApp) return CalendarChoice.MainCalendar
        val pkg = PACKAGES.firstOrNull { it in launchable } ?: return CalendarChoice.App
        return CalendarChoice.Package(pkg)
    }
}
