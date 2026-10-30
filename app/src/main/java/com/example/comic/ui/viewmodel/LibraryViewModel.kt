package com.example.comic.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comic.data.CachedShelf
import com.example.comic.data.ShelfCacheStore
import com.example.comic.data.SourceRepository
import com.example.comic.model.Series
import com.example.comic.model.SourceConfig
import com.example.comic.model.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(
    private val repository: SourceRepository,
    private val shelfCache: ShelfCacheStore,
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

    private var refreshJob: Job? = null
    private var refreshGeneration = 0
    private var cacheSnapshot: Map<String, CachedShelf> = emptyMap()

    init {
        cacheSnapshot = shelfCache.load()
        val sources = repository.sources.value
        _state.value = UiState(
            loading = sources.any { it.enabled },
            entries = mergeShelfEntries(sources, emptyMap(), cacheSnapshot),
        )
        viewModelScope.launch {
            repository.sources.collect { configs ->
                cacheSnapshot = shelfCache.load()
                syncEntriesWithSources(configs)
                startRefresh(configs)
            }
        }
    }

    fun refresh() {
        startRefresh(repository.sources.value)
    }

    private fun syncEntriesWithSources(sources: List<SourceConfig>) {
        _state.update { current ->
            val visible = current.entries
                .groupBy { it.series.sourceId }
                .mapValues { (_, entries) -> entries.map { it.series } }
            current.copy(entries = mergeShelfEntries(sources, visible, cacheSnapshot))
        }
    }

    private fun startRefresh(configs: List<SourceConfig>) {
        val enabled = configs.filter { it.enabled && it.type != SourceType.ZLIBRARY }
        val generation = ++refreshGeneration
        refreshJob?.cancel()

        if (enabled.isEmpty()) {
            _state.value = UiState(loading = false)
            return
        }

        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, failures = emptyList()) }
            val results = coroutineScope {
                enabled.map { config ->
                    async(Dispatchers.IO) { config to loadSource(config) }
                }.awaitAll()
            }
            currentCoroutineContext().ensureActive()
            if (generation != refreshGeneration) return@launch

            val successes = results.mapNotNull { (config, result) ->
                result.getOrNull()?.let { config to it }
            }
            val failures = results.mapNotNull { (config, result) ->
                val error = result.exceptionOrNull() ?: return@mapNotNull null
                Log.w("ComicLibrary", "加载源「${config.name}」失败", error)
                "${config.name}：${error.message?.takeIf { it.isNotBlank() } ?: "未知错误"}"
            }

            if (successes.isNotEmpty()) {
                val records = successes.map { (config, series) ->
                    CachedShelf(
                        sourceId = config.id,
                        sourceName = config.name,
                        sourceType = config.type,
                        series = series.map { it.copy(description = null) },
                        savedAt = System.currentTimeMillis(),
                    )
                }
                withContext(Dispatchers.IO) {
                    shelfCache.update(records)
                }
                cacheSnapshot = cacheSnapshot + records.associateBy { it.sourceId }
            }

            if (generation != refreshGeneration) return@launch
            _state.value = UiState(
                loading = false,
                entries = mergeShelfEntries(
                    enabled,
                    successes.associate { (config, series) -> config.id to series },
                    cacheSnapshot,
                ),
                failures = failures,
            )
        }
    }

    private suspend fun loadSource(config: SourceConfig): Result<List<Series>> = try {
        Result.success(repository.seriesForSource(config))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
