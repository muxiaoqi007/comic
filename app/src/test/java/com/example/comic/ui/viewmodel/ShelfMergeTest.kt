package com.example.comic.ui.viewmodel

import com.example.comic.data.CachedShelf
import com.example.comic.model.Series
import com.example.comic.model.SourceConfig
import com.example.comic.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfMergeTest {

    private fun config(id: String, name: String = id, enabled: Boolean = true) = SourceConfig(
        id = id,
        type = SourceType.KOMGA,
        name = name,
        enabled = enabled,
    )

    private fun series(id: String, sourceId: String, title: String) = Series(
        id = id,
        sourceId = sourceId,
        libraryId = "library",
        title = title,
        bookCount = 1,
    )

    private fun cached(sourceId: String, vararg series: Series) = CachedShelf(
        sourceId = sourceId,
        sourceName = sourceId,
        sourceType = SourceType.KOMGA,
        series = series.toList(),
        savedAt = 0L,
    )

    @Test
    fun disabledSourcesAreExcluded() {
        val entries = mergeShelfEntries(
            sources = listOf(config("nas", enabled = false)),
            fresh = mapOf("nas" to listOf(series("1", "nas", "Cached"))),
            cached = mapOf("nas" to cached("nas", series("1", "nas", "Cached"))),
        )

        assertTrue(entries.isEmpty())
    }

    @Test
    fun failedSourceFallsBackToCache() {
        val entries = mergeShelfEntries(
            sources = listOf(config("nas", name = "家里 NAS")),
            fresh = emptyMap(),
            cached = mapOf("nas" to cached("nas", series("1", "nas", "Cached"))),
        )

        assertEquals(1, entries.size)
        assertEquals("家里 NAS", entries.single().sourceName)
        assertEquals("Cached", entries.single().series.title)
    }

    @Test
    fun freshEmptyResultReplacesCache() {
        val entries = mergeShelfEntries(
            sources = listOf(config("nas")),
            fresh = mapOf("nas" to emptyList()),
            cached = mapOf("nas" to cached("nas", series("1", "nas", "Old"))),
        )

        assertTrue(entries.isEmpty())
    }

    @Test
    fun removedSourcesAreIgnored() {
        val entries = mergeShelfEntries(
            sources = listOf(config("nas")),
            fresh = mapOf("nas" to listOf(series("1", "nas", "Current"))),
            cached = mapOf("old" to cached("old", series("2", "old", "Removed"))),
        )

        assertEquals(listOf("Current"), entries.map { it.series.title })
    }

    @Test
    fun titlesAreSortedNaturally() {
        val entries = mergeShelfEntries(
            sources = listOf(config("local")),
            fresh = mapOf(
                "local" to listOf(
                    series("10", "local", "Vol 10"),
                    series("2", "local", "Vol 2"),
                    series("1", "local", "Vol 1"),
                ),
            ),
            cached = emptyMap(),
        )

        assertEquals(listOf("1", "2", "10"), entries.map { it.series.id })
    }
}
