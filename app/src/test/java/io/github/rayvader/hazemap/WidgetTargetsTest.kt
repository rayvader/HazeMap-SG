package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetTargetsTest {

    private val tokyo = Place(1850147L, "Tokyo", "Tokyo", "Japan", "JP", 35.69, 139.69, "Asia/Tokyo")

    private fun roundTrip(target: WidgetTarget): WidgetTarget? = WidgetTargetCodec.decode(WidgetTargetCodec.encode(target))

    @Test
    fun singaporeIsWrittenDownAndReadBack() {
        assertEquals(WidgetTarget.Singapore, roundTrip(WidgetTarget.Singapore))
    }

    @Test
    fun aCityKeepsEverythingTheWidgetNeedsToShowIt() {
        val target = WidgetTarget.City(tokyo)
        assertEquals(target, roundTrip(target))
    }

    @Test
    fun aMissingOrDamagedValueIsNotATarget() {
        assertNull(WidgetTargetCodec.decode(null))
        assertNull(WidgetTargetCodec.decode(""))
        assertNull(WidgetTargetCodec.decode("   "))
        assertNull(WidgetTargetCodec.decode("tokyo"))
        assertNull(WidgetTargetCodec.decode("[]"))
        assertNull(WidgetTargetCodec.decode("""{"id":1}"""))
        assertNull(WidgetTargetCodec.decode("""[{"id":1}]"""))
    }
}
