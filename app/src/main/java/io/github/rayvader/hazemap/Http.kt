package io.github.rayvader.hazemap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

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

    /** A quick call (the place search) gives each try this long to connect and answer. */
    const val QUICK_ATTEMPT_MS = 8_000

    /** One try, then one more: a stalled call fails or recovers in about 16 s. */
    const val QUICK_ATTEMPTS = 2

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

    /**
     * GET [url] as text, for a person waiting on the answer (the place search). Each try has [attemptMs] in all,
     * to connect and to answer: a stalled server is cut off then, whatever the connection is doing, and the
     * call is tried once more. So it fails, or finds its answer, in about [attempts] x [attemptMs]. Not rate
     * limited. An HTTP error is not tried again: the server did answer.
     */
    suspend fun getTextQuick(url: String, attemptMs: Int = QUICK_ATTEMPT_MS, attempts: Int = QUICK_ATTEMPTS): String {
        var last: IOException? = null
        repeat(attempts) {
            val response = try {
                attempt(url, attemptMs)
            } catch (e: IOException) {
                last = e
                return@repeat
            }
            if (response.code in 200..299) return response.body.toString(Charsets.UTF_8)
            throw IOException("HTTP ${response.code} for $url")
        }
        throw last ?: IOException("no try made for $url")
    }

    /**
     * One try with a hard limit. The read runs apart from the caller, so the limit holds even when the
     * connection does not notice the stall (or tries again by itself); the connection is closed at the limit.
     */
    private suspend fun attempt(url: String, ms: Int): Response {
        val opened = AtomicReference<HttpURLConnection?>()
        val job = CoroutineScope(Dispatchers.IO).async { fetch(url, ms, ms) { opened.set(it) } }
        try {
            return withTimeoutOrNull(ms.toLong()) { job.await() }
                ?: throw IOException("no answer in $ms ms from $url")
        } finally {
            if (!job.isCompleted) {
                job.cancel()
                opened.get()?.disconnect()
            }
        }
    }

    /** GET [url] as raw bytes (map tiles). Not rate limited. */
    suspend fun getBytes(url: String): ByteArray {
        val response = withContext(Dispatchers.IO) { fetch(url) }
        if (response.code !in 200..299) throw IOException("HTTP ${response.code} for $url")
        return response.body
    }

    /**
     * Streams [url] into [target], at most [maxBytes], calling [onProgress] with the bytes so far and the
     * total (-1 when the server does not say). It is written to a side file first, so a download cut
     * short never looks whole. Not rate limited.
     */
    suspend fun download(url: String, target: File, maxBytes: Long, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val part = File(target.path + ".part")
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code for $url")
                val total = conn.contentLengthLong
                if (total > maxBytes) throw IOException("too big: $total bytes")
                var done = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            done += n
                            if (done > maxBytes) throw IOException("too big")
                            output.write(buffer, 0, n)
                            onProgress(done, total)
                        }
                    }
                }
                if (total >= 0 && done != total) throw IOException("cut short at $done of $total bytes")
                target.delete()
                if (!part.renameTo(target)) throw IOException("could not save $target")
            } catch (e: Throwable) {
                part.delete()
                throw e
            } finally {
                conn.disconnect()
            }
        }
    }

    private class Response(val code: Int, val body: ByteArray)

    private fun fetch(
        url: String,
        connectMs: Int = 10_000,
        readMs: Int = 20_000,
        onOpen: (HttpURLConnection) -> Unit = {}
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        onOpen(conn)
        try {
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
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
