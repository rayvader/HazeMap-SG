package io.github.rayvader.hazemap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Books call slots so no more than [maxCalls] start within any [windowMs].
 *
 * data.gov.sg allows 6 calls per 10 seconds without an API key. We stay at 5 so a call
 * from another app on the same address does not tip us over.
 */
class RateLimiter(private val maxCalls: Int, private val windowMs: Long) {
    private val starts = ArrayDeque<Long>()

    /** Books a slot and returns how long (ms) the caller must wait before using it. */
    @Synchronized
    fun reserve(nowMs: Long): Long {
        val start = if (starts.size < maxCalls) nowMs else maxOf(nowMs, starts.first() + windowMs)
        starts.addLast(start)
        while (starts.size > maxCalls) starts.removeFirst()
        return start - nowMs
    }
}

object Http {
    private const val USER_AGENT = "HazeMapSG/1.0 (Android; +https://github.com/rayvader/HazeMap-SG)"
    private const val RETRY_AFTER_429_MS = 10_500L
    private const val MAX_429_RETRIES = 2

    /** Shared by every data.gov.sg call in the app. */
    private val neaLimiter = RateLimiter(maxCalls = 5, windowMs = 10_000)

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    /** GET [url] as text. data.gov.sg calls ([limited] = true) wait for a slot and retry on HTTP 429. */
    suspend fun getText(url: String, limited: Boolean = true): String {
        var retries = 0
        while (true) {
            if (limited) {
                val wait = neaLimiter.reserve(nowMs())
                if (wait > 0) delay(wait)
            }
            val response = withContext(Dispatchers.IO) { fetch(url) }
            when {
                response.code in 200..299 -> return response.body.toString(Charsets.UTF_8)
                response.code == 429 && retries < MAX_429_RETRIES -> {
                    retries++
                    delay(RETRY_AFTER_429_MS)
                }
                else -> throw IOException("HTTP ${response.code} for $url")
            }
        }
    }

    /** GET [url] as raw bytes (map tiles). Not rate limited. */
    suspend fun getBytes(url: String): ByteArray {
        val response = withContext(Dispatchers.IO) { fetch(url) }
        if (response.code !in 200..299) throw IOException("HTTP ${response.code} for $url")
        return response.body
    }

    private class Response(val code: Int, val body: ByteArray)

    private fun fetch(url: String): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json, image/png, */*")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Response(code, body)
        } finally {
            conn.disconnect()
        }
    }
}
