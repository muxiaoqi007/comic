package com.example.comic.ui.viewmodel

import com.example.comic.model.Series
import com.example.comic.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfQueryTest {
    private fun entry(id: String, title: String, source: String = "nas") =
        LibraryViewModel.Entry(Series(id, source, "library", title), source, SourceType.KOMGA)

    private val entries = listOf(entry("10", "Volume 10"), entry("2", "Volume 2"), entry("a", "海街日记", "local"))

    @Test fun naturalTitleOrder() {
        assertEquals(listOf("2", "10"), selectShelfEntries(entries, "volume", null, ShelfSort.TITLE, emptyMap()).map { it.series.id })
    }

    @Test fun searchTrimsAndIgnoresCase() {
        assertEquals(2, selectShelfEntries(entries, "  VOLUME  ", null, ShelfSort.TITLE, emptyMap()).size)
    }

    @Test fun searchMatchesSourceName() {
        assertEquals("a", selectShelfEntries(entries, "local", null, ShelfSort.TITLE, emptyMap()).single().series.id)
    }

    @Test fun sourceAndQueryCombine() {
        assertEquals(0, selectShelfEntries(entries, "海街", "nas", ShelfSort.TITLE, emptyMap()).size)
    }

    @Test fun recentReadingUsesSourceAndSeriesKey() {
        val sameId = listOf(entry("2", "A", "nas"), entry("2", "B", "local"))
        assertEquals("local", selectShelfEntries(sameId, "", null, ShelfSort.RECENT, mapOf("local|2" to 200L, "nas|2" to 100L)).first().series.sourceId)
    }

    @Test fun reverseOrderAndEmptyResults() {
        assertEquals(listOf("10", "2"), selectShelfEntries(entries, "volume", null, ShelfSort.TITLE_DESC, emptyMap()).map { it.series.id })
        assertEquals(emptyList<LibraryViewModel.Entry>(), selectShelfEntries(entries, "not-found", null, ShelfSort.RECENT, emptyMap()))
    }
}
