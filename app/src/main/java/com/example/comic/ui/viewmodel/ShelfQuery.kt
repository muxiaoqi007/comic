package com.example.comic.ui.viewmodel

import com.example.comic.util.compareTitles

enum class ShelfSort(val label: String) {
    TITLE("名称顺序"),
    TITLE_DESC("名称倒序"),
    RECENT("最近阅读"),
}

fun selectShelfEntries(
    entries: List<LibraryViewModel.Entry>,
    query: String,
    sourceId: String?,
    sort: ShelfSort,
    lastRead: Map<String, Long>,
): List<LibraryViewModel.Entry> {
    val term = query.trim()
    return entries.filter { entry ->
        (sourceId == null || entry.series.sourceId == sourceId) &&
            (entry.series.title.contains(term, ignoreCase = true) ||
                entry.sourceName.contains(term, ignoreCase = true))
    }.sortedWith { a, b ->
        val titleOrder = compareTitles(a.series.title, b.series.title)
        when (sort) {
            ShelfSort.TITLE -> titleOrder
            ShelfSort.TITLE_DESC -> -titleOrder
            ShelfSort.RECENT -> {
                val recentOrder = (lastRead["${b.series.sourceId}|${b.series.id}"] ?: 0L)
                    .compareTo(lastRead["${a.series.sourceId}|${a.series.id}"] ?: 0L)
                if (recentOrder == 0) titleOrder else recentOrder
            }
        }
    }
}
