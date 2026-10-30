package com.example.comic.source.zlibrary

import com.example.comic.model.SourceConfig
import com.example.comic.model.SourceType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ZlibrarySourceTest {

    private lateinit var server: FakeServer
    private lateinit var source: ZlibrarySource
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = FakeServer { method, path, body ->
            when {
                path == "/eapi/user/login" -> {
                    val form = form(body)
                    if (form["email"] == "reader@example.com" && form["password"] == "secret") {
                        json("""{"success":1,"user":{"id":"7","remix_userkey":"key"}}""")
                    } else {
                        json("""{"success":0,"error":"邮箱或密码错误"}""")
                    }
                }

                path == "/eapi/user/profile" ->
                    json("""{"success":1,"user":{"downloads_today":2,"downloads_limit":10}}""")

                path == "/eapi/book/search" ->
                    json(
                        """
                        {"success":1,"books":[{"id":"1","hash":"abc123","title":"Test Book",
                        "author":"Author","extension":"epub","filesize":1234,
                        "year":"2026","language":"English"}],
                        "pagination":{"current":1,"total_pages":1}}
                        """.trimIndent(),
                    )

                path == "/eapi/book/1/abc123/file" ->
                    json(
                        """{"success":1,"file":{"allowDownload":1,
                        "downloadLink":"${server.base}/files/1.epub",
                        "description":"Test Book","author":"Author","extension":"epub"}}""".trimIndent(),
                    )

                path == "/files/1.epub" -> "epub-bytes" to "application/epub+zip"
                else -> """{"success":0,"error":"not found"}""" to "application/json"
            }
        }
        tempDir = Files.createTempDirectory("zlibrary-test").toFile()
        source = ZlibrarySource(
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
            config = SourceConfig(
                id = "zl",
                type = SourceType.ZLIBRARY,
                name = "Z-Library",
                baseUrl = server.base,
                username = "reader@example.com",
                password = "secret",
            ),
        )
    }

    @After
    fun tearDown() {
        server.stop()
        tempDir.deleteRecursively()
    }

    @Test
    fun connectsSearchesAndDownloads() = runBlocking {
        val profile = source.connect()
        assertEquals(2, profile?.downloadsToday)
        assertEquals(10, profile?.downloadsLimit)

        val page = source.search("Test")
        assertEquals(1, page.books.size)
        val book = page.books.single()
        assertEquals("Test Book", book.title)
        assertEquals("abc123", book.hash)

        val target = File(tempDir, "book.part")
        val download = source.download(book, target) { _, _ -> }

        assertEquals("Test Book - Author.epub", download.fileName)
        assertEquals("application/epub+zip", download.mimeType)
        assertEquals("epub-bytes", target.readText())
        assertTrue(download.sizeBytes > 0)
    }

    private fun form(body: String): Map<String, String> =
        body.split("&").filter { it.isNotBlank() }.associate { pair ->
            val parts = pair.split("=", limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to
                URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }

    private fun json(body: String): Pair<String, String> = body to "application/json"

    private class FakeServer(
        private val handler: (method: String, path: String, body: String) -> Pair<String, String>,
    ) {
        private val socket = ServerSocket(0)
        val base: String = "http://127.0.0.1:${socket.localPort}"
        private val worker = thread(isDaemon = true, name = "zlibrary-test-server") {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        val input = client.getInputStream().bufferedReader()
                        val requestLine = input.readLine() ?: return@use
                        val parts = requestLine.split(" ")
                        val method = parts.getOrElse(0) { "GET" }
                        val path = parts.getOrElse(1) { "/" }.substringBefore('?')
                        var contentLength = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                            }
                        }
                        val body = if (contentLength > 0) {
                            val chars = CharArray(contentLength)
                            var read = 0
                            while (read < contentLength) {
                                val count = input.read(chars, read, contentLength - read)
                                if (count < 0) break
                                read += count
                            }
                            String(chars, 0, read)
                        } else {
                            ""
                        }
                        val (responseBody, contentType) = handler(method, path, body)
                        val bytes = responseBody.toByteArray()
                        val output = client.getOutputStream()
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: $contentType\r\n" +
                                    "Content-Length: ${bytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(),
                        )
                        output.write(bytes)
                        output.flush()
                    }
                } catch (_: Exception) {
                    if (socket.isClosed) return@thread
                }
            }
        }

        fun stop() {
            runCatching { socket.close() }
        }
    }
}
