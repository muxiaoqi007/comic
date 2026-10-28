package com.example.comic.source

import com.example.comic.model.Book
import java.io.File

data class DownloadResult(
    val sizeBytes: Long,
    val pageCount: Int,
)

interface DownloadableSource : ComicSource {
    suspend fun downloadBook(
        book: Book,
        targetFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadResult

    suspend fun downloadCover(seriesId: String, targetFile: File): Boolean
}
