package com.example.comic.data

import android.content.Context
import android.util.Log
import com.example.comic.model.SourceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val crypto = KeystoreCrypto()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _sources = MutableStateFlow(load())
    val sources: StateFlow<List<SourceConfig>> = _sources.asStateFlow()

    fun get(id: String): SourceConfig? = _sources.value.firstOrNull { it.id == id }

    @Synchronized
    fun save(config: SourceConfig) {
        val updated = _sources.value.toMutableList()
        val index = updated.indexOfFirst { it.id == config.id }
        if (index >= 0) updated[index] = config else updated += config
        persist(updated)
    }

    @Synchronized
    fun delete(id: String) {
        persist(_sources.value.filterNot { it.id == id })
    }

    private fun persist(sources: List<SourceConfig>) {
        val encoded = json.encodeToString(sources)
        prefs.edit().putString(KEY_SOURCES, crypto.encrypt(encoded)).apply()
        _sources.value = sources
    }

    private fun load(): List<SourceConfig> {
        val encoded = prefs.getString(KEY_SOURCES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<SourceConfig>>(crypto.decrypt(encoded))
        }.onFailure {
            Log.w(TAG, "Unable to decrypt source configuration", it)
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFS_NAME = "comic_sources"
        const val KEY_SOURCES = "sources"
        const val TAG = "SourceStore"
    }
}
