package io.github.rayvader.hazemap

import android.content.Context

/** What one widget shows. It is picked when the widget is added, and again when it is reconfigured. */
sealed interface WidgetTarget {
    /** Singapore: NEA weather for the area chosen in the app, and the PSI. */
    data object Singapore : WidgetTarget

    /**
     * A city from My places. The widget keeps its own copy of the place, so it keeps working
     * if the city is later taken off the list.
     */
    data class City(val place: Place) : WidgetTarget
}

/** How a [WidgetTarget] is written down in the app's settings. */
object WidgetTargetCodec {
    private const val SINGAPORE = "sg"

    fun encode(target: WidgetTarget): String = when (target) {
        WidgetTarget.Singapore -> SINGAPORE
        is WidgetTarget.City -> PlaceList.toJson(listOf(target.place))
    }

    /** Null for a missing or damaged value. */
    fun decode(text: String?): WidgetTarget? {
        if (text.isNullOrBlank()) return null
        if (text == SINGAPORE) return WidgetTarget.Singapore
        return PlaceList.parse(text).firstOrNull()?.let { WidgetTarget.City(it) }
    }
}

/** What every widget on the home screen shows, by the widget's id. */
class WidgetTargetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** False for a widget that was never set up, such as one added before cities were possible. */
    fun has(widgetId: Int): Boolean = prefs.contains(key(widgetId))

    /** Singapore for a widget that was never set up, as every widget used to be. */
    fun get(widgetId: Int): WidgetTarget =
        WidgetTargetCodec.decode(prefs.getString(key(widgetId), null)) ?: WidgetTarget.Singapore

    fun set(widgetId: Int, target: WidgetTarget) {
        prefs.edit().putString(key(widgetId), WidgetTargetCodec.encode(target)).apply()
    }

    fun remove(widgetIds: IntArray) {
        val edit = prefs.edit()
        for (id in widgetIds) edit.remove(key(id))
        edit.apply()
    }

    /** Forgets widgets that are gone, so an id Android hands out again never inherits an old choice. */
    fun keepOnly(widgetIds: Collection<Int>) {
        val live = widgetIds.map { key(it) }.toSet()
        val stale = prefs.all.keys.filter { it !in live }
        if (stale.isEmpty()) return
        val edit = prefs.edit()
        for (k in stale) edit.remove(k)
        edit.apply()
    }

    private fun key(widgetId: Int) = "w$widgetId"

    private companion object {
        const val NAME = "hazemap_widgets"
    }
}
