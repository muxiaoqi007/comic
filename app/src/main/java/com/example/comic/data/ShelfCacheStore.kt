package com.example.comic.data

import android.content.Context
import android.util.Log
import com.example.comic.model.Series
import com.example.comic.model.SourceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CachedShelf(
    val sourceId: String,
    val sourceName: String,
    val sourceType: SourceType,
    val series: List<Series>,
    val savedAt: Long,
)

class ShelfCacheStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun load(): Map<String, CachedShelf> {
        val raw = prefs.getString(KEY_SNAPSHOTS, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<List<CachedShelf>>(raw).associateBy { it.sourceId }
        }.onFailure {
            Log.w(TAG, "Unable to read shelf cache", it)
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun update(records: List<CachedShelf>) {
        if (records.isEmpty()) return
        val merged = load().toMutableMap()
        records.forEach { merged[it.sourceId] = it }
        prefs.edit()
            .putString(KEY_SNAPSHOTS, json.encodeToString(merged.values.toList()))
            .apply()
    }

    @Synchronized
    fun remove(sourceId: String) {
        val current = load()
        if (sourceId !in current) return
        val remaining = current - sourceId
        if (remaining.isEmpty()) {
            prefs.edit().remove(KEY_SNAPSHOTS).apply()
        } else {
            prefs.edit()
                .putString(KEY_SNAPSHOTS, json.encodeToString(remaining.values.toList()))
                .apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "shelf_cache"
        const val KEY_SNAPSHOTS = "snapshots"
        const val TAG = "ShelfCacheStore"
    }
}
