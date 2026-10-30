package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The widget pictures are hand-written XML. A wrong part name stops the whole widget loading on the
 * home screen, and a picture that keeps moving costs battery, so both are checked here.
 */
class WidgetResourcesTest {

    // Gradle runs unit tests from the app folder; an IDE may run them from the project folder.
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun parse(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(res, path)).documentElement

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    private fun Element.descendants(): List<Element> = children().flatMap { listOf(it) + it.descendants() }

    private fun Element.attr(name: String): String? = getAttributeNS(ANDROID, name).ifEmpty { null }

    /** The named groups and paths a picture's animations can move. */
    private fun parts(scene: Element): Map<String, Element> =
        scene.descendants().filter { it.localName in PART_KINDS && it.attr("name") != null }
            .associateBy { it.attr("name")!! }

    private fun targets(scene: Element): List<Element> = scene.children().filter { it.localName == "target" }

    /** A target's animation: an objectAnimator or a set, inside an inline aapt:attr. */
    private fun Element.animation(): Element = children().single { it.localName == "attr" }.children().single()

    @Test
    fun everyWidgetLayoutHoldsEveryPicture() {
        for (layout in listOf("widget_small", "widget_wide", "widget_strip")) {
            val bars = parse("layout/$layout.xml").descendants().filter { it.localName == "ProgressBar" }
            val drawableById = bars.associate { it.attr("id") to it.attr("indeterminateDrawable") }
            for (s in SkyScene.entries) {
                // "partly_night" is the view wScenePartlyNight.
                val id = "@+id/wScene" + s.key.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
                assertEquals("$layout $id", "@drawable/scene_${s.key}", drawableById[id])
            }
            assertEquals("every picture in $layout", SkyScene.entries.size, bars.size)
            assertEquals("$layout shows one picture before the app draws", 1, bars.count { it.attr("visibility") == null })
        }
    }

    @Test
    fun everyPictureHasASky() {
        for (s in SkyScene.entries) assertTrue(s.key, File(res, "drawable/widget_sky_${s.key}.xml").isFile)
    }

    @Test
    fun everyNightPictureIsLitByTheGlowOfTheMoonOrTheLightning() {
        for (s in SkyScene.entries.filter { it.isNight }) {
            val names = parts(parse("drawable/scene_${s.key}.xml")).keys
            assertTrue("${s.key} has no glow", "moonGlow" in names || "boltGlow" in names)
        }
    }

    @Test
    fun thePlaceLineIsOneLineThatEndsInDotsWhenItRunsOut() {
        // A city comes with its country, which can be long: the line must never wrap or push the rest around.
        for (layout in listOf("widget_small", "widget_wide", "widget_strip")) {
            val place = views(layout).single { it.attr("id") == "@+id/wPlace" }
            assertEquals("$layout lines", "1", place.attr("maxLines"))
            assertEquals("$layout ellipsize", "end", place.attr("ellipsize"))
        }
    }

    @Test
    fun theWideShowsTheTemperatureTheWeatherAndThePlaceOnOneLine() {
        val row = views("widget_wide").single { parent -> parent.children().any { it.attr("id") == "@+id/wTemp" } }
        assertEquals(
            listOf("@+id/wTemp", "@+id/wCondition", "@+id/wPlace"),
            row.children().mapNotNull { it.attr("id") }
        )
        assertEquals("horizontal", row.attr("orientation"))
        // Their texts differ in size, so they sit on one baseline.
        assertEquals("true", row.attr("baselineAligned"))
        // The place takes what the temperature and the weather leave. A long weather phrase stops at maxWidth,
        // so it cannot push the place out of the line; both end in "…" instead of wrapping.
        val place = row.children().single { it.attr("id") == "@+id/wPlace" }
        assertEquals("0dp", place.attr("layout_width"))
        assertEquals("1", place.attr("layout_weight"))
        val condition = row.children().single { it.attr("id") == "@+id/wCondition" }
        assertNotNull(condition.attr("maxWidth"))
        assertEquals("1", condition.attr("maxLines"))
        assertEquals("end", condition.attr("ellipsize"))
    }

    /** Every view in a layout, the root included. */
    private fun views(layout: String): List<Element> = parse("layout/$layout.xml").let { listOf(it) + it.descendants() }

