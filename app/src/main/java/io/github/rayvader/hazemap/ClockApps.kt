package io.github.rayvader.hazemap

/** What a tap on a widget's clock or date opens. */
sealed interface ClockChoice {
    /** Android's "show alarms" screen, in whatever clock app the phone has. */
    data object Alarms : ClockChoice

    /** A known clock app, opened by its launcher entry. */
    data class Package(val name: String) : ClockChoice

    /** No clock app found: the tap opens this app, like the rest of the widget. */
    data object App : ClockChoice
}

/** Picks the clock app for the widget's clock tap. No Android in it, so it is tested on the JVM. */
object ClockApps {
    const val SAMSUNG = "com.sec.android.app.clockpackage"
    const val GOOGLE = "com.google.android.deskclock"

    /** The clock apps tried after "show alarms", in order. The manifest's <queries> names each one. */
    val PACKAGES = listOf(SAMSUNG, GOOGLE)

    /**
     * Alarms first, then Samsung's Clock, then Google's, then this app.
     * [showsAlarms]: some app handles "show alarms". [launchable]: the clock apps that have a launcher entry.
     */
    fun choose(showsAlarms: Boolean, launchable: Set<String>): ClockChoice {
        if (showsAlarms) return ClockChoice.Alarms
        val pkg = PACKAGES.firstOrNull { it in launchable } ?: return ClockChoice.App
        return ClockChoice.Package(pkg)
    }
}
