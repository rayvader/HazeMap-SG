package io.github.rayvader.hazemap

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Looks at a widget the preview has drawn (see [WidgetPreview]) the way a person would: is the time on one
 * line, is the date on its row (or under the clock), is any text cut or covered, do the event lines stay clear
 * of the numbers, the picture and each other. Only the emulator test uses it; it reads the real views, so it
 * sees what the home screen would draw.
 */
internal object WidgetPreviewCheck {

    /** Room kept between two parts, in dp. */
    private const val MIN_GAP_DP = 1f

    /** The tap area for the clock and the date: at least this tall. */
    private const val MIN_TAP_DP = 48f

    /** The Square's sky art reaches 63 dp down inside a picture box that ends at 70 dp. */
    private const val ART_ABOVE_BOX_END_DP = 4f

    /** What is wrong with the drawing, or nothing. [minLines] is the least event lines the test wants to see. */
    fun problems(
        root: View,
        shape: WidgetShape,
        plan: RowPlan,
        density: Float,
        minLines: Int,
        keepWeatherWhole: Boolean
    ): List<String> {
        val out = ArrayList<String>()
        fun dp(px: Int) = px / density

        fun box(v: View?): Rect? {
            if (v == null || !v.isShown || v.width == 0 || v.height == 0) return null
            val a = IntArray(2)
            val b = IntArray(2)
            v.getLocationInWindow(a)
            root.getLocationInWindow(b)
            return Rect(a[0] - b[0], a[1] - b[1], a[0] - b[0] + v.width, a[1] - b[1] + v.height)
        }

        fun whole(label: String, v: TextView?) {
            val layout = v?.layout ?: return
            if (v.lineCount != 1) out += "$label is on ${v.lineCount} lines"
            if (layout.getEllipsisCount(0) > 0) out += "$label is cut"
        }

        fun inside(label: String, r: Rect?) {
            if (r == null) return
            if (r.left < 0 || r.top < 0 || r.right > root.width || r.bottom > root.height) {
                out += "$label sticks out of the widget (${dp(r.left)}, ${dp(r.top)}, ${dp(r.right)}, ${dp(r.bottom)} dp)"
            }
        }

        val clock = root.findViewById<TextView>(R.id.wClock)
        val clockBox = box(clock)
        if (clockBox == null) {
            out += "no clock on screen"
            return out
        }
        whole("the time", clock)
        inside("the time", clockBox)

        val picture = findPicture(root)?.let { box(it) }
        val tap = box(root.findViewById(R.id.wClockTap))
        if (tap != null && dp(tap.height()) < MIN_TAP_DP - 0.5f) out += "the clock's tap area is ${dp(tap.height())} dp tall"

        // The date: on the clock's row, or under it.
        val beside = plan.dateSpot == DateSpot.BESIDE
        val dateView = root.findViewById<TextView>(if (beside) R.id.wDate else R.id.wDateWrapped)
        val other = root.findViewById<TextView>(if (beside) R.id.wDateWrapped else R.id.wDate)
        if (box(other) != null) out += "the date is on screen twice"
        val date = box(dateView)
        if (plan.showDate) {
            if (date == null) {
                out += "the date is not on screen"
            } else {
                whole("the date", dateView)
                inside("the date", date)
                if (beside) {
                    if (date.left < clockBox.right) out += "the date overlaps the time"
                    if (date.top < clockBox.top - dp1(density) || date.bottom > clockBox.bottom + 2 * dp1(density)) {
                        out += "the date is not on the time's row"
                    }
                } else if (date.top < clockBox.bottom - 2 * dp1(density)) {
                    out += "the date overlaps the time"
                }
                if (picture != null && date.right > picture.left && date.top < picture.bottom) out += "the date runs under the picture"
            }
        }

        if (shape == WidgetShape.STRIP) {
            // The Strip's row must not be squeezed by a date beside the clock: the weather stays whole.
            if (keepWeatherWhole) {
                whole("the Strip's temperature", root.findViewById(R.id.wTemp))
                whole("the Strip's weather", root.findViewById(R.id.wCondition))
                whole("the Strip's place", root.findViewById(R.id.wPlace))
            }
            inside("the Strip's PSI", box(root.findViewById(R.id.wBadge)))
            return out
        }

        // The event lines.
        val list = root.findViewById<ViewGroup>(R.id.wEvents)
        val rows = (0 until (list?.childCount ?: 0)).map { list!!.getChildAt(it) }.filter { it.isShown }
        if (rows.size != plan.lines) out += "drew ${rows.size} event lines, the plan has ${plan.lines}"
        if (plan.lines < minLines) out += "only ${plan.lines} event lines, wanted $minLines"
        val numbers = box(root.findViewById(R.id.wTemp))
        var previous: Rect? = null
        rows.forEachIndexed { i, row ->
            val r = box(row) ?: return@forEachIndexed
            inside("event line ${i + 1}", r)
            whole("event line ${i + 1}", row.findViewById(R.id.wEventText))
            previous?.let { if (r.top < it.bottom) out += "event line ${i + 1} overlaps the one above" }
            previous = r
            if (picture != null && shape == WidgetShape.WIDE && r.right > picture.left) {
                out += "event line ${i + 1} runs under the picture"
            }
        }
        val first = rows.firstOrNull()?.let { box(it) }
        val last = rows.lastOrNull()?.let { box(it) }
        if (first != null && tap != null && first.top < tap.bottom) out += "the first event line overlaps the clock"
        if (last != null && numbers != null && last.bottom > numbers.top - MIN_GAP_DP * density) {
            out += "the last event line is ${dp(numbers.top - last.bottom)} dp from the numbers"
        }
        if (first != null && picture != null && shape == WidgetShape.SMALL &&
            first.top < picture.bottom - ART_ABOVE_BOX_END_DP * density
        ) {
            out += "the first event line is under the picture's art"
        }
        return out
    }

    private fun dp1(density: Float) = density.toInt().coerceAtLeast(1)

    /** The sky picture on screen: the one view whose id starts with "wScene". */
    private fun findPicture(root: View): View? {
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.id != View.NO_ID && child.isShown) {
                val name = runCatching { child.resources.getResourceEntryName(child.id) }.getOrNull()
                if (name != null && name.startsWith("wScene")) return child
            }
            findPicture(child)?.let { return it }
        }
        return null
    }
}
