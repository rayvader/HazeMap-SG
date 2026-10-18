package io.github.rayvader.hazemap

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.rayvader.hazemap.databinding.ActivityMainBinding
import io.github.rayvader.hazemap.databinding.ScreenHazeBinding
import io.github.rayvader.hazemap.databinding.ScreenRainBinding

/**
 * Three tabs on one screen: Now (forecast), Rain (map with the past two hours) and Haze (PSI/PM2.5).
 * Each tab is a screen object that owns its views; switching tabs shows and hides them, so a
 * tab keeps its map position and loaded data. The two map tabs are built the first time they open.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var restored: Bundle? = null
    private lateinit var now: NowScreen
    private var rain: RainScreen? = null
    private var haze: HazeScreen? = null
    private var activeLocationListener: LocationListener? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            findMyLocation()
        } else {
            Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private var area: Area = Areas.default
    private var currentTab = NO_TAB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        restored = savedInstanceState
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        area = Areas.byName(prefs.getString(KEY_AREA, null)) ?: Areas.default
        showAreaName()

        now = NowScreen(this, binding.nowScreen, { area }) { binding.bottomNav.selectedItemId = R.id.nav_haze }

        binding.bottomNav.setOnItemSelectedListener {
            select(it.itemId)
            true
        }
        binding.areaBar.setOnClickListener { pickArea() }
        binding.btnRefresh.setOnClickListener { refreshCurrent() }
        binding.btnTheme.setOnClickListener { toggleTheme() }
        showThemeButton()

        // After a recreation (dark mode switch, low memory) reopen the tab the user was on.
        val restored = savedInstanceState?.getInt(KEY_TAB, NO_TAB) ?: NO_TAB
        val start = if (restored != NO_TAB) {
            restored
        } else {
            when (intent.getStringExtra(EXTRA_TAB)) {
                "rain" -> R.id.nav_rain
                "haze" -> R.id.nav_haze
                else -> R.id.nav_now
            }
        }
        binding.bottomNav.selectedItemId = start
        select(start)
    }

    private fun rainScreen(): RainScreen = rain
        ?: RainScreen(this, ScreenRainBinding.bind(binding.rainStub.inflate()), prefs, restored).also {
            rain = it
            it.mapResume()
        }

    private fun hazeScreen(): HazeScreen = haze
        ?: HazeScreen(this, ScreenHazeBinding.bind(binding.hazeStub.inflate()), restored).also {
            haze = it
            it.mapResume()
        }

    private fun select(tab: Int) {
        if (tab == currentTab) return
        if (currentTab == R.id.nav_rain) rain?.onHidden()
        currentTab = tab
        binding.nowScreen.root.visibility = if (tab == R.id.nav_now) View.VISIBLE else View.GONE
        when (tab) {
            R.id.nav_now -> now.onShown()
            R.id.nav_rain -> rainScreen().onShown()
            R.id.nav_haze -> hazeScreen().onShown()
        }
        rain?.setVisible(tab == R.id.nav_rain)
        haze?.setVisible(tab == R.id.nav_haze)
    }

    private fun refreshCurrent() {
        when (currentTab) {
            R.id.nav_now -> now.load(force = true)
            R.id.nav_rain -> rain?.refresh()
            R.id.nav_haze -> haze?.load(force = true)
        }
    }

    // ---- Theme ----

    /** The sun or moon in the top bar shows what a tap switches to. */
    private fun showThemeButton() {
        val dark = isNightMode()
        binding.btnTheme.setImageResource(if (dark) R.drawable.ic_theme_to_light else R.drawable.ic_theme_to_dark)
        binding.btnTheme.contentDescription = getString(if (dark) R.string.switch_to_light else R.string.switch_to_dark)
    }

    private fun toggleTheme() {
        // This recreates the activity; onSaveInstanceState keeps the open tab and the map views.
        ThemeStore.choose(this, ThemeChoice.afterTap(isNightMode()))
    }

    // ---- Area ----

    private fun showAreaName() {
        binding.areaName.text = area.name
    }

    private fun pickArea() {
        val names = Areas.all.map { it.name }.toTypedArray()
        val choices = arrayOf(getString(R.string.use_my_location), *names)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_area)
            .setSingleChoiceItems(choices, names.indexOf(area.name) + 1) { dialog, which ->
                if (which == 0) {
                    requestMyLocation(dialog)
                } else {
                    selectArea(Areas.all[which - 1])
                    dialog.dismiss()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Keep the new location row visible even when the current area is selected.
        dialog.listView?.setSelection(0)
    }

    private fun requestMyLocation(dialog: DialogInterface) {
        dialog.dismiss()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            findMyLocation()
        }
    }

    private fun findMyLocation() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = manager.getProviders(true).filter {
            it == LocationManager.NETWORK_PROVIDER || it == LocationManager.GPS_PROVIDER
        }
        val last = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
        if (last != null) {
            setAreaFromLocation(last)
            return
        }

        lateinit var listener: LocationListener
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                stopLocationUpdates(listener)
                setAreaFromLocation(location)
            }
        }
        activeLocationListener = listener
        var requested = false
        for (provider in providers) {
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                requested = true
            } catch (_: SecurityException) {
                // The permission can change while the request is running.
            }
        }
        if (!requested) {
            stopLocationUpdates(listener)
            Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (activeLocationListener === listener) {
                stopLocationUpdates(listener)
                Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_SHORT).show()
            }
        }, LOCATION_TIMEOUT_MS)
    }

    private fun stopLocationUpdates(listener: LocationListener) {
        if (activeLocationListener !== listener) return
        activeLocationListener = null
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { manager.removeUpdates(listener) }
    }

    private fun setAreaFromLocation(location: Location) {
        selectArea(Areas.nearest(location.latitude, location.longitude))
    }

    private fun selectArea(selected: Area) {
        area = selected
        prefs.edit().putString(KEY_AREA, area.name).apply()
        showAreaName()
        now.onAreaChanged()
    }

    // ---- Lifecycle ----

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
        rain?.saveMap(outState)
        haze?.saveMap(outState)
    }

    override fun onResume() {
        super.onResume()
        rain?.mapResume()
        haze?.mapResume()
        // onPause marked the rain tab hidden; if it is still the open tab, it is visible again.
        if (currentTab == R.id.nav_rain) rain?.onShown()
    }

    override fun onPause() {
        super.onPause()
        rain?.mapPause()
        haze?.mapPause()
        rain?.onHidden()
    }

    override fun onDestroy() {
        activeLocationListener?.let { stopLocationUpdates(it) }
        rain?.destroy()
        haze?.destroy()
        super.onDestroy()
    }

    companion object {
        /** Opens a given tab: "now", "rain" or "haze". Used by the emulator smoke test. */
        const val EXTRA_TAB = "tab"

        /** Opens the Rain tab on "radar" or "gauges". Used by the emulator smoke test. */
        const val EXTRA_SOURCE = "source"

        /** Starts the Rain map at this zoom, to look beyond Singapore. Used by the emulator smoke test. */
        const val EXTRA_ZOOM = "zoom"

        private const val LOCATION_TIMEOUT_MS = 10_000L
        private const val KEY_AREA = "area"
        private const val KEY_TAB = "tab"
        private const val NO_TAB = -1
    }
}
