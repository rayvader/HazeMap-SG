package io.github.rayvader.hazemap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * "Follow my location": the app and the widgets use the NEA area nearest to the phone. Only the phone's
 * approximate location is read (no GPS start, no service running), and it stays on the phone: the area name
 * is what the rest of the app sees. The rules are in [FollowLogic].
 */
object FollowLocation {

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(Prefs.KEY_FOLLOW, false)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(Prefs.KEY_FOLLOW, on).apply {
            // A new start: the first reading after turning it on always counts.
            if (on) remove(Prefs.KEY_FOLLOW_FIX)
        }.apply()
    }

    fun hasCoarse(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < FollowLogic.BACKGROUND_SDK ||
            granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun state(context: Context): FollowState =
        FollowLogic.state(isOn(context), hasCoarse(context), hasBackground(context), Build.VERSION.SDK_INT)

    /** The newest reading the phone already has, with no request: null when there is none or it may not be read. */
    fun lastKnown(context: Context): Fix? {
        if (!hasCoarse(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        return providers.mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                // That provider is not on this phone.
                null
            }
        }.maxByOrNull { it.time }?.let { toFix(it) }
    }

    fun toFix(location: Location) = Fix(location.latitude, location.longitude, location.time)

    /**
     * Uses one reading when the switch is on: saves the new area and the reading (rounded), and returns the
     * new area, or null when the area stays as it is.
     */
    fun apply(context: Context, fix: Fix?, nowMs: Long = System.currentTimeMillis()): Area? {
        if (!isOn(context)) return null
        val p = prefs(context)
        val current = Areas.byName(p.getString(Prefs.KEY_AREA, null)) ?: Areas.default
        val step = FollowLogic.step(current, FollowLogic.decode(p.getString(Prefs.KEY_FOLLOW_FIX, null)), fix, nowMs)
        if (step !is FollowLogic.Step.Use) return null
        p.edit().apply {
            putString(Prefs.KEY_FOLLOW_FIX, FollowLogic.encode(step.fix))
            step.area?.let { putString(Prefs.KEY_AREA, it.name) }
        }.apply()
        return step.area
    }

    /**
     * For a widget refresh: when the widgets may follow the phone, uses the reading the phone already has.
     * Otherwise they keep the last area the app saw.
     */
    fun forWidgets(context: Context) {
        if (state(context) == FollowState.ALL) apply(context, lastKnown(context))
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
}
