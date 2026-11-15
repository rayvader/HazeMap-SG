package io.github.rayvader.hazemap

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Draws the widgets at exact sizes inside the app. A phone's home screen can give a two-row widget 140 dp
 * or less, while the emulator's gives about 220, so the emulator test looks at the short ones here.
 * Only the emulator test opens it (see [MainActivity.EXTRA_WIDGET_PREVIEW]).
 */
internal object WidgetPreview {

    private const val TAG = "HazeWidget"

    private const val FIRST_ID = 9000

    /** The time the layout gets to settle before it is checked. */
    private const val CHECK_DELAY_MS = 1500L

    /** A saved city whose name and country fit a widget's place line, and one that is too long for the smaller ones. */
    private val CITY = Place(1, "Kuala Lumpur", "Kuala Lumpur", "Malaysia", "MY", 3.14, 101.69, "Asia/Kuala_Lumpur")
    private val LONG_CITY = Place(2, "Santiago de Compostela", "Galicia", "Spain", "ES", 42.88, -8.54, "Europe/Madrid")

    /** The width each shape gets on a four-column phone, in dp. */
    private fun widthDp(shape: WidgetShape): Int = if (shape == WidgetShape.SMALL) 180 else 362

    /** The longest phrase NEA gives for a forecast, which the Wide's one line has to cut short. */
    private const val LONGEST_NEA_WORDS = "Heavy Thundery Showers with Gusty Winds"

    /** The longest of Open-Meteo's weather codes: thunderstorm with hail, "Thunderstorm with hail". */
    private const val LONGEST_WMO_CODE = 96

    /** Made-up events, in the order a calendar would list them: "+e=4" is the first four. */
    private val EVENTS = listOf(
        "Sun 14:00 Team offsite",
        "Sun 15:30 Dentist",
        "Sun 17:00 Dinner with Mei",
        "Tmrw 09:00 Stand-up",
        "Tmrw 12:30 Lunch with Ben",
        "Mon 10:00 Review",
        "Mon 16:00 Gym",
        "Tue 08:30 Flight to KL"
    )

