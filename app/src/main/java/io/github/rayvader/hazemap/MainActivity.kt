package io.github.rayvader.hazemap

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.rayvader.hazemap.databinding.ActivityMainBinding
import io.github.rayvader.hazemap.databinding.ScreenHazeBinding
import io.github.rayvader.hazemap.databinding.ScreenRainBinding
import kotlinx.coroutines.launch

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
    private lateinit var updates: UpdateChecker

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
        binding.btnPlaces.setOnClickListener { PlacesSheet(this) { area }.show() }
        showThemeButton()
        setupUpdateBanner()

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

        if (savedInstanceState == null) {
            WidgetShape.fromKey(intent.getStringExtra(EXTRA_PIN_WIDGET))?.let { WidgetUpdater.requestPin(this, it) }
            intent.getStringExtra(EXTRA_WIDGET_SCENE)?.let { key ->
                WidgetUpdater.sceneOverride = SkyScene.fromKey(key)
                lifecycleScope.launch { WidgetUpdater.update(this@MainActivity) }
            }
        }
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

    // ---- Update banner ----

    /** The version the update check compares with. The smoke test can pretend to be an old one. */
    private fun installedVersion(): String? =
        intent.getStringExtra(EXTRA_INSTALLED_VERSION) ?: updates.installedVersion()

    private fun setupUpdateBanner() {
        updates = UpdateChecker(this)
        Updater.justUpdatedTo(this, updates.installedVersion())?.let {
            Toast.makeText(this, getString(R.string.update_done, it), Toast.LENGTH_LONG).show()
        }
        binding.updateDownload.setOnClickListener { onUpdateButton() }
        binding.updateClose.setOnClickListener {
            Updater.state.value.info?.let { updates.dismiss(it.version) }
            offerUpdate(null)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { Updater.state.collect { showUpdate(it) } }
        }
        offerUpdate(updates.cached())
        // The smoke test's pretend version also skips the six-hour wait, so the real request is tested.
        val force = intent.hasExtra(EXTRA_INSTALLED_VERSION)
        lifecycleScope.launch { offerUpdate(updates.refreshIfDue(force)) }
    }

    private fun offerUpdate(latest: UpdateInfo?) {
        val show = UpdateLogic.shouldShow(installedVersion(), latest, updates.dismissedVersion())
        // A pretend version only shows the banner. The download starts by itself only off mobile data.
        val auto = !intent.hasExtra(EXTRA_INSTALLED_VERSION) && onUnmeteredNetwork()
        Updater.offer(this, if (show) latest else null, auto)
    }

    private fun onUnmeteredNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return !cm.isActiveNetworkMetered
    }

    private fun showUpdate(state: UpdateState) {
        val info = state.info
        binding.updateBanner.visibility = if (info == null) View.GONE else View.VISIBLE
        if (info == null) return
        val version = getString(R.string.update_sub, info.version)
        val (title, sub, button) = when (state) {
            is UpdateState.Available -> Triple(R.string.update_title, version, R.string.update_now)
            is UpdateState.Downloading -> Triple(
                R.string.update_downloading,
                state.percent?.let { getString(R.string.update_percent, info.version, it) } ?: version,
                null
            )
            is UpdateState.Ready -> Triple(
                R.string.update_ready,
                if (state.installOnLeave) getString(R.string.update_on_leave, info.version) else version,
                R.string.update_install
            )
            is UpdateState.Installing -> Triple(R.string.update_installing, version, null)
            is UpdateState.Failed -> Triple(
                R.string.update_failed,
                getString(problemText(state.problem)),
                if (state.problem.retry && info.isApk) R.string.update_retry else R.string.update_download
            )
            UpdateState.Hidden -> return
        }
        binding.updateTitle.setText(title)
        binding.updateSub.text = sub
        binding.updateDownload.visibility = if (button == null) View.GONE else View.VISIBLE
        if (button != null) binding.updateDownload.setText(button)
    }

    private fun problemText(problem: UpdateProblem): Int = when (problem) {
        UpdateProblem.DOWNLOAD -> R.string.update_problem_download
        UpdateProblem.DAMAGED -> R.string.update_problem_damaged
        UpdateProblem.OTHER_APP -> R.string.update_problem_other_app
        UpdateProblem.NOT_NEWER -> R.string.update_problem_not_newer
        UpdateProblem.OTHER_SIGNER -> R.string.update_problem_other_signer
        UpdateProblem.INSTALL -> R.string.update_problem_install
    }

    private fun onUpdateButton() {
        when (val s = Updater.state.value) {
            is UpdateState.Available -> s.info?.let { if (it.isApk) Updater.download(this, it) else openDownload(it) }
            is UpdateState.Ready -> Updater.installNow(this)
            is UpdateState.Failed -> s.info?.let {
                if (s.problem.retry && it.isApk) Updater.download(this, it) else openDownload(it)
            }
            else -> Unit
        }
    }

    /** The release page or APK in the browser: the way out when the app cannot update itself. */
    private fun openDownload(info: UpdateInfo) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.update_no_browser, Toast.LENGTH_LONG).show()
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
        // Home screen widgets show the chosen area too.
        lifecycleScope.launch { WidgetUpdater.update(this@MainActivity) }
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

        /** Asks the home screen to add a widget: "small", "wide" or "strip". Used by the emulator smoke test. */
        const val EXTRA_PIN_WIDGET = "pin_widget"

        /** Pretends the installed app has this version, to see the update banner. Used by the emulator smoke test. */
        const val EXTRA_INSTALLED_VERSION = "installed_version"

        /**
         * Redraws the widgets with this sky ("sun", "rain", "night", ...) whatever the weather; "auto" goes
         * back to the real one. Used by the emulator smoke test.
         */
        const val EXTRA_WIDGET_SCENE = "widget_scene"

        private const val LOCATION_TIMEOUT_MS = 10_000L
        private const val KEY_AREA = Prefs.KEY_AREA
        private const val KEY_TAB = "tab"
        private const val NO_TAB = -1
    }
}
