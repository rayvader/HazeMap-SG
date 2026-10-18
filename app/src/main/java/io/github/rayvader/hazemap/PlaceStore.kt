package io.github.rayvader.hazemap

import android.content.Context

/** The saved places on this phone. */
class PlaceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    fun all(): List<Place> = PlaceList.parse(prefs.getString(Prefs.KEY_PLACES, null))

    fun contains(id: Long): Boolean = all().any { it.id == id }

    fun add(place: Place) = save(PlaceList.add(all(), place))

    fun remove(id: Long) = save(PlaceList.remove(all(), id))

    private fun save(list: List<Place>) {
        prefs.edit().putString(Prefs.KEY_PLACES, PlaceList.toJson(list)).apply()
    }
}