    /**
     * One widget per entry like "wide@140+e": the shape, "@", the height in dp, then any of
     * "+e" (one event) or "+e=6" (that many, up to 8), "+min=2" (the test wants at least that many event
     * lines drawn), "+c" (a saved city, Kuala Lumpur), "+cl" (a city with a long name),
     * "+s=rain_night" (that sky, whatever the weather), "+lc" (the longest weather phrase) and
     * "+w=300" (that width in dp, for a phone whose columns are narrower than 362 dp for a Wide).
     * Entries in a row are split by ",", rows by "/". A row has to fit the screen, which is about 390 dp
     * wide on the test emulator: one Wide or Strip, or two Squares.
     */
    fun build(context: Context, spec: String): View {
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt()
        val fontScale = context.resources.configuration.fontScale
        val now = System.currentTimeMillis()
        val singapore = SingaporeModel("Punggol", 27.0, "Light Rain", 73, now)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111418.toInt())
            setPadding(px(6), px(6), px(6), px(6))
        }
        var id = FIRST_ID
        for (rowSpec in spec.split("/")) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (entry in rowSpec.split(",")) {
                val flags = entry.split("+")
                val head = flags.first()
                val shape = WidgetShape.fromKey(head.substringBefore("@")) ?: continue
                val heightDp = head.substringAfter("@").toIntOrNull() ?: continue
                val eventCount = flags.firstNotNullOfOrNull { f ->
                    when {
                        f == "e" -> 1
                        f.startsWith("e=") -> f.removePrefix("e=").toIntOrNull()?.coerceIn(0, EVENTS.size)
                        else -> null
                    }
                } ?: 0
                val minLines = flags.firstOrNull { it.startsWith("min=") }?.removePrefix("min=")?.toIntOrNull() ?: 0
                val events = EVENTS.take(eventCount).mapIndexed { i, text ->
                    WidgetUpdater.EventLine(CalendarEvent(i + 1L, 1, text, now, now, allDay = false), text)
                }
                val place = when {
                    "cl" in flags -> LONG_CITY
                    "c" in flags -> CITY
                    else -> null
                }
                val scene = flags.firstOrNull { it.startsWith("s=") }?.let { SkyScene.fromKey(it.removePrefix("s=")) }
                val longWords = "lc" in flags
                val widthOverride = flags.firstOrNull { it.startsWith("w=") }?.removePrefix("w=")?.toIntOrNull()
                val model: WidgetModel = if (place == null) {
                    if (longWords) singapore.copy(condition = LONGEST_NEA_WORDS) else singapore
                } else {
                    CityModel(place, 31.0, if (longWords) LONGEST_WMO_CODE else 61, true, 82, place.timezone, now)
                }
                val widthForShape = widthOverride ?: widthDp(shape)
                val fit = WidgetFits.of(shape, heightDp, eventCount > 0, fontScale, widthForShape)
                val plan = WidgetUpdater.planOf(context, shape, heightDp, widthForShape, eventCount)
                val caption = captionOf(shape, heightDp, eventCount, fit, plan, place?.nameAndCountry, scene) +
                    (if (longWords) " | long words" else "") + (widthOverride?.let { " | w=$it" } ?: "")
                Log.i(TAG, "preview $caption fontScale=$fontScale")

                val remote = WidgetUpdater.views(
                    context, shape, model, id++, events, now, heightDp, scene, widthForShape
                )
                val frame = FrameLayout(context)
                // The application context, as a home screen inflates it. An activity's context would swap the
                // TextViews for AppCompat's, which RemoteViews cannot set a background on.
                val drawn = remote.apply(context.applicationContext, frame)
                frame.addView(
                    drawn,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                // Once it is on screen and laid out, look at it the way a person would (see WidgetPreviewCheck).
                // A long city or weather phrase is cut on purpose, so the weather check only runs without them.
                val keepWeatherWhole = place == null && !longWords
                frame.postDelayed({
                    val problems = WidgetPreviewCheck.problems(drawn, shape, plan, density, minLines, keepWeatherWhole)
                    val summary = "${shape.key} ${heightDp}dp w=$widthForShape fontScale=$fontScale date=${plan.dateSpot.name.lowercase()} " +
                        "clock=${plan.clockSp} lines=${plan.lines} (events ${plan.events}, more ${plan.more})"
                    Log.i(TAG, "check $summary: " + if (problems.isEmpty()) "ok" else "FAIL " + problems.joinToString("; "))
                }, CHECK_DELAY_MS)
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, px(6), px(6))
                }
                cell.addView(
                    TextView(context).apply {
                        text = caption
                        setTextColor(Color.WHITE)
                        textSize = 10f
                    }
                )
                cell.addView(frame, LinearLayout.LayoutParams(px(widthForShape), px(heightDp)))
                row.addView(cell)
            }
            column.addView(row)
        }
        return ScrollView(context).apply { addView(column) }
    }

    /** "small 115dp: clock 22, temp 20, date under". What the widget below it was told to do. */
    private fun captionOf(
        shape: WidgetShape,
        heightDp: Int,
        eventCount: Int,
        fit: WidgetFit?,
        plan: RowPlan,
        city: String?,
        scene: SkyScene?
    ): String {
        val parts = StringBuilder("${shape.key} ${heightDp}dp")
        if (eventCount == 1) parts.append(" +event") else if (eventCount > 1) parts.append(" +$eventCount events")
        parts.append(": clock ${plan.clockSp}")
        if (fit != null) parts.append(", temp ${fit.tempSp}")
        parts.append(if (!plan.showDate) ", no date" else if (plan.dateSpot == DateSpot.BESIDE) ", date beside" else ", date under")
        if (eventCount > 0) {
            parts.append(if (plan.lines == 0) ", no event" else ", ${plan.lines} lines")
            if (plan.more > 0) parts.append(" (+${plan.more} more)")
        }
        if (city != null) parts.append(" | $city")
        if (scene != null) parts.append(" | ${scene.key}")
        return parts.toString()
    }
}
