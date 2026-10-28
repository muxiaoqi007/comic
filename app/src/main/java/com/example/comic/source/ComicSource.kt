package com.example.comic.source

import com.example.comic.model.Book
import com.example.comic.model.Library
import com.example.comic.model.Page
import com.example.comic.model.Series
import com.example.comic.model.SourceConfig
import com.example.comic.model.SourceType
import java.io.Closeable
import java.io.InputStream

interface ComicSource : Closeable {
    val config: SourceConfig

    val id: String
        get() = config.id

    val type: SourceType
        get() = config.type

    val name: String
        get() = config.name

    suspend fun testConnection()

    suspend fun libraries(): List<Library>

    suspend fun series(libraryId: String): List<Series>

    suspend fun books(seriesId: String): List<Book>

    suspend fun pages(book: Book): List<Page>

    suspend fun openPage(page: Page): InputStream

    suspend fun openCover(series: Series): InputStream? = null

    suspend fun updateProgress(book: Book, page: Int, totalPages: Int, completed: Boolean) = Unit

    override fun close() = Unit
}

class SourceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
