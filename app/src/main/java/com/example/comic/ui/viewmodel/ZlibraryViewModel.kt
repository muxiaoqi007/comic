package com.example.comic.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comic.data.DownloadStore
import com.example.comic.data.SourceRepository
import com.example.comic.data.ZlibraryDownloadManager
import com.example.comic.model.DownloadRecord
import com.example.comic.source.zlibrary.ZlibraryBook
import com.example.comic.source.zlibrary.ZlibraryProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ZlibraryFormat(val label: String, val extension: String?) {
    ALL("全部", null),
    EPUB("EPUB", "epub"),
    PDF("PDF", "pdf"),
}

class ZlibraryViewModel(
    private val repository: SourceRepository,
    store: DownloadStore,
    private val downloads: ZlibraryDownloadManager,
    val sourceId: String,
) : ViewModel() {

    data class UiState(
        val connecting: Boolean = true,
        val connected: Boolean = false,
        val error: String? = null,
        val profile: ZlibraryProfile? = null,
        val books: List<ZlibraryBook> = emptyList(),
        val query: String = "",
        val page: Int = 0,
        val totalPages: Int = 0,
        val searching: Boolean = false,
        val searchError: String? = null,
        val format: ZlibraryFormat = ZlibraryFormat.ALL,
    )

    val sourceName: String =
        repository.sources.value.firstOrNull { it.id == sourceId }?.name ?: "Z-Library"

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val active: StateFlow<Map<String, ZlibraryDownloadManager.Active>> = downloads.active

    val downloaded: StateFlow<Map<String, DownloadRecord>> = store.items
        .map { records ->
            records.filter { it.sourceId == sourceId }.associateBy { it.bookId }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var searchJob: Job? = null

    init {
        connect()
    }

    fun connect() {
        val config = repository.sources.value.firstOrNull { it.id == sourceId }
        if (config == null) {
            _state.update {
                it.copy(connecting = false, connected = false, error = "这个 Z-Library 源不存在")
            }
            return
        }
        if (!config.enabled) {
            _state.update {
                it.copy(connecting = false, connected = false, error = "该源已停用，请先在源管理中启用")
            }
            return
        }
        _state.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            val result = try {
                Result.success(repository.zlibraryConnect(sourceId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { profile ->
                    _state.update {
                        it.copy(connecting = false, connected = true, error = null, profile = profile)
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            connecting = false,
                            connected = false,
                            error = error.message ?: "Z-Library 登录失败",
                        )
                    }
                },
            )
        }
    }

    fun search(query: String, format: ZlibraryFormat = _state.value.format) {
        val term = query.trim()
        _state.update { it.copy(query = term, format = format) }
        searchJob?.cancel()
        if (term.isEmpty()) {
            _state.update {
                it.copy(books = emptyList(), page = 0, totalPages = 0, searching = false, searchError = null)
            }
            return
        }
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true, searchError = null) }
            val result = try {
                Result.success(
                    repository.zlibrarySearch(
                        sourceId = sourceId,
                        query = term,
                        page = 1,
                        extensions = format.extension?.let(::listOf).orEmpty(),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(
                            searching = false,
                            books = page.books,
                            page = page.page,
                            totalPages = page.totalPages,
                            searchError = null,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(searching = false, searchError = error.message ?: "搜索失败")
                    }
                },
            )
        }
    }

    fun selectFormat(format: ZlibraryFormat) {
        if (_state.value.format == format) return
        _state.update { it.copy(format = format) }
        if (_state.value.query.isNotBlank()) {
            search(_state.value.query, format)
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.searching || current.query.isBlank() || current.page >= current.totalPages) return
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true, searchError = null) }
            val result = try {
                Result.success(
                    repository.zlibrarySearch(
                        sourceId = sourceId,
                        query = current.query,
                        page = current.page + 1,
                        extensions = current.format.extension?.let(::listOf).orEmpty(),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(
                            searching = false,
                            books = it.books + page.books,
                            page = page.page,
                            totalPages = page.totalPages,
                            searchError = null,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(searching = false, searchError = error.message ?: "加载更多失败")
                    }
                },
            )
        }
    }

    fun refreshProfile() {
        viewModelScope.launch {
            val profile = runCatching { repository.zlibraryProfile(sourceId) }.getOrNull()
            if (profile != null) {
                _state.update { it.copy(profile = profile) }
            }
        }
    }

    fun download(book: ZlibraryBook) {
        downloads.download(sourceId, book)
    }

    fun retry(item: ZlibraryDownloadManager.Active) {
        downloads.retry(item)
    }

    fun cancel(key: String) {
        downloads.cancel(key)
    }
}
