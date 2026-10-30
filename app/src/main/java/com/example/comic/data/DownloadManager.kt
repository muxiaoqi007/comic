package com.example.comic.data

import com.example.comic.model.Book
import com.example.comic.model.DownloadRecord
import com.example.comic.model.storageKey
import com.example.comic.source.DownloadableSource
import com.example.comic.source.SourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DownloadManager(
    private val repository: SourceRepository,
    private val store: DownloadStore,
    private val appContext: android.content.Context,
) {
    data class ActiveDownload(
        val key: String,
        val sourceId: String,
        val bookId: String,
        val title: String,
        val seriesTitle: String? = null,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val error: String? = null,
        val book: Book? = null,
        val author: String? = null,
    )

    val downloads: StateFlow<List<DownloadRecord>> = store.items

    private val _active = MutableStateFlow<Map<String, ActiveDownload>>(emptyMap())
    val active: StateFlow<Map<String, ActiveDownload>> = _active.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    fun supports(sourceId: String): Boolean =
        runCatching { repository.source(sourceId) is DownloadableSource }.getOrDefault(false)

    fun isDownloaded(sourceId: String, bookId: String): Boolean =
        store.find(sourceId, bookId) != null

    fun download(book: Book, seriesTitle: String?, author: String?) {
        val key = book.storageKey
        if (isDownloaded(book.sourceId, book.id)) return
        if (jobs[key]?.isActive == true) return

        _active.update {
            it + (key to ActiveDownload(
                key = key,
                sourceId = book.sourceId,
                bookId = book.id,
                title = book.title,
                seriesTitle = seriesTitle,
                book = book,
                author = author,
            ))
        }

        jobs[key] = scope.launch {
            var partial: File? = null
            try {
                val source = repository.source(book.sourceId) as? DownloadableSource
                    ?: throw SourceException("该源不支持下载")

                val epub = store.epubFile(book.sourceId, book.id)
                epub.parentFile?.mkdirs()
                val temp = File(epub.parentFile, epub.name + ".part")
                partial = temp
                temp.delete()

                val result = source.downloadBook(book, temp) { done, total ->
                    _active.update { current ->
                        val entry = current[key] ?: return@update current
                        current + (key to entry.copy(
                            downloadedBytes = done,
                            totalBytes = total,
                        ))
                    }
                }
                val publicUri = runCatching {
                    PublicDownloads.publish(
                        appContext,
                        temp,
                        exportFileName(book.title),
                        "application/epub+zip",
                    )
                }.getOrNull()

                val storedPath: String
                if (publicUri != null) {
                    temp.delete()
                    storedPath = ""
                } else {
                    if (!temp.renameTo(epub)) {
                        temp.copyTo(epub, overwrite = true)
                        temp.delete()
                    }
                    storedPath = epub.absolutePath
                }
                partial = null

                val coverFile = store.coverFile(book.sourceId, book.id)
                val hasCover = runCatching {
                    source.downloadCover(book, coverFile)
                }.getOrDefault(false)

                store.add(
                    DownloadRecord(
                        sourceId = book.sourceId,
                        sourceType = source.type,
                        bookId = book.id,
                        seriesId = book.seriesId,
                        title = book.title,
                        seriesTitle = seriesTitle,
                        author = author,
                        pageCount = result.pageCount.takeIf { it > 0 },
                        sizeBytes = result.sizeBytes,
                        filePath = storedPath,
                        contentUri = publicUri,
                        mimeType = "application/epub+zip",
                        coverPath = coverFile
                            .takeIf { hasCover && it.length() > 0 }
                            ?.absolutePath,
                        downloadedAt = System.currentTimeMillis(),
                    ),
                )
                _active.update { it - key }
            } catch (e: CancellationException) {
                partial?.delete()
                _active.update { it - key }
                throw e
            } catch (e: Exception) {
                partial?.delete()
                _active.update { current ->
                    val entry = current[key] ?: return@update current
                    current + (key to entry.copy(error = e.message ?: "下载失败"))
                }
            } finally {
                jobs.remove(key)
            }
        }
    }

    private fun exportFileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "comic" }.take(80) + ".epub"

    fun retry(item: ActiveDownload) {
        val book = item.book ?: return
        download(book, item.seriesTitle, item.author)
    }

    fun cancel(key: String) {
        jobs[key]?.cancel()
        jobs.remove(key)
        _active.update { it - key }
    }

    fun delete(record: DownloadRecord) {
        store.delete(record)
    }
}
