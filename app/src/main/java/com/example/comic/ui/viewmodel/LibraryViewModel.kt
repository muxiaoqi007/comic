package com.example.comic.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comic.data.SourceRepository
import com.example.comic.model.Series
import com.example.comic.model.SourceType
import com.example.comic.util.compareTitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repository: SourceRepository,
) : ViewModel() {

    data class Entry(
        val series: Series,
        val sourceName: String,
        val sourceType: SourceType,
    )

    data class UiState(
        val loading: Boolean = true,
        val entries: List<Entry> = emptyList(),
        val failures: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    fun refresh() {
        if (job?.isActive == true) return
        val configs = repository.sources.value
        job = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val results = coroutineScope {
                configs.map { config ->
                    async(Dispatchers.IO) {
                        config to runCatching { repository.seriesForSource(config) }
                    }
                }.awaitAll()
            }
            val entries = results.flatMap { (config, result) ->
                result.getOrDefault(emptyList()).map { series ->
                    Entry(series = series, sourceName = config.name, sourceType = config.type)
                }
            }.sortedWith { a, b -> compareTitles(a.series.title, b.series.title) }
            val failures = results.mapNotNull { (config, result) ->
                val error = result.exceptionOrNull() ?: return@mapNotNull null
                Log.w("ComicLibrary", "加载源「${config.name}」失败", error)
                val detail = error.message?.takeIf { it.isNotBlank() } ?: "未知错误"
                "${config.name}：$detail"
            }
            _state.value = UiState(
                loading = false,
                entries = entries,
                failures = failures,
            )
        }
    }
}
