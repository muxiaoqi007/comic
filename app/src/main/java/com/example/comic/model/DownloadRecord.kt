package com.example.comic.model

import kotlinx.serialization.Serializable

@Serializable
data class DownloadRecord(
    val sourceId: String,
    val sourceType: SourceType,
    val bookId: String,
    val seriesId: String,
    val title: String,
    val seriesTitle: String? = null,
    val author: String? = null,
    val pageCount: Int? = null,
    val sizeBytes: Long = 0,
    val filePath: String,
    val coverPath: String? = null,
    val downloadedAt: Long,
)