    @Test
    fun everyLayoutHasTheViewsTheAppFillsIn() {
        // A view missing from one layout only fails on a phone, when the widget refuses to draw.
        val shared = listOf("wRoot", "wClock", "wDate", "wTemp", "wCondition", "wPlace", "wBadge")
        val eventLine = listOf("wEventRow", "wEventIcon", "wEventText")
        // The two blocks whose space the app trims on a short widget (see WidgetFit).
        val trimmed = listOf("wTopBlock", "wBottomBlock")
        for (shape in WidgetShape.entries) {
            val layout = "widget_${shape.key}"
            val wanted = shared +
                (if (shape == WidgetShape.WIDE) emptyList() else listOf("wWhen")) +
                (if (shape.hasEventLine) eventLine else emptyList()) +
                (if (WidgetFits.of(shape, 100, false) != null) trimmed else emptyList())
            val have = views(layout).mapNotNull { it.attr("id") }.toSet()
            for (id in wanted) assertTrue("$layout has no $id", "@+id/$id" in have)
        }
    }

    @Test
    fun theEventLineIsHiddenUntilThereIsAnEvent() {
        // The widget picker's preview, and a phone with no calendar chosen, show no empty line.
        for (shape in WidgetShape.entries.filter { it.hasEventLine }) {
            val row = views("widget_${shape.key}").single { it.attr("id") == "@+id/wEventRow" }
            assertEquals(shape.key, "gone", row.attr("visibility"))
        }
    }

