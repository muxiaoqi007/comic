package com.example.comic.source.webdav

import android.content.Context
import android.util.Xml
import com.example.comic.model.Book
import com.example.comic.model.Library
import com.example.comic.model.Page
import com.example.comic.model.Series
import com.example.comic.model.SourceConfig
import com.example.comic.source.ComicSource
import com.example.comic.source.SourceException
import com.example.comic.source.remote.AdaptiveBaseUrl
import com.example.comic.source.remote.appendPath
import com.example.comic.source.remote.authorize
import com.example.comic.source.remote.connectivityFailure
import com.example.comic.source.remote.execute
import com.example.comic.source.remote.normalizeBaseUrl
import com.example.comic.util.isArchiveName
import com.example.comic.util.isImageName
import com.example.comic.util.mimeForName
import com.example.comic.util.naturalCompare
import com.example.comic.util.titleWithoutExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.compress.archivers.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel

class WebDavSource(
    private val client: OkHttpClient,
    override val config: SourceConfig,
    context: Context? = null,
) : ComicSource {
    private val localRoot = normalizeBaseUrl(config.baseUrl).appendPath(config.rootPath)
    private val publicRoot = config.publicBaseUrl
        ?.takeIf { it.isNotBlank() }
        ?.let { normalizeBaseUrl(it).appendPath(config.rootPath) }
    private val adaptive = AdaptiveBaseUrl(
        context = context,
        client = client,
        local = localRoot,
        public = publicRoot,
        authorize = { builder -> builder.authorize(config) },
    )
    private var base: HttpUrl = localRoot
    private var root: HttpUrl = localRoot

    private suspend fun ensureBase() {
        base = adaptive.resolve()
        root = base
    }

    /**
     * Runs [block] on the chosen base; on a transport failure the selector is
     * told, the other address is picked, and the request runs once more.
     */
    private suspend fun <T> withFailover(block: suspend () -> T): T {
        ensureBase()
        return try {
            block()
        } catch (e: IOException) {
            if (e is AdaptiveBaseUrl.ConnectivityFailure) throw e
            val retryBase = adaptive.resolve(connectivityFailure(base, e))
            base = retryBase
            root = retryBase
            adaptive.succeed(retryBase)
            block()
        }
    }

    override suspend fun testConnection() {
        ensureBase()
        withContext(Dispatchers.IO) { withFailover { propfind(root, depth = "0") } }
    }

    override suspend fun libraries(): List<Library> = withContext(Dispatchers.IO) {
        listOf(Library(id = ROOT_LIBRARY_ID, sourceId = id, name = config.name))
    }

    override suspend fun series(libraryId: String): List<Series> = withContext(Dispatchers.IO) {
        withFailover {
            val entries = propfind(root, depth = "1").filterNot { it.isSelf(root) }
            val directories = entries
                .filter { it.isCollection }
                .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                .map { entry ->
                    Series(
                        id = entry.href,
                        sourceId = id,
                        libraryId = libraryId,
                        title = entry.name,
                    )
                }

            val looseArchives = entries.count { !it.isCollection && isArchiveName(it.name) }
            if (looseArchives > 0) {
                directories + Series(
                    id = SINGLES_ID,
                    sourceId = id,
                    libraryId = libraryId,
                    title = "单本",
                    bookCount = looseArchives,
                )
            } else {
                directories
            }
        }
    }

    override suspend fun books(seriesId: String): List<Book> = withContext(Dispatchers.IO) {
        withFailover {
        val directory = if (seriesId == SINGLES_ID) root else parseHref(seriesId)
        val entries = propfind(directory, depth = "1").filterNot { it.isSelf(directory) }
        val books = entries.mapNotNull { entry ->
            when {
                !entry.isCollection && isArchiveName(entry.name) -> Book(
                    id = entry.href,
                    sourceId = id,
                    seriesId = seriesId,
                    title = titleWithoutExtension(entry.name),
                    size = entry.size,
                    updatedAt = entry.lastModified,
                )

                entry.isCollection -> Book(
                    id = entry.href,
                    sourceId = id,
                    seriesId = seriesId,
                    title = entry.name,
                )

                else -> null
            }
        }
        books.sortedWith { a, b -> naturalCompare(a.title, b.title) }
        }
    }

    override suspend fun pages(book: Book): List<Page> = withContext(Dispatchers.IO) {
        withFailover {
        val url = parseHref(book.id)
        if (isArchiveName(url.encodedPath)) {
            withZip(book) { zip ->
                zip.entries.toList()
                    .filter { !it.isDirectory && isImageName(it.name) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                    .mapIndexed { index, entry ->
                        Page(
                            sourceId = id,
                            bookId = book.id,
                            index = index,
                            key = entry.name,
                            mimeType = mimeForName(entry.name),
                        )
                    }
            }
        } else {
            propfind(url, depth = "1")
                .filterNot { it.isSelf(url) }
                .filter { !it.isCollection && isImageName(it.name) }
                .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                .mapIndexed { index, entry ->
                    Page(
                        sourceId = id,
                        bookId = book.id,
                        index = index,
                        key = entry.href,
                        mimeType = mimeForName(entry.name),
                    )
                }
        }
        }
    }

    override suspend fun openPage(page: Page): InputStream = withContext(Dispatchers.IO) {
        withFailover {
        val bookUrl = parseHref(page.bookId)
        if (!isArchiveName(bookUrl.encodedPath)) {
            val request = Request.Builder().url(parseHref(page.key)).get()
            request.authorize(config)
            val response = client.execute(request.build())
            if (!response.isSuccessful) {
                response.close()
                throw SourceException("加载页面失败：HTTP ${response.code}")
            }
            return@withFailover response.body?.byteStream() ?: run {
                response.close()
                throw SourceException("页面内容为空")
            }
        }
        val book = Book(
            id = page.bookId,
            sourceId = id,
            seriesId = "",
            title = "",
        )
        val bytes = withZip(book) { zip ->
            val entry = zip.getEntry(page.key)
                ?: throw SourceException("压缩包中找不到页面：${page.key}")
            zip.getInputStream(entry).use { it.readBytes() }
        }
        ByteArrayInputStream(bytes)
        }
    }

    override suspend fun openCover(series: Series): InputStream? = withContext(Dispatchers.IO) {
        val firstBook = books(series.id).firstOrNull() ?: return@withContext null
        val firstPage = pages(firstBook).firstOrNull() ?: return@withContext null
        openPage(firstPage)
    }

    private fun propfind(url: HttpUrl, depth: String): List<DavEntry> {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", depth)
            .header("Accept", "application/xml")
        request.authorize(config)
        val response = client.execute(request.build())
        response.use {
            if (!it.isSuccessful) throw SourceException("WebDAV 返回 HTTP ${it.code}")
            val stream = it.body?.byteStream() ?: throw SourceException("WebDAV 返回内容为空")
            return parseMultistatus(stream, url)
        }
    }

    private fun parseMultistatus(stream: InputStream, context: HttpUrl): List<DavEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(stream, null)

        val entries = mutableListOf<DavEntry>()
        var inResponse = false
        var href: String? = null
        var displayName: String? = null
        var size: Long? = null
        var lastModified: Long? = null
        var isCollection = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "response" -> {
                        inResponse = true
                        href = null
                        displayName = null
                        size = null
                        lastModified = null
                        isCollection = false
                    }

                    "href" -> if (inResponse) href = parser.nextText().trim()
                    "displayname" -> if (inResponse) displayName = parser.nextText().trim()
                    "getcontentlength" -> if (inResponse) size = parser.nextText().trim().toLongOrNull()
                    "getlastmodified" -> if (inResponse) lastModified = parseHttpDate(parser.nextText().trim())
                    "collection" -> if (inResponse) isCollection = true
                }

                XmlPullParser.END_TAG -> if (parser.name.equals("response", ignoreCase = true)) {
                    val rawHref = href
                    if (!rawHref.isNullOrBlank()) {
                        val resolved = resolveWebDavHref(base, rawHref, context)
                        if (resolved != null) {
                            val fallbackName = resolved.pathSegments.lastOrNull { it.isNotBlank() }.orEmpty()
                            entries += DavEntry(
                                href = resolved.toString(),
                                name = displayName?.takeIf { it.isNotBlank() } ?: fallbackName,
                                isCollection = isCollection,
                                size = size,
                                lastModified = lastModified,
                            )
                        }
                    }
                    inResponse = false
                }
            }
            event = parser.next()
        }
        return entries
    }

    private fun <T> withZip(book: Book, block: (ZipFile) -> T): T {
        val url = parseHref(book.id)
        val size = book.size?.takeIf { it > 0 } ?: remoteSize(url)
        val channel = HttpRangeChannel(client, url, size) { builder -> builder.authorize(config) }
        channel.use {
            ZipFile(it).use { zip -> return block(zip) }
        }
    }

    private fun remoteSize(url: HttpUrl): Long {
        val head = Request.Builder().url(url).head()
        head.authorize(config)
        runCatching {
            client.execute(head.build()).use { response ->
                if (response.isSuccessful) {
                    response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
                }
            }
        }

        val range = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .get()
        range.authorize(config)
        client.execute(range.build()).use { response ->
            val total = response.header("Content-Range")
                ?.substringAfterLast('/', "")
                ?.toLongOrNull()
            if (total != null && total > 0) return total
            if (response.isSuccessful) {
                response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
            }
        }
        throw SourceException("无法获取远端文件大小")
    }

    private fun parseHref(value: String): HttpUrl =
        resolveWebDavHref(base, value)
            ?: throw SourceException("无效或跨源的远端地址：$value")

    private fun DavEntry.isSelf(url: HttpUrl): Boolean = href.trimEnd('/') == url.toString().trimEnd('/')

    private class DavEntry(
        val href: String,
        val name: String,
        val isCollection: Boolean,
        val size: Long?,
        val lastModified: Long?,
    )

    private companion object {
        const val ROOT_LIBRARY_ID = "root"
        const val SINGLES_ID = "webdav:singles"
        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
              </d:prop>
            </d:propfind>"""
    }
}

// HttpUrl canonicalizes schemes, hosts and effective ports before comparison.
internal fun resolveWebDavHref(base: HttpUrl, value: String, context: HttpUrl = base): HttpUrl? =
    context.resolve(value)?.takeIf {
        it.scheme == base.scheme && it.host == base.host && it.port == base.port
    }

private fun parseHttpDate(value: String): Long? {
    val formats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy",
    )
    formats.forEach { pattern ->
        runCatching {
            return java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
                .parse(value)
                ?.time
        }.getOrNull()?.let { return it }
    }
    return null
}

private class HttpRangeChannel(
    private val client: OkHttpClient,
    private val url: HttpUrl,
    private val size: Long,
    private val authorize: (Request.Builder) -> Request.Builder,
) : SeekableByteChannel {
    private var position = 0L
    private var open = true

    override fun isOpen(): Boolean = open

    override fun size(): Long = size

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel {
        if (newPosition < 0) throw IllegalArgumentException("position < 0")
        position = newPosition
        return this
    }

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        if (position >= size) return -1
        if (!dst.hasRemaining()) return 0

        val wanted = minOf(dst.remaining().toLong(), size - position)
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=$position-${position + wanted - 1}")
            .header("Accept-Encoding", "identity")
            .get()
        authorize(builder)
        val response = client.execute(builder.build())
        response.use {
            if (!it.isSuccessful) throw SourceException("读取远端文件失败：HTTP ${it.code}")
            val stream = it.body?.byteStream() ?: throw SourceException("读取远端文件失败")

            if (it.code != 206) {
                var skipped = 0L
                while (skipped < position) {
                    val amount = stream.skip(position - skipped)
                    if (amount > 0) {
                        skipped += amount
                    } else {
                        if (stream.read() < 0) return -1
                        skipped++
                    }
                }
            }

            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (total < wanted) {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), wanted - total).toInt())
                if (read < 0) break
                dst.put(buffer, 0, read)
                total += read
            }
            position += total
            return if (total == 0) -1 else total
        }
    }

    override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException("read only")

    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException("read only")

    override fun close() {
        open = false
    }
}
