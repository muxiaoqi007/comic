package com.example.comic.data

import android.content.Context
import com.example.comic.model.ReadingProgress
import com.example.comic.model.storageKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _progress = MutableStateFlow(load())
    val progress: StateFlow<Map<String, ReadingProgress>> = _progress.asStateFlow()

    fun get(sourceId: String, bookId: String): ReadingProgress? =
        _progress.value["$sourceId|$bookId"]

    @Synchronized
    fun save(progress: ReadingProgress) {
        val updated = _progress.value.toMutableMap()
        updated[progress.storageKey] = progress
        prefs.edit().putString(KEY_PROGRESS, json.encodeToString(updated)).apply()
        _progress.value = updated
    }

    private fun load(): Map<String, ReadingProgress> {
        val encoded = prefs.getString(KEY_PROGRESS, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, ReadingProgress>>(encoded)
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val PREFS_NAME = "comic_progress"
        const val KEY_PROGRESS = "progress"
    }
}
