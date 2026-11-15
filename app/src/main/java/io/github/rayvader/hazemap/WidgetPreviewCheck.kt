package io.github.rayvader.hazemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
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

    private const val TAG = "HazeWidget"

    /** Room kept between two parts, in dp, and between the last event line and the numbers' digits. */
    private const val MIN_GAP_DP = 1f
    private const val MIN_DIGITS_GAP_DP = 3f

    /** A pixel of a picture counts as art above this alpha. */
    private const val ART_ALPHA = 24

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
        keepWeatherWhole: Boolean,
        summary: MutableMap<String, String> = HashMap()
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

        // The picture's art, as drawn, against the PSI pill under it.
        val art = artBox(root, density)
        if (art != null) {
            summary["artBottom"] = "%.1f".format(dp(art.bottom))
            summary["artTop"] = "%.1f".format(dp(art.top))
            val pill = box(root.findViewById(R.id.wBadge))
            if (shape == WidgetShape.WIDE && pill != null && Rect.intersects(art, Rect(pill.left, pill.top - density.toInt(), pill.right, pill.bottom))) {
                out += "the picture's art (down to ${dp(art.bottom)} dp) overlaps the PSI pill (from ${dp(pill.top)} dp)"
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
        // The count of the events left over: a chip on the last line only, whole, and there whenever events are hidden.
        rows.forEachIndexed { i, row ->
            val chip = row.findViewById<TextView>(R.id.wEventMore)
            val wantChip = plan.more > 0 && i == rows.lastIndex
            if (wantChip) {
                if (!chip.isShown) out += "the last line has no count of the ${plan.more} hidden events"
                whole("the count of hidden events", chip)
                if (!chip.text.toString().contains("${plan.more}")) out += "the count says '${chip.text}', not ${plan.more}"
            } else if (chip.isShown) {
                out += "event line ${i + 1} has a count but is not the last"
            }
        }
        val temp = root.findViewById<TextView>(R.id.wTemp)
        val numbers = box(temp)
        // The numbers' digits start under the top of their box: measure to the digits themselves.
        val digitsTop = if (numbers != null && temp != null) {
            val ink = Rect()
            temp.paint.getTextBounds(temp.text.toString(), 0, temp.text.length, ink)
            numbers.top + temp.baseline + ink.top
        } else {
            null
        }
        if (digitsTop != null && numbers != null) {
            summary["digitsTopEm"] = "%.3f".format((digitsTop - numbers.top) / temp.textSize)
        }
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
        if (last != null && digitsTop != null && last.bottom > digitsTop - MIN_DIGITS_GAP_DP * density) {
            out += "the last event line is ${dp(digitsTop - last.bottom)} dp from the numbers' digits"
        }
        if (first != null && picture != null && shape == WidgetShape.SMALL &&
            first.top < picture.bottom - ART_ABOVE_BOX_END_DP * density
        ) {
            out += "the first event line is under the picture's art"
        }
        return out
    }

    private fun dp1(density: Float) = density.toInt().coerceAtLeast(1)

    /**
     * Where the sky picture's art is, in the widget's pixels: the picture is drawn into a bitmap and its
     * opaque part found, then placed the way its box is (moved and shrunk about its pivot).
     */
    private fun artBox(root: View, density: Float): Rect? {
        val bar = findPicture(root) ?: return null
        val box = bar.parent as? View ?: return null
        if (bar.width == 0 || bar.height == 0) return null
        val bitmap = Bitmap.createBitmap(bar.width, bar.height, Bitmap.Config.ARGB_8888)
        bar.draw(Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var top = Int.MAX_VALUE
        var bottom = -1
        var left = Int.MAX_VALUE
        var right = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if ((pixels[y * bitmap.width + x] ushr 24) > ART_ALPHA) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        bitmap.recycle()
        if (bottom < 0) return null
        // The box sits at box.left/top inside the widget, then shrinks about its pivot.
        val k = box.scaleX
        fun x(px: Int) = (box.left + box.pivotX + (px + bar.left - box.pivotX) * k).toInt()
        fun y(py: Int) = (box.top + box.pivotY + (py + bar.top - box.pivotY) * k).toInt()
        return Rect(x(left), y(top), x(right), y(bottom))
    }

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
