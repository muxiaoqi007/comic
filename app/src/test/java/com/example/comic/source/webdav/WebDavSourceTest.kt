package com.example.comic.source.webdav

import com.example.comic.model.Book
import com.example.comic.model.Page
import com.example.comic.model.SourceConfig
import com.example.comic.model.SourceType
import com.example.comic.source.SourceException
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebDavSourceTest {
    private val config = SourceConfig(
        id = "dav",
        type = SourceType.WEBDAV,
        name = "WebDAV",
        baseUrl = "https://dav.example.test/dav/",
        username = "reader",
        password = "secret",
    )
    private val authorization = Credentials.basic("reader", "secret")
    private val foreignHrefs = listOf(
        "https://attacker.test/dav/",
        "//attacker.test/dav/",
        "http://dav.example.test/dav/",
        "https://dav.example.test:444/dav/",
        "https://dav.example.test.attacker.test/dav/",
        "https://dav.example.test@attacker.test/dav/",
        "ftp://dav.example.test/dav/",
    )

    @Test
    fun archiveEntriesWithUrlNamesAreReadOnlyFromZip() = runBlocking {
        val contents = listOf(
            "http://attacker.test/001.jpg",
            "https://attacker.test/002.jpg",
            "https://dav.example.test/003.jpg",
            "//attacker.test/004.jpg",
            "HTTPS://attacker.test/005.jpg",
            "images/006.jpg",
        ).associateWith { "zip content: $it".toByteArray() }
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                contents.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

        for (archive in listOf("book.cbz", "book.ZIP?download=1")) {
            val bookUrl = "https://dav.example.test/dav/$archive".toHttpUrl()
            val requests = mutableListOf<Request>()
            val source = source { request ->
                requests += request
                if (request.url != bookUrl) {
                    response(request, "not zip content".toByteArray())
                } else if (request.method == "HEAD") {
                    response(request).newBuilder()
                        .header("Content-Length", bytes.size.toString()).build()
                } else {
                    val range = checkNotNull(request.header("Range")).removePrefix("bytes=")
                    val start = range.substringBefore('-').toInt()
                    val end = range.substringAfter('-').toInt()
                    response(request, bytes.copyOfRange(start, end + 1), 206).newBuilder()
                        .header("Content-Range", "bytes $start-$end/${bytes.size}").build()
                }
            }
            val book = book(bookUrl.toString()).copy(size = bytes.size.toLong())
            val pages = source.pages(book)
            assertEquals(contents.keys, pages.map { it.key }.toSet())
            pages.forEach { page ->
                source.openPage(page).use { stream ->
                    assertArrayEquals(page.key, contents.getValue(page.key), stream.readBytes())
                }
            }
            assertTrue(requests.any { it.method == "HEAD" })
            assertTrue(requests.any { it.header("Range") != null })
            requests.forEach {
                assertEquals(bookUrl, it.url)
                assertEquals(authorization, it.header("Authorization"))
            }
        }
    }

    @Test
    fun directoryPagesUseSameOriginHttpHrefs() = runBlocking {
        val requests = mutableListOf<Request>()
        val image = "directory image".toByteArray()
        val source = source { request ->
            requests += request
            response(request, image)
        }
        for (href in listOf(
            "https://DAV.EXAMPLE.TEST:443/dav/001.jpg",
            "//dav.example.test/dav/001.jpg",
            "/dav/001.jpg",
        )) {
            source.openPage(page("https://dav.example.test/dav/book/", href)).use {
                assertArrayEquals(image, it.readBytes())
            }
        }
        assertEquals(3, requests.size)
        requests.forEach {
            assertEquals("GET", it.method)
            assertEquals("https://dav.example.test/dav/001.jpg".toHttpUrl(), it.url)
            assertEquals(authorization, it.header("Authorization"))
        }
    }

    @Test
    fun foreignDirectoryPageHrefsAreRejectedBeforeRequest() = runBlocking {
        val requests = mutableListOf<Request>()
        val source = source { request ->
            requests += request
            response(request)
        }
        foreignHrefs.forEach { href ->
            assertRejected {
                source.openPage(page("https://dav.example.test/dav/book/", "${href}001.jpg")).close()
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun foreignBookAndSeriesIdsAreRejectedBeforePropfindHeadOrRange() = runBlocking {
        val requests = mutableListOf<Request>()
        val source = source { request ->
            requests += request
            // Avoid Android XML parsing if an unvalidated PROPFIND reaches the transport.
            response(request, code = 403)
        }
        foreignHrefs.forEach { href ->
            assertRejected { source.books(href) }
            assertRejected { source.pages(book(href)) }
            assertRejected { source.pages(book("${href}book.cbz")) }
            assertRejected { source.pages(book("${href}book.cbz").copy(size = 100)) }
            assertRejected {
                source.openPage(page("${href}book.cbz", "001.jpg")).close()
            }
            assertRejected {
                source.openPage(page(href, "https://dav.example.test/dav/001.jpg")).close()
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun hrefResolutionPreservesDavContextAndCanonicalSameOriginUrls() {
        val base = config.baseUrl!!.toHttpUrl()
        val context = "https://dav.example.test/dav/books/".toHttpUrl()
        val hrefs = mapOf(
            "001.jpg" to "https://dav.example.test/dav/books/001.jpg",
            "../001.jpg" to "https://dav.example.test/dav/001.jpg",
            "/other/001.jpg" to "https://dav.example.test/other/001.jpg",
            "//DAV.EXAMPLE.TEST:443/dav/001.jpg" to "https://dav.example.test/dav/001.jpg",
            "HTTPS://DAV.EXAMPLE.TEST/dav/001.jpg" to "https://dav.example.test/dav/001.jpg",
            "cover%20image.jpg?download=1" to "https://dav.example.test/dav/books/cover%20image.jpg?download=1",
        )
        hrefs.forEach { (href, expected) ->
            assertEquals(href, expected.toHttpUrl(), resolveWebDavHref(base, href, context))
        }
        assertEquals(base.resolve("001.jpg"), resolveWebDavHref(base, "001.jpg"))
    }

    @Test
    fun hrefResolutionRejectsForeignOriginsAndUnsupportedSchemes() {
        val base = config.baseUrl!!.toHttpUrl()
        val context = "https://dav.example.test/dav/books/".toHttpUrl()
        (foreignHrefs + listOf("file:///001.jpg", "data:image/png;base64,AA==", "https://[")).forEach { href ->
            assertNull(href, resolveWebDavHref(base, href))
            assertNull(href, resolveWebDavHref(base, href, context))
        }
        // Resolve relative DAV hrefs against the response context, but trust only the source origin.
        assertNull(resolveWebDavHref(base, "001.jpg", "https://attacker.test/dav/".toHttpUrl()))
    }

    @Test
    fun hrefResolutionUsesEffectivePortIncludingNonDefaultPorts() {
        val httpBase = "http://dav.example.test/dav/".toHttpUrl()
        assertEquals(httpBase, resolveWebDavHref(httpBase, "http://DAV.EXAMPLE.TEST:80/dav/"))
        assertNull(resolveWebDavHref(httpBase, "https://dav.example.test:80/dav/"))
        val customBase = "https://dav.example.test:8443/dav/".toHttpUrl()
        assertEquals(customBase.resolve("001.jpg"), resolveWebDavHref(customBase, "001.jpg"))
        assertEquals(customBase, resolveWebDavHref(customBase, "//dav.example.test:8443/dav/"))
        assertNull(resolveWebDavHref(customBase, "https://dav.example.test/dav/"))
    }

    private fun source(respond: (Request) -> Response): WebDavSource = WebDavSource(
        OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build(),
        config,
    )

    private fun response(request: Request, bytes: ByteArray = byteArrayOf(), code: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(bytes.toResponseBody())
            .build()

    private fun book(id: String) = Book(id, config.id, "series", "book")

    private fun page(bookId: String, key: String) = Page(config.id, bookId, 0, key)

    private suspend fun assertRejected(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected an invalid or cross-origin href to be rejected")
        } catch (_: SourceException) {
            // Expected: validation happens before any authenticated request.
        }
    }
}
