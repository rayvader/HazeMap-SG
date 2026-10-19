package io.github.rayvader.hazemap

import java.io.File
import java.io.IOException

/**
 * Radar pictures kept on the phone. A radar frame never changes once RainViewer has published it,
 * so a saved picture is reused as it is: the app downloads only the frames it does not have yet, and
 * the last two hours can be shown with no network at all. Best effort: a failed read or write just
 * means the picture is fetched again.
 */
class RadarCache(private val dir: File) {

    init {
        dir.mkdirs()
    }

    /** A file name for [url] that cannot point outside [dir]. */
    fun fileName(url: String): String {
        val plain = url.substringAfter("://").replace(Regex("[^A-Za-z0-9._-]"), "_")
        // Very long names fail on some file systems; a hash keeps the short form unique.
        return if (plain.length <= MAX_NAME) plain else plain.take(MAX_NAME - 9) + "_" + plain.hashCode().toUInt().toString(16)
    }

    private fun file(url: String) = File(dir, fileName(url))

    @Synchronized
    fun read(url: String): ByteArray? {
        val f = file(url)
        if (!f.isFile) return null
        return try {
            f.readBytes()
        } catch (e: IOException) {
            null
        }
    }

    /** Writes to a temporary file first, so a picture cut short by the app closing is never read as whole. */
    @Synchronized
    fun write(url: String, bytes: ByteArray) {
        try {
            val target = file(url)
            val temp = File(dir, target.name + TEMP)
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) temp.delete()
        } catch (e: IOException) {
            // The cache is an optimisation only.
        }
    }

    @Synchronized
    fun delete(url: String) {
        file(url).delete()
    }

    /** Removes every saved picture except those of [urls]: the frames that have scrolled out of the last two hours. */
    @Synchronized
    fun keepOnly(urls: Collection<String>) {
        val keep = urls.map { fileName(it) }.toSet()
        for (f in dir.listFiles().orEmpty()) {
            if (f.isFile && f.name !in keep) f.delete()
        }
    }

    private companion object {
        const val MAX_NAME = 150
        const val TEMP = ".part"
    }
}
