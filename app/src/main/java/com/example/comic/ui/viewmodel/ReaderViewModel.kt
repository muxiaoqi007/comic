package com.example.comic.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comic.data.ImageLoader
import com.example.comic.data.SourceRepository
import com.example.comic.model.Book
import com.example.comic.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderViewModel(
    private val repository: SourceRepository,
    val imageLoader: ImageLoader,
    sourceId: String,
    bookId: String,
    seriesId: String,
    bookTitle: String,
    initialPageOverride: Int? = null,
) : ViewModel() {

    val book = Book(
        id = bookId,
        sourceId = sourceId,
        seriesId = seriesId,
        title = bookTitle,
    )

    data class UiState(
        val loading: Boolean = true,
        val pages: List<Page> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val initialPage: Int = (initialPageOverride ?: repository.progressFor(book)?.page ?: 0)
        .coerceAtLeast(0)

    private var saveJob: Job? = null

    init {
        viewModelScope.launch { repository.repairProgressSeries(book) }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState(loading = true)
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.pages(book) }
            }
            _state.value = result.fold(
                onSuccess = { UiState(loading = false, pages = it) },
                onFailure = { UiState(loading = false, error = it.message ?: "加载页面失败") },
            )
        }
    }

    fun scheduleSave(page: Int, totalPages: Int) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700)
            persist(page, totalPages)
        }
    }

    fun saveNow(page: Int, totalPages: Int) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { persist(page, totalPages) }
    }

    fun saveNowBlocking(page: Int, totalPages: Int) {
        saveJob?.cancel()
        if (totalPages <= 0) return
        val safePage = page.coerceIn(0, totalPages - 1)
        repository.saveProgressImmediate(
            book = book,
            page = safePage,
            totalPages = totalPages,
            completed = safePage == totalPages - 1,
        )
    }

    private suspend fun persist(page: Int, totalPages: Int) {
        if (totalPages <= 0) return
        val safePage = page.coerceIn(0, totalPages - 1)
        repository.saveProgress(
            book = book,
            page = safePage,
            totalPages = totalPages,
            completed = safePage == totalPages - 1,
        )
    }
}
