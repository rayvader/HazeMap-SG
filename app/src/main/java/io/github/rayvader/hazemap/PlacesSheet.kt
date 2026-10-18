package io.github.rayvader.hazemap

import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.rayvader.hazemap.databinding.DialogAddPlaceBinding
import io.github.rayvader.hazemap.databinding.ItemPlaceBinding
import io.github.rayvader.hazemap.databinding.SheetPlacesBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * "My places": Singapore pinned on top, then the cities the user saved, each with its temperature
 * and weather. Tapping a city opens its page; "Add a place" searches for a new one.
 */
class PlacesSheet(
    private val activity: AppCompatActivity,
    private val currentArea: () -> Area
) {
    private val repo = WeatherRepository.get(activity)
    private val store = PlaceStore(activity)
    private val dialog = BottomSheetDialog(activity)
    private val b = SheetPlacesBinding.inflate(activity.layoutInflater)
    private var glanceJob: Job? = null

    fun show() {
        dialog.setContentView(b.root)
        dialog.setOnDismissListener { glanceJob?.cancel() }
        b.placesAdd.setOnClickListener { showAdd() }
        render()
        dialog.show()
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun render() {
        b.placesList.removeAllViews()

        val home = ItemPlaceBinding.inflate(activity.layoutInflater, b.placesList, false)
        home.rowName.text = SINGAPORE
        home.rowSub.text = activity.getString(R.string.places_home_sub)
        home.root.setBackgroundColor(MaterialColors.getColor(home.root, com.google.android.material.R.attr.colorSecondaryContainer))
        home.root.setOnClickListener { dialog.dismiss() }
        b.placesList.addView(home.root)
        fillHome(home)

        val places = store.all()
        b.placesEmpty.visibility = if (places.isEmpty()) View.VISIBLE else View.GONE
        val rows = places.map { p ->
            val row = ItemPlaceBinding.inflate(activity.layoutInflater, b.placesList, false)
            row.rowName.text = p.name
            row.rowSub.text = listOf(p.subtitle, TimeFmt.clockIn(System.currentTimeMillis(), p.timezone))
                .filter { it.isNotEmpty() }.joinToString(" · ")
            row.root.setOnClickListener {
                dialog.dismiss()
                activity.startActivity(PlaceActivity.intent(activity, p))
            }
            b.placesList.addView(row.root)
            row
        }
        loadGlance(places, rows)
    }

    /** Singapore's row uses the NEA data already saved on the phone, so it needs no request. */
    private fun fillHome(row: ItemPlaceBinding) {
        val area = currentArea()
        activity.lifecycleScope.launch {
            val (temp, text) = withContext(Dispatchers.IO) {
                val t = repo.peek(Endpoints.TEMPERATURE)?.value?.nearestValue(area.lat, area.lon)
                val c = repo.peek(Endpoints.FORECAST_2H)?.value?.forArea(area.name)
                t to c
            }
            if (temp != null) row.rowTemp.text = "${temp.roundToInt()}°"
            if (text != null) row.rowIcon.setImageResource(Icons.forCondition(Condition.fromText(text)))
        }
    }

    private fun loadGlance(places: List<Place>, rows: List<ItemPlaceBinding>) {
        glanceJob?.cancel()
        if (places.isEmpty()) return
        val endpoint = Endpoints.placeGlance(places)
        glanceJob = activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.peek(endpoint) }?.let { fill(rows, it.value) }
            try {
                fill(rows, repo.load(endpoint).value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The rows keep their dashes, or the saved readings, when the request fails.
            }
        }
    }

    private fun fill(rows: List<ItemPlaceBinding>, glances: List<PlaceGlance>) {
        // The saved list can change while a request is in flight; only a matching answer is shown.
        if (glances.size != rows.size) return
        rows.forEachIndexed { i, row ->
            val g = glances[i]
            row.rowTemp.text = g.tempC?.let { "${it.roundToInt()}°" } ?: activity.getString(R.string.no_temp)
            row.rowIcon.setImageResource(Icons.forCondition(Wmo.condition(g.code, g.isDay)))
        }
    }

    // ---- Add a place ----

    private fun showAdd() {
        val v = DialogAddPlaceBinding.inflate(activity.layoutInflater)
        val add = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.add_title)
            .setView(v.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        v.searchInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                search(v, add, v.searchInput.text.toString().trim())
                true
            } else {
                false
            }
        }
        add.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        add.show()
        v.searchInput.requestFocus()
    }

    private fun search(v: DialogAddPlaceBinding, add: androidx.appcompat.app.AlertDialog, query: String) {
        if (query.isEmpty()) return
        activity.lifecycleScope.launch {
            v.searchResults.removeAllViews()
            v.searchStatus.text = activity.getString(R.string.add_searching)
            val found = try {
                val name = URLEncoder.encode(query, "UTF-8")
                PlaceParsers.geocoding(Http.getText(GEOCODING + name + GEOCODING_TAIL, limited = false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                v.searchStatus.text = activity.getString(R.string.add_failed)
                return@launch
            }
            v.searchStatus.text = if (found.isEmpty()) activity.getString(R.string.add_none) else ""
            for (p in found) {
                val row = ItemPlaceBinding.inflate(activity.layoutInflater, v.searchResults, false)
                val saved = store.contains(p.id)
                row.rowName.text = p.name
                row.rowSub.text = listOfNotNull(
                    p.subtitle.ifEmpty { null },
                    if (saved) activity.getString(R.string.add_already_saved) else null
                ).joinToString(" · ")
                row.rowTemp.visibility = View.GONE
                row.rowIcon.visibility = View.GONE
                row.root.setOnClickListener { choose(p, add) }
                v.searchResults.addView(row.root)
            }
        }
    }

    private fun choose(p: Place, add: androidx.appcompat.app.AlertDialog) {
        if (!store.contains(p.id)) {
            store.add(p)
            if (store.contains(p.id)) {
                Toast.makeText(activity, activity.getString(R.string.add_saved_toast, p.name), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, R.string.places_full, Toast.LENGTH_LONG).show()
            }
        }
        add.dismiss()
        render()
    }

    private companion object {
        const val SINGAPORE = "Singapore"
        const val GEOCODING = "https://geocoding-api.open-meteo.com/v1/search?name="
        const val GEOCODING_TAIL = "&count=8&language=en&format=json"
    }
}
