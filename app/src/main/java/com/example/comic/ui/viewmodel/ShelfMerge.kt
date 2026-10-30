package com.example.comic.ui.viewmodel

import com.example.comic.data.CachedShelf
import com.example.comic.model.Series
import com.example.comic.model.SourceConfig
import com.example.comic.util.compareTitles

internal fun mergeShelfEntries(
    sources: List<SourceConfig>,
    fresh: Map<String, List<Series>>,
    cached: Map<String, CachedShelf>,
): List<LibraryViewModel.Entry> =
    sources.asSequence()
        .filter { it.enabled }
        .flatMap { config ->
            val series = fresh[config.id] ?: cached[config.id]?.series.orEmpty()
            series.asSequence().map { LibraryViewModel.Entry(it, config.name, config.type) }
        }
        .sortedWith { a, b -> compareTitles(a.series.title, b.series.title) }
        .toList()
