package io.github.rayvader.hazemap

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The place search against a fake server on this machine that stalls: it must give up quickly and try again. */
class QuickSearchTest {

    private val body = """{"results":[{"id":1850147,"name":"Tokyo","latitude":35.6895,"longitude":139.69171,""" +
        """"country_code":"JP","timezone":"Asia/Tokyo","country":"Japan","admin1":"Tokyo"}]}"""

    /** Keeps stalled sockets open (no answer at all) until the test ends. */
    private val held = CopyOnWriteArrayList<Socket>()
    private var server: ServerSocket? = null

    /** A server whose first [stalls] connections get no answer; the rest get the place search answer. */
    private fun serve(stalls: Int): Pair<String, AtomicInteger> {
        val s = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        server = s
        val seen = AtomicInteger()
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (e: IOException) { break }
                if (seen.incrementAndGet() <= stalls) {
                    held.add(sock)
                    continue
                }
                thread(isDaemon = true) {
                    sock.use {
                        val reader = it.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val bytes = body.toByteArray()
                        it.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray() + bytes
                        )
                        it.getOutputStream().flush()
                    }
                }
            }
        }
        return "http://127.0.0.1:${s.localPort}/v1/search?name=tokyo" to seen
    }

    @After
    fun stop() {
        server?.close()
        held.forEach { runCatching { it.close() } }
    }

    @Test
    fun aStalledFirstTryIsCutOffAndTheRetryFindsThePlace() {
        val (url, seen) = serve(stalls = 1)
        val started = System.nanoTime()
        val found = runBlocking { PlaceParsers.geocoding(Http.getTextQuick(url)) }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals("Tokyo", found.single().name)
        assertEquals("Japan", found.single().country)
        assertTrue("took $ms ms", ms < 20_000)
        // The first try waited its full time before it was cut off.
        assertTrue("took $ms ms", ms >= Http.QUICK_ATTEMPT_MS - 500)
        assertTrue("connections: ${seen.get()}", seen.get() >= 2)
    }

    @Test
    fun aServerThatNeverAnswersFailsInUnder20Seconds() {
        val (url, _) = serve(stalls = Int.MAX_VALUE)
        val started = System.nanoTime()
        try {
            runBlocking { Http.getTextQuick(url) }
            fail("a server that never answers gave an answer")
        } catch (e: IOException) {
            // "Search failed. Check your connection." in the app.
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 20_000)
    }

    @Test
    fun aQuickAnswerNeedsNoRetry() {
        val (url, seen) = serve(stalls = 0)
        val started = System.nanoTime()
        val text = runBlocking { Http.getTextQuick(url) }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(text.contains("Tokyo"))
        assertEquals(1, seen.get())
        assertTrue("took $ms ms", ms < 5_000)
    }
}
