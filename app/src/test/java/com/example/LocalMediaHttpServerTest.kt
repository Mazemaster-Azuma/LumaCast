package com.example

import com.example.core.network.FileMediaSource
import com.example.core.network.LocalMediaHttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class LocalMediaHttpServerTest {

    private lateinit var server: LocalMediaHttpServer
    private lateinit var sampleFile: File
    private lateinit var sampleBytes: ByteArray

    @Before
    fun setUp() {
        server = LocalMediaHttpServer()
        // 512 KB deterministic test payload to test multi-chunk buffered streaming & seeking
        sampleBytes = ByteArray(512 * 1024) { idx -> (idx % 251).toByte() }
        sampleFile = File.createTempFile("lumacast_http_test_", ".mp4").apply {
            writeBytes(sampleBytes)
            deleteOnExit()
        }
    }

    @After
    fun tearDown() {
        server.stop()
        sampleFile.delete()
    }

    @Test
    fun `range header parser handles closed, open-ended, suffix, and invalid ranges`() {
        val closed = LocalMediaHttpServer.parseRangeHeader("bytes=0-1023", 10_000L)
        assertNotNull(closed)
        assertEquals(0L, closed!!.start)
        assertEquals(1023L, closed.end)
        assertEquals(1024L, closed.length)

        val openEnd = LocalMediaHttpServer.parseRangeHeader("bytes=5000-", 10_000L)
        assertNotNull(openEnd)
        assertEquals(5000L, openEnd!!.start)
        assertEquals(9999L, openEnd.end)

        val suffix = LocalMediaHttpServer.parseRangeHeader("bytes=-512", 10_000L)
        assertNotNull(suffix)
        assertEquals(9488L, suffix!!.start)
        assertEquals(9999L, suffix.end)

        assertNull(LocalMediaHttpServer.parseRangeHeader("bytes=10000-10500", 10_000L))
        assertNull(LocalMediaHttpServer.parseRangeHeader("bytes=500-200", 10_000L))
    }

    @Test
    fun `path validator blocks directory traversal attempts`() {
        assertTrue(LocalMediaHttpServer.isSafeRequestPath("/health"))
        assertTrue(LocalMediaHttpServer.isSafeRequestPath("/stream/abcdef0123456789"))
        assertFalse(LocalMediaHttpServer.isSafeRequestPath("/stream/../../etc/passwd"))
        assertFalse(LocalMediaHttpServer.isSafeRequestPath("/stream/%2e%2e/secret"))
        assertFalse(LocalMediaHttpServer.isSafeRequestPath("/etc/hosts"))
    }

    @Test
    fun `server serves GET 200, HEAD 200, 206 Partial Content seeking, 416, and rejects bad tokens`() = runBlocking {
        val port = server.start("127.0.0.1", 0)
        assertTrue(port > 0)
        assertTrue(server.isRunning)

        val health = server.runSelfHealthCheck()
        assertTrue(health.healthy)

        val stream = server.registerMedia(FileMediaSource(sampleFile, "video/mp4"))
        assertEquals(sampleBytes.size.toLong(), stream.contentLength)

        // 1. HEAD request returns Content-Length and Accept-Ranges with empty body
        val headConn = (URL(stream.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
        }
        assertEquals(200, headConn.responseCode)
        assertEquals("video/mp4", headConn.contentType)
        assertEquals("bytes", headConn.getHeaderField("Accept-Ranges"))
        assertEquals(sampleBytes.size.toString(), headConn.getHeaderField("Content-Length"))
        headConn.disconnect()

        // 2. Full GET returns exact 512 KB byte stream
        val fullConn = (URL(stream.url).openConnection() as HttpURLConnection)
        assertEquals(200, fullConn.responseCode)
        val fullReceived = fullConn.inputStream.use { it.readBytes() }
        assertArrayEquals(sampleBytes, fullReceived)
        fullConn.disconnect()

        // 3. Range GET (seeking mid-file) returns 206 Partial Content with exact slice
        val start = 200_000
        val end = 265_535
        val rangeConn = (URL(stream.url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=$start-$end")
        }
        assertEquals(206, rangeConn.responseCode)
        assertEquals(
            "bytes $start-$end/${sampleBytes.size}",
            rangeConn.getHeaderField("Content-Range")
        )
        val sliceReceived = rangeConn.inputStream.use { it.readBytes() }
        assertArrayEquals(sampleBytes.copyOfRange(start, end + 1), sliceReceived)
        rangeConn.disconnect()

        // 4. Out-of-bounds Range returns 416
        val badRangeConn = (URL(stream.url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=9999999-10000000")
        }
        assertEquals(416, badRangeConn.responseCode)
        badRangeConn.disconnect()

        // 5. Unknown token returns 403 Forbidden
        val fakeToken = "0".repeat(48)
        val forbiddenConn = (URL("http://127.0.0.1:$port/stream/$fakeToken").openConnection() as HttpURLConnection)
        assertEquals(403, forbiddenConn.responseCode)
        forbiddenConn.disconnect()

        // 6. Concurrent range reads succeed safely
        val concurrentSlices = listOf(
            0 to 4095,
            100_000 to 132_767,
            400_000 to 524_287
        ).map { (s, e) ->
            async {
                val c = (URL(stream.url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Range", "bytes=$s-$e")
                }
                assertEquals(206, c.responseCode)
                val out = ByteArrayOutputStream()
                c.inputStream.use { it.copyTo(out) }
                c.disconnect()
                out.toByteArray() to sampleBytes.copyOfRange(s, e + 1)
            }
        }.awaitAll()

        concurrentSlices.forEach { (actual, expected) ->
            assertArrayEquals(expected, actual)
        }
    }
}
