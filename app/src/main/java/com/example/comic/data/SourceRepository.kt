package com.example.comic.data

import android.content.Context
import com.example.comic.model.Book
import com.example.comic.model.Page
import com.example.comic.model.ReadingProgress
import com.example.comic.model.Series
import com.example.comic.model.SourceConfig
import com.example.comic.model.SourceType
import com.example.comic.source.ComicSource
import com.example.comic.source.DownloadableSource
import com.example.comic.source.SourceException
import com.example.comic.source.komga.KomgaSource
import com.example.comic.source.koobone.KooboneSource
import com.example.comic.source.local.LocalSource
import com.example.comic.source.webdav.WebDavSource
import com.example.comic.source.zlibrary.ZlibraryBook
import com.example.comic.source.zlibrary.ZlibraryDownload
import com.example.comic.source.zlibrary.ZlibraryProfile
import com.example.comic.source.zlibrary.ZlibrarySearchPage
import com.example.comic.source.zlibrary.ZlibrarySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

class SourceRepository(
    private val context: Context,
    private val client: OkHttpClient,
    private val sourceStore: SourceStore,
    private val progressStore: ProgressStore,
    private val downloadStore: DownloadStore,
    private val sourceTestStore: SourceTestStore,
    private val shelfCacheStore: ShelfCacheStore,
) {
    private val cache = ConcurrentHashMap<String, ComicSource>()

    val sources: StateFlow<List<SourceConfig>> = sourceStore.sources
    val progress: StateFlow<Map<String, ReadingProgress>> = progressStore.progress

    fun source(config: SourceConfig): ComicSource = cache.getOrPut(config.id) {
        when (config.type) {
            SourceType.LOCAL -> LocalSource(context, config)
            SourceType.WEBDAV -> WebDavSource(client, config, context)
            SourceType.KOMGA -> KomgaSource(client, config, context)
            SourceType.KOOBONE -> KooboneSource(context, client, config, downloadStore)
            SourceType.ZLIBRARY -> ZlibrarySource(client, config)
        }
    }

    fun source(sourceId: String): ComicSource {
        val config = sourceStore.get(sourceId) ?: throw SourceException("漫画源不存在")
        return source(config)
    }

    fun saveSource(config: SourceConfig) {
        sourceStore.save(config)
        sourceTestStore.remove(config.id)
        shelfCacheStore.remove(config.id)
        invalidate(config.id)
    }

    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        val config = sourceStore.get(sourceId) ?: return
        if (config.enabled == enabled) return
        sourceStore.save(config.copy(enabled = enabled))
        invalidate(sourceId)
    }

    fun deleteSource(sourceId: String) {
        sourceStore.delete(sourceId)
        sourceTestStore.remove(sourceId)
        shelfCacheStore.remove(sourceId)
        invalidate(sourceId)
    }

    fun invalidate(sourceId: String) {
        cache.remove(sourceId)?.runCatching { close() }
    }

    suspend fun testConnection(config: SourceConfig) = withContext(Dispatchers.IO) {
        source(config).testConnection()
    }

    suspend fun testConnectionTransient(config: SourceConfig) = withContext(Dispatchers.IO) {
        val probe = config.copy(id = "probe-${System.nanoTime()}")
        try {
            source(probe).testConnection()
        } finally {
            invalidate(probe.id)
        }
    }

    suspend fun seriesForSource(config: SourceConfig): List<Series> = withContext(Dispatchers.IO) {
        val source = source(config)
        val libraries = source.libraries()
        val result = mutableListOf<Series>()
        var failure: Throwable? = null
        for (library in libraries) {
            try {
                result += source.series(library.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
        }
        if (result.isEmpty() && failure != null) throw failure
        result
    }

    suspend fun books(series: Series): List<Book> = withContext(Dispatchers.IO) {
        source(series.sourceId).books(series.id)
    }

    suspend fun nextBook(book: Book): Book? = withContext(Dispatchers.IO) {
        val seriesId = resolveSeriesId(book) ?: book.seriesId
        if (seriesId.isBlank()) return@withContext null
        val siblings = source(book.sourceId).books(seriesId)
        val index = siblings.indexOfFirst { it.id == book.id }
        if (index < 0) null else siblings.getOrNull(index + 1)
    }

    suspend fun pages(book: Book): List<Page> = withContext(Dispatchers.IO) {
        source(book.sourceId).pages(book)
    }

    suspend fun openPageBytes(page: Page): ByteArray = withContext(Dispatchers.IO) {
        source(page.sourceId).openPage(page).use { it.readBytes() }
    }

    suspend fun openCoverBytes(series: Series): ByteArray? = withContext(Dispatchers.IO) {
        if (sourceStore.get(series.sourceId)?.enabled == false) return@withContext null
        source(series.sourceId).openCover(series)?.use { it.readBytes() }
    }

    fun sourceType(sourceId: String): SourceType? = sourceStore.get(sourceId)?.type

    suspend fun fetchBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? =
        withContext(Dispatchers.IO) {
            val builder = okhttp3.Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.header(name, value) }
            runCatching {
                client.newCall(builder.get().build()).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes() else null
                }
            }.getOrNull()
        }

    fun zlibrarySource(sourceId: String): ZlibrarySource =
        source(sourceId) as? ZlibrarySource
            ?: throw SourceException("该源不是 Z-Library 源")

    suspend fun zlibraryConnect(sourceId: String): ZlibraryProfile? =
        withContext(Dispatchers.IO) { zlibrarySource(sourceId).connect() }

    suspend fun zlibraryProfile(sourceId: String): ZlibraryProfile? =
        withContext(Dispatchers.IO) { zlibrarySource(sourceId).profile() }

    suspend fun zlibrarySearch(
        sourceId: String,
        query: String,
        page: Int,
        extensions: List<String>,
    ): ZlibrarySearchPage = withContext(Dispatchers.IO) {
        zlibrarySource(sourceId).search(query, page, extensions)
    }

    suspend fun zlibraryDownload(
        sourceId: String,
        book: ZlibraryBook,
        targetFile: java.io.File,
        onProgress: (Long, Long) -> Unit,
    ): ZlibraryDownload = withContext(Dispatchers.IO) {
        zlibrarySource(sourceId).download(book, targetFile, onProgress)
    }

    suspend fun zlibraryCover(sourceId: String, url: String): ByteArray? =
        withContext(Dispatchers.IO) { zlibrarySource(sourceId).coverBytes(url) }

    fun coverCacheToken(series: Series): String {
        val config = sourceStore.get(series.sourceId) ?: return ""
        return listOf(
            config.type,
            config.treeUri,
            config.baseUrl,
            config.rootPath,
            config.username,
        ).joinToString("|")
    }

    fun saveProgressImmediate(book: Book, page: Int, totalPages: Int, completed: Boolean) {
        progressStore.save(
            ReadingProgress(
                sourceId = book.sourceId,
                bookId = book.id,
                page = page,
                totalPages = totalPages,
                updatedAt = System.currentTimeMillis(),
                completed = completed,
                seriesId = resolveSeriesId(book) ?: book.seriesId,
                bookTitle = book.title,
            ),
        )
    }

    fun resolveSeriesId(book: Book): String? =
        runCatching { source(book.sourceId).resolveSeriesId(book.id) }.getOrNull()

    /** Repairs progress saved before series grouping changed, without touching the page. */
    suspend fun repairProgressSeries(book: Book): ReadingProgress? = withContext(Dispatchers.IO) {
        val existing = progressStore.get(book.sourceId, book.id) ?: return@withContext null
        val seriesId = resolveSeriesId(book) ?: return@withContext existing
        if (existing.seriesId == seriesId) return@withContext existing
        val updated = existing.copy(seriesId = seriesId)
        progressStore.save(updated)
        updated
    }

    suspend fun saveProgress(book: Book, page: Int, totalPages: Int, completed: Boolean) {
        val entry = ReadingProgress(
            sourceId = book.sourceId,
            bookId = book.id,
            page = page,
            totalPages = totalPages,
            updatedAt = System.currentTimeMillis(),
            completed = completed,
            seriesId = resolveSeriesId(book) ?: book.seriesId,
            bookTitle = book.title,
        )
        progressStore.save(entry)
        runCatching {
            withContext(Dispatchers.IO) {
                source(book.sourceId).updateProgress(book, page, totalPages, completed)
            }
        }
    }

    fun supportsDownload(sourceId: String): Boolean =
        runCatching { source(sourceId) is DownloadableSource }.getOrDefault(false)

    fun progressFor(book: Book): ReadingProgress? =
        progressStore.get(book.sourceId, book.id)

    fun close() {
        cache.values.forEach { runCatching { it.close() } }
        cache.clear()
    }
}