    @Test
    fun calendarChangesReachTheWidgets() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(res.parentFile, "AndroidManifest.xml")).documentElement
        val all = manifest.descendants()
        assertTrue(all.any { it.localName == "uses-permission" && it.attr("name") == "android.permission.READ_CALENDAR" })
        // The calendar provider's broadcast comes from another app, so the receiver must be open to it.
        val receiver = all.single { it.localName == "receiver" && it.attr("name") == ".CalendarChangedReceiver" }
        assertEquals("true", receiver.attr("exported"))
        val inside = receiver.descendants()
        assertTrue(inside.any { it.localName == "action" && it.attr("name") == "android.intent.action.PROVIDER_CHANGED" })
        assertTrue(inside.any { it.localName == "data" && it.attr("host") == "com.android.calendar" })
        // The alarm that redraws at an event's start and end is only for this app.
        val alarm = all.single { it.localName == "receiver" && it.attr("name") == ".WidgetRefreshReceiver" }
        assertEquals("false", alarm.attr("exported"))
        assertTrue(File(res.parentFile, "java/io/github/rayvader/hazemap/CalendarsActivity.kt").isFile)
    }

    @Test
    fun theClockAndDateAreRealClocksSoTheyCanTakeATimeZone() {
        for (layout in listOf("widget_small", "widget_wide", "widget_strip")) {
            for (id in listOf("wClock", "wDate")) {
                val view = views(layout).single { it.attr("id") == "@+id/$id" }
                assertEquals("$layout $id", "TextClock", view.localName)
            }
        }
    }

    @Test
    fun theClockIsBold() {
        // Roboto Bold is about 6% wider than the Light the clock had, which WidgetFits counts on (CLOCK_DP_PER_SP).
        for (layout in listOf("widget_small", "widget_wide", "widget_strip")) {
            val clock = views(layout).single { it.attr("id") == "@+id/wClock" }
            assertEquals("$layout weight", "bold", clock.attr("textStyle"))
            assertEquals("$layout family", "sans-serif", clock.attr("fontFamily"))
        }
    }

    @Test
    fun theClockIsAlways24Hour() {
        // A TextClock picks one of its two formats by the phone's setting, so both must be 24-hour.
        for (layout in listOf("widget_small", "widget_wide", "widget_strip")) {
            val clock = views(layout).single { it.attr("id") == "@+id/wClock" }
            assertEquals("$layout 12-hour setting", "HH:mm", clock.attr("format12Hour"))
            assertEquals("$layout 24-hour setting", "HH:mm", clock.attr("format24Hour"))
        }
    }

    @Test
    fun theDateIsInEveryLayoutUnderTheClockAndShownUntilTheAppAsksOtherwise() {
        // The widget picker's preview, drawn before the app knows the height, shows the date under the clock.
        for (layout in listOf("widget_small", "widget_wide")) {
            val top = views(layout).single { it.attr("id") == "@+id/wTopBlock" }
            val order = top.children().mapNotNull { it.attr("id") }
            assertEquals(layout, listOf("@+id/wClock", "@+id/wDate"), order.take(2))
            assertNull(layout, top.children().single { it.attr("id") == "@+id/wDate" }.attr("visibility"))
        }
    }

    @Test
    fun theWidgetPlaceScreenIsDeclaredTheWayTheLauncherNeedsIt() {
        val main = res.parentFile
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(main, "AndroidManifest.xml")).documentElement
        val screen = manifest.descendants().single { it.localName == "activity" && it.attr("name") == ".WidgetPlaceActivity" }
        assertEquals("true", screen.attr("exported"))
        val actions = screen.descendants().filter { it.localName == "action" }.mapNotNull { it.attr("name") }
        assertTrue(actions.contains("android.appwidget.action.APPWIDGET_CONFIGURE"))

        for (info in listOf("widget_small_info", "widget_wide_info", "widget_strip_info")) {
            val provider = parse("xml/$info.xml")
            assertEquals(info, "io.github.rayvader.hazemap.WidgetPlaceActivity", provider.attr("configure"))
            assertEquals(info, "reconfigurable", provider.attr("widgetFeatures"))
        }
        // The class is named in XML, which the compiler does not check, so look for its file.
        assertTrue(File(main, "java/io/github/rayvader/hazemap/WidgetPlaceActivity.kt").isFile)
    }

    @Test
    fun picturesOnlyMovePartsTheyHave() {
        for (s in SkyScene.entries) {
            val scene = parse("drawable/scene_${s.key}.xml")
            val parts = parts(scene)
            assertTrue("${s.key} does not move", targets(scene).isNotEmpty())
            for (t in targets(scene)) {
                val name = t.attr("name")!!
                val part = parts[name]
                assertNotNull("${s.key} has no part $name", part)
                val moved = t.descendants().mapNotNull { it.attr("propertyName") }
                val allowed = if (part!!.localName == "group") GROUP_PROPERTIES else PATH_PROPERTIES
                assertTrue("${s.key}: a ${part.localName} cannot animate $moved", allowed.containsAll(moved))
            }
        }
    }

    @Test
    fun picturesMoveForAboutTenSecondsThenRestOnTheStillPicture() {
        for (s in SkyScene.entries) {
            val scene = parse("drawable/scene_${s.key}.xml")
            val parts = parts(scene)
            var longest = 0L
            for (t in targets(scene)) {
                val name = t.attr("name")!!
                val animation = t.animation()
                longest = maxOf(longest, runMillis(animation))
                for ((property, end) in endValues(animation)) {
                    val still = parts.getValue(name).attr(property)?.toFloat() ?: STILL_DEFAULTS.getValue(property)
                    // The sun's eight rays look the same after every 45 degrees of turn.
                    val gap = if (property == "rotation") (end - still) % 45f else end - still
                    assertEquals("${s.key}: $name $property rests at $end, the still picture has $still", 0f, gap, 0.001f)
                }
            }
            assertTrue("${s.key} moves for $longest ms", longest in 8_000L..11_000L)
        }
    }

    /** How long an animation runs. One that repeats forever fails the test. */
    private fun runMillis(e: Element): Long {
        if (e.localName == "set") {
            val runs = e.children().map { runMillis(it) }
            return if (e.attr("ordering") == "sequentially") runs.sum() else runs.max()
        }
        val repeats = repeatCount(e)
        assertTrue("an animation repeats forever", repeats >= 0)
        return (e.attr("startOffset")?.toLong() ?: 0L) + (e.attr("duration")?.toLong() ?: 300L) * (repeats + 1)
    }

    /** Where each property ends up. In a sequence the later animation wins. */
    private fun endValues(e: Element): Map<String, Float> {
        if (e.localName == "set") {
            return e.children().fold(mutableMapOf<String, Float>()) { acc, c -> acc.apply { putAll(endValues(c)) } }
        }
        // Going back and forth an even number of times ends where it began.
        val backAtStart = e.attr("repeatMode") == "reverse" && repeatCount(e) % 2 == 1
        val property = e.attr("propertyName")
        if (property != null) {
            return mapOf(property to (if (backAtStart) e.attr("valueFrom") else e.attr("valueTo"))!!.toFloat())
        }
        return e.children().filter { it.localName == "propertyValuesHolder" }.associate { holder ->
            val frames = holder.children().filter { it.localName == "keyframe" }
            holder.attr("propertyName")!! to (if (backAtStart) frames.first() else frames.last()).attr("value")!!.toFloat()
        }
    }

    private fun repeatCount(e: Element): Int = when (val r = e.attr("repeatCount")) {
        null -> 0
        "infinite" -> -1
        else -> r.toInt()
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        val PART_KINDS = setOf("group", "path")
        val GROUP_PROPERTIES = setOf("rotation", "pivotX", "pivotY", "scaleX", "scaleY", "translateX", "translateY")
        val PATH_PROPERTIES = setOf(
            "fillColor", "strokeColor", "fillAlpha", "strokeAlpha", "strokeWidth", "pathData",
            "trimPathStart", "trimPathEnd", "trimPathOffset"
        )

        /** What a part looks like when its XML leaves the property out. */
        val STILL_DEFAULTS = mapOf(
            "rotation" to 0f, "translateX" to 0f, "translateY" to 0f, "scaleX" to 1f, "scaleY" to 1f,
            "fillAlpha" to 1f, "strokeAlpha" to 1f
        )
    }
}
