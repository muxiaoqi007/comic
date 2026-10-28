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
) {
    private val cache = ConcurrentHashMap<String, ComicSource>()

    val sources: StateFlow<List<SourceConfig>> = sourceStore.sources
    val progress: StateFlow<Map<String, ReadingProgress>> = progressStore.progress

    fun source(config: SourceConfig): ComicSource = cache.getOrPut(config.id) {
        when (config.type) {
            SourceType.LOCAL -> LocalSource(context, config)
            SourceType.WEBDAV -> WebDavSource(client, config)
            SourceType.KOMGA -> KomgaSource(client, config)
            SourceType.KOOBONE -> KooboneSource(context, client, config, downloadStore)
        }
    }

    fun source(sourceId: String): ComicSource {
        val config = sourceStore.get(sourceId) ?: throw SourceException("漫画源不存在")
        return source(config)
    }

    fun saveSource(config: SourceConfig) {
        sourceStore.save(config)
        invalidate(config.id)
    }

    fun deleteSource(sourceId: String) {
        sourceStore.delete(sourceId)
        invalidate(sourceId)
    }

    fun invalidate(sourceId: String) {
        cache.remove(sourceId)?.runCatching { close() }
    }

    suspend fun testConnection(config: SourceConfig) = withContext(Dispatchers.IO) {
        source(config).testConnection()
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

    suspend fun pages(book: Book): List<Page> = withContext(Dispatchers.IO) {
        source(book.sourceId).pages(book)
    }

    suspend fun openPageBytes(page: Page): ByteArray = withContext(Dispatchers.IO) {
        source(page.sourceId).openPage(page).use { it.readBytes() }
    }

    suspend fun openCoverBytes(series: Series): ByteArray? = withContext(Dispatchers.IO) {
        source(series.sourceId).openCover(series)?.use { it.readBytes() }
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
                seriesId = book.seriesId,
                bookTitle = book.title,
            ),
        )
    }

    suspend fun saveProgress(book: Book, page: Int, totalPages: Int, completed: Boolean) {
        val entry = ReadingProgress(
            sourceId = book.sourceId,
            bookId = book.id,
            page = page,
            totalPages = totalPages,
            updatedAt = System.currentTimeMillis(),
            completed = completed,
            seriesId = book.seriesId,
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
