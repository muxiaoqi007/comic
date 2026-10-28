package com.example.comic.model

import kotlinx.serialization.Serializable

@Serializable
enum class SourceType {
    LOCAL,
    WEBDAV,
    KOMGA,
    KOOBONE,
}

@Serializable
data class SourceConfig(
    val id: String,
    val type: SourceType,
    val name: String,
    val treeUri: String? = null,
    val baseUrl: String? = null,
    val rootPath: String = "",
    val username: String? = null,
    val password: String? = null,
    val apiKey: String? = null,
)

data class Library(
    val id: String,
    val sourceId: String,
    val name: String,
)

data class Series(
    val id: String,
    val sourceId: String,
    val libraryId: String,
    val title: String,
    val bookCount: Int? = null,
    val description: String? = null,
)

data class Book(
    val id: String,
    val sourceId: String,
    val seriesId: String,
    val title: String,
    val number: String? = null,
    val pageCount: Int? = null,
    val size: Long? = null,
    val updatedAt: Long? = null,
)

data class Page(
    val sourceId: String,
    val bookId: String,
    val index: Int,
    val key: String,
    val mimeType: String? = null,
)

@Serializable
data class ReadingProgress(
    val sourceId: String,
    val bookId: String,
    val page: Int,
    val totalPages: Int,
    val updatedAt: Long,
    val completed: Boolean = false,
    val seriesId: String? = null,
    val bookTitle: String? = null,
)

val ReadingProgress.storageKey: String
    get() = "$sourceId|$bookId"

val Book.storageKey: String
    get() = "$sourceId|$id"
