package com.example.comic.data

import android.content.Context
import android.net.Uri
import com.example.comic.model.DownloadRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class DownloadStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "comic_downloads",
    )
    private val indexFile = File(root, "index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<DownloadRecord>> = _items.asStateFlow()

    init {
        synchronized(lock) {
            val valid = _items.value.filter {
                it.contentUri != null || File(it.filePath).length() > 0
            }
            if (valid.size != _items.value.size) {
                _items.value = valid
                persist(valid)
            }
        }
    }

    fun find(sourceId: String, bookId: String): DownloadRecord? =
        _items.value.firstOrNull { it.sourceId == sourceId && it.bookId == bookId }

    fun epubFile(sourceId: String, bookId: String): File =
        File(sourceDir(sourceId), "${safeName(bookId)}.epub")

    fun coverFile(sourceId: String, bookId: String): File =
        File(sourceDir(sourceId), "${safeName(bookId)}.jpg")

    fun bookFile(sourceId: String, bookId: String, extension: String): File =
        File(sourceDir(sourceId), "${safeName(bookId)}.${safeName(extension)}")

    fun add(record: DownloadRecord) {
        synchronized(lock) {
            val updated = listOf(record) + _items.value.filterNot {
                it.sourceId == record.sourceId && it.bookId == record.bookId
            }
            _items.value = updated
            persist(updated)
        }
    }

    fun delete(record: DownloadRecord) {
        synchronized(lock) {
            val updated = _items.value.filterNot {
                it.sourceId == record.sourceId && it.bookId == record.bookId
            }
            _items.value = updated
            persist(updated)
            if (record.contentUri != null) {
                runCatching {
                    appContext.contentResolver.delete(Uri.parse(record.contentUri), null, null)
                }
            } else {
                runCatching { File(record.filePath).delete() }
            }
            record.coverPath?.let { runCatching { File(it).delete() } }
        }
    }

    private fun sourceDir(sourceId: String): File =
        File(root, safeName(sourceId)).apply { mkdirs() }

    private fun load(): List<DownloadRecord> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<DownloadRecord>>(indexFile.readText())
        }.getOrDefault(emptyList())
    }

    private fun persist(items: List<DownloadRecord>) {
        runCatching {
            root.mkdirs()
            val temp = File(root, "index.json.tmp")
            temp.writeText(json.encodeToString(items))
            if (!temp.renameTo(indexFile)) {
                temp.copyTo(indexFile, overwrite = true)
                temp.delete()
            }
        }
    }

    private fun safeName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "item" }
}
