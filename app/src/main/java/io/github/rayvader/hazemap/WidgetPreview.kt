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

    /** The width each shape gets on a four-column phone, in dp. */
    private fun widthDp(shape: WidgetShape): Int = if (shape == WidgetShape.SMALL) 180 else 362

    /**
     * One widget per entry like "wide@140+e": the shape, "@", the height in dp, and "+e" for an event line.
     * Entries in a row are split by ",", rows by "/". A row has to fit the screen, which is about 390 dp
     * wide on the test emulator: one Wide or Strip, or two Squares.
     */
    fun build(context: Context, spec: String): View {
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt()
        val fontScale = context.resources.configuration.fontScale
        val now = System.currentTimeMillis()
        val model = SingaporeModel("Punggol", 27.0, "Light Rain", 73, now)
        val event = CalendarEvent(1, 1, "Joya Onsen", now, now, allDay = false)
        val line = WidgetUpdater.EventLine(event, "Sun 2 pm Joya Onsen")

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111418.toInt())
            setPadding(px(6), px(6), px(6), px(6))
        }
        var id = FIRST_ID
        for (rowSpec in spec.split("/")) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (entry in rowSpec.split(",")) {
                val shape = WidgetShape.fromKey(entry.substringBefore("@")) ?: continue
                val heightDp = entry.substringAfter("@").substringBefore("+").toIntOrNull() ?: continue
                val withEvent = entry.endsWith("+e")
                val fit = WidgetFits.of(shape, heightDp, withEvent, fontScale)
                val caption = captionOf(shape, heightDp, withEvent, fit)
                Log.i(TAG, "preview $caption fontScale=$fontScale")

                val remote = WidgetUpdater.views(context, shape, model, id++, if (withEvent) line else null, now, heightDp)
                val frame = FrameLayout(context)
                frame.addView(
                    remote.apply(context, frame),
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
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
                cell.addView(frame, LinearLayout.LayoutParams(px(widthDp(shape)), px(heightDp)))
                row.addView(cell)
            }
            column.addView(row)
        }
        return ScrollView(context).apply { addView(column) }
    }

    /** "wide 140dp +event: clock 34, temp 24, no date". What the widget below it was told to do. */
    private fun captionOf(shape: WidgetShape, heightDp: Int, withEvent: Boolean, fit: WidgetFit?): String {
        val parts = StringBuilder("${shape.key} ${heightDp}dp")
        if (withEvent) parts.append(" +event")
        if (fit != null) {
            parts.append(": clock ${fit.clockSp}, temp ${fit.tempSp}")
            if (!fit.showDate) parts.append(", no date")
            if (withEvent && !fit.showEvent) parts.append(", no event")
        }
        return parts.toString()
    }
}
