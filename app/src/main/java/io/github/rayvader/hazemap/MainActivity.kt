package io.github.rayvader.hazemap

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.rayvader.hazemap.databinding.ActivityMainBinding

/**
 * Three tabs on one screen: Now (forecast), Rain (map with the past two hours) and Haze (PSI/PM2.5).
 * Each tab is a screen object that owns its views; switching tabs shows and hides them, so a
 * tab keeps its map position and loaded data.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var now: NowScreen
    private lateinit var rain: RainScreen
    private lateinit var haze: HazeScreen

    private var area: Area = Areas.default
    private var currentTab = NO_TAB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        area = Areas.byName(prefs.getString(KEY_AREA, null)) ?: Areas.default
        showAreaName()

        now = NowScreen(this, binding.nowScreen, { area }) { binding.bottomNav.selectedItemId = R.id.nav_haze }
        rain = RainScreen(this, binding.rainScreen, prefs)
        haze = HazeScreen(this, binding.hazeScreen)

        binding.bottomNav.setOnItemSelectedListener {
            select(it.itemId)
            true
        }
        binding.areaBar.setOnClickListener { pickArea() }
        binding.btnRefresh.setOnClickListener { refreshCurrent() }

        val start = when (intent.getStringExtra(EXTRA_TAB)) {
            "rain" -> R.id.nav_rain
            "haze" -> R.id.nav_haze
            else -> R.id.nav_now
        }
        binding.bottomNav.selectedItemId = start
        select(start)
    }

    private fun select(tab: Int) {
        if (tab == currentTab) return
        if (currentTab == R.id.nav_rain) rain.onHidden()
        currentTab = tab
        binding.nowScreen.root.visibility = if (tab == R.id.nav_now) View.VISIBLE else View.GONE
        binding.rainScreen.root.visibility = if (tab == R.id.nav_rain) View.VISIBLE else View.GONE
        binding.hazeScreen.root.visibility = if (tab == R.id.nav_haze) View.VISIBLE else View.GONE
        when (tab) {
            R.id.nav_now -> now.onShown()
            R.id.nav_rain -> rain.onShown()
            R.id.nav_haze -> haze.onShown()
        }
    }

    private fun refreshCurrent() {
        when (currentTab) {
            R.id.nav_now -> now.load(force = true)
            R.id.nav_rain -> rain.refresh()
            R.id.nav_haze -> haze.load(force = true)
        }
    }

    // ---- Area ----

    private fun showAreaName() {
        binding.areaName.text = area.name
    }

    private fun pickArea() {
        val names = Areas.all.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_area)
            .setSingleChoiceItems(names, names.indexOf(area.name)) { dialog, which ->
                area = Areas.all[which]
                prefs.edit().putString(KEY_AREA, area.name).apply()
                showAreaName()
                now.onAreaChanged()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Lifecycle ----

    override fun onResume() {
        super.onResume()
        rain.mapResume()
        haze.mapResume()
        // onPause marked the rain tab hidden; if it is still the open tab, it is visible again.
        if (currentTab == R.id.nav_rain) rain.onShown()
    }

    override fun onPause() {
        super.onPause()
        rain.mapPause()
        haze.mapPause()
        rain.onHidden()
    }

    override fun onDestroy() {
        super.onDestroy()
        rain.destroy()
        haze.destroy()
    }

    companion object {
        /** Opens a given tab: "now", "rain" or "haze". Used by the emulator smoke test. */
        const val EXTRA_TAB = "tab"

        /** Opens the Rain tab on "radar" or "gauges". Used by the emulator smoke test. */
        const val EXTRA_SOURCE = "source"

        private const val PREFS = "hazemap"
        private const val KEY_AREA = "area"
        private const val NO_TAB = -1
    }
}
