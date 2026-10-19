package io.github.rayvader.hazemap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RadarCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val urlA = "https://tilecache.rainviewer.com/v2/radar/1792291200/256/7/100/63/2/1_0.png"
    private val urlB = "https://tilecache.rainviewer.com/v2/radar/1792291800/256/7/101/63/2/1_0.png"

    private fun cache() = RadarCache(folder.newFolder())

    @Test
    fun aSavedPictureComesBackUnchanged() {
        val c = cache()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        c.write(urlA, bytes)
        assertArrayEquals(bytes, c.read(urlA))
    }

    @Test
    fun aPictureThatWasNeverSavedIsMissing() {
        assertNull(cache().read(urlA))
    }

    @Test
    fun differentPicturesDoNotShareAFile() {
        val c = cache()
        c.write(urlA, byteArrayOf(1))
        c.write(urlB, byteArrayOf(2))
        assertArrayEquals(byteArrayOf(1), c.read(urlA))
        assertArrayEquals(byteArrayOf(2), c.read(urlB))
    }

    @Test
    fun writingAgainReplacesThePicture() {
        val c = cache()
        c.write(urlA, byteArrayOf(1))
        c.write(urlA, byteArrayOf(9, 9))
        assertArrayEquals(byteArrayOf(9, 9), c.read(urlA))
    }

    @Test
    fun deletingForgetsThePicture() {
        val c = cache()
        c.write(urlA, byteArrayOf(1))
        c.delete(urlA)
        assertNull(c.read(urlA))
    }

    @Test
    fun keepOnlyRemovesTheFramesThatScrolledOut() {
        val dir = folder.newFolder()
        val c = RadarCache(dir)
        c.write(urlA, byteArrayOf(1))
        c.write(urlB, byteArrayOf(2))
        c.keepOnly(listOf(urlB))
        assertNull(c.read(urlA))
        assertNotNull(c.read(urlB))
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun keepOnlyAnEmptyListClearsEverything() {
        val c = cache()
        c.write(urlA, byteArrayOf(1))
        c.keepOnly(emptyList())
        assertNull(c.read(urlA))
    }

    @Test
    fun nothingIsLeftOverAfterASave() {
        val dir = folder.newFolder()
        RadarCache(dir).write(urlA, byteArrayOf(1))
        val names = dir.listFiles()!!.map { it.name }
        assertEquals(1, names.size)
        assertFalse(names.single().endsWith(".part"))
    }

    @Test
    fun aFileNameStaysInsideTheFolder() {
        val c = cache()
        for (url in listOf("https://x.test/../../etc/passwd", "https://x.test/a/b/c.png?x=1&y=2", "no scheme/../x")) {
            val name = c.fileName(url)
            assertFalse(name, name.contains('/'))
            assertFalse(name, name.contains('\\'))
            assertTrue(name.isNotEmpty())
        }
    }

    @Test
    fun veryLongAddressesGetShortUniqueNames() {
        val c = cache()
        val long1 = "https://x.test/" + "a".repeat(400) + "1"
        val long2 = "https://x.test/" + "a".repeat(400) + "2"
        assertTrue(c.fileName(long1).length < 200)
        assertTrue(c.fileName(long1) != c.fileName(long2))
    }

    @Test
    fun theSameAddressAlwaysGivesTheSameName() {
        val c = cache()
        assertEquals(c.fileName(urlA), c.fileName(urlA))
        assertTrue(c.fileName(urlA) != c.fileName(urlB))
    }
}
