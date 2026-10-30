package com.example.comic.data

import android.content.Context
import com.example.comic.model.DownloadRecord
import com.example.comic.model.SourceType
import com.example.comic.source.zlibrary.ZlibraryBook
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

class ZlibraryDownloadManager(
    private val repository: SourceRepository,
    private val store: DownloadStore,
    private val appContext: Context,
) {
    data class Active(
        val key: String,
        val sourceId: String,
        val book: ZlibraryBook,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _active = MutableStateFlow<Map<String, Active>>(emptyMap())
    val active: StateFlow<Map<String, Active>> = _active.asStateFlow()

    fun download(sourceId: String, book: ZlibraryBook) {
        if (store.find(sourceId, book.id) != null) return
        val key = key(sourceId, book.id)
        if (jobs[key]?.isActive == true) return
        _active.update { it + (key to Active(key = key, sourceId = sourceId, book = book)) }
        jobs[key] = scope.launch {
            var partial: File? = null
            try {
                val source = repository.zlibrarySource(sourceId)
                val dir = File(appContext.cacheDir, "zlibrary").apply { mkdirs() }
                val temp = File(dir, "${book.id}.part").apply { delete() }
                partial = temp
                val result = repository.zlibraryDownload(sourceId, book, temp) { done, total ->
                    _active.update { current ->
                        val entry = current[key] ?: return@update current
                        current + (key to entry.copy(
                            downloadedBytes = done,
                            totalBytes = total,
                        ))
                    }
                }

                val coverFile = store.coverFile(sourceId, book.id)
                val hasCover = book.coverUrl?.let { url ->
                    val bytes = repository.zlibraryCover(sourceId, url)
                    if (bytes == null) {
                        false
                    } else {
                        coverFile.parentFile?.mkdirs()
                        runCatching { coverFile.writeBytes(bytes) }.isSuccess
                    }
                } ?: false

                val publicUri = runCatching {
                    PublicDownloads.publish(appContext, temp, result.fileName, result.mimeType)
                }.getOrNull()

                val storedPath: String
                if (publicUri != null) {
                    temp.delete()
                    storedPath = ""
                } else {
                    val target = store.bookFile(sourceId, book.id, result.extension)
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    storedPath = target.absolutePath
                }
                partial = null

                store.add(
                    DownloadRecord(
                        sourceId = sourceId,
                        sourceType = SourceType.ZLIBRARY,
                        bookId = book.id,
                        seriesId = "zlibrary",
                        title = book.title,
                        seriesTitle = book.author,
                        author = book.author,
                        pageCount = null,
                        sizeBytes = result.sizeBytes,
                        filePath = storedPath,
                        contentUri = publicUri,
                        mimeType = result.mimeType,
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

    fun retry(item: Active) {
        _active.update { it - item.key }
        download(item.sourceId, item.book)
    }

    fun cancel(key: String) {
        jobs[key]?.cancel()
        jobs.remove(key)
        _active.update { it - key }
    }

    private fun key(sourceId: String, bookId: String): String = "$sourceId|$bookId"
}
