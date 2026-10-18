package io.github.rayvader.hazemap

import java.io.File
import java.io.IOException

/**
 * Keeps the last good response of each endpoint on disk, so the app can paint at once on launch
 * and keep working offline. Best effort: a failed read or write just means no cache.
 */
class JsonCache(private val dir: File) {

    class Entry(val body: String, val savedAtMs: Long)

    init {
        dir.mkdirs()
    }

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    @Synchronized
    fun read(key: String): Entry? {
        val f = file(key)
        if (!f.isFile) return null
        return try {
            Entry(f.readText(), f.lastModified())
        } catch (e: IOException) {
            null
        }
    }

    @Synchronized
    fun write(key: String, body: String) {
        try {
            val f = file(key)
            f.writeText(body)
            f.setLastModified(System.currentTimeMillis())
        } catch (e: IOException) {
            // The cache is an optimisation only.
        }
    }
}
