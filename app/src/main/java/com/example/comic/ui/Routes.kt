package com.example.comic.ui

import android.net.Uri
import com.example.comic.model.Book
import com.example.comic.model.Series

object Routes {
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val SOURCES = "sources"

    const val SERIES_PATTERN =
        "series?sourceId={sourceId}&seriesId={seriesId}&title={title}&libraryId={libraryId}&bookCount={bookCount}"
    const val READER_PATTERN =
        "reader?sourceId={sourceId}&bookId={bookId}&seriesId={seriesId}&title={title}"

    fun series(series: Series): String =
        "series?sourceId=${Uri.encode(series.sourceId)}" +
            "&seriesId=${Uri.encode(series.id)}" +
            "&title=${Uri.encode(series.title)}" +
            "&libraryId=${Uri.encode(series.libraryId)}" +
            "&bookCount=${series.bookCount ?: -1}"

    fun reader(book: Book): String =
        "reader?sourceId=${Uri.encode(book.sourceId)}" +
            "&bookId=${Uri.encode(book.id)}" +
            "&seriesId=${Uri.encode(book.seriesId)}" +
            "&title=${Uri.encode(book.title)}"
}
