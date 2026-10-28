package com.example.comic.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comic.data.SourceRepository
import com.example.comic.model.Book
import com.example.comic.model.Series
import com.example.comic.util.compareTitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val bookOrder = Comparator<Book> { a, b ->
    val numberA = a.number?.trim()?.toDoubleOrNull()
    val numberB = b.number?.trim()?.toDoubleOrNull()
    if (numberA != null && numberB != null) {
        val comparison = numberA.compareTo(numberB)
        if (comparison != 0) return@Comparator comparison
    } else if (numberA != null) {
        return@Comparator -1
    } else if (numberB != null) {
        return@Comparator 1
    }
    compareTitles(a.title, b.title)
}

class SeriesDetailViewModel(
    private val repository: SourceRepository,
    private val series: Series,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val books: List<Book> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState(loading = true)
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.books(series) }
            }
            _state.value = result.fold(
                onSuccess = { UiState(loading = false, books = it.sortedWith(bookOrder)) },
                onFailure = { UiState(loading = false, error = it.message ?: "加载失败") },
            )
        }
    }
}
