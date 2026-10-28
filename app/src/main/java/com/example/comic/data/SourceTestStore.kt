package com.example.comic.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SourceTestRecord(
    val sourceId: String,
    val ok: Boolean,
    val message: String,
    val testedAt: Long,
)

class SourceTestStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _results = MutableStateFlow(load())
    val results: StateFlow<Map<String, SourceTestRecord>> = _results.asStateFlow()

    @Synchronized
    fun record(sourceId: String, ok: Boolean, message: String) {
        val entry = SourceTestRecord(
            sourceId = sourceId,
            ok = ok,
            message = message,
            testedAt = System.currentTimeMillis(),
        )
        persist(_results.value + (sourceId to entry))
    }

    @Synchronized
    fun remove(sourceId: String) {
        if (sourceId !in _results.value) return
        persist(_results.value - sourceId)
    }

    private fun persist(results: Map<String, SourceTestRecord>) {
        prefs.edit()
            .putString(KEY_RESULTS, json.encodeToString(results.values.toList()))
            .apply()
        _results.value = results
    }

    private fun load(): Map<String, SourceTestRecord> {
        val raw = prefs.getString(KEY_RESULTS, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<List<SourceTestRecord>>(raw).associateBy { it.sourceId }
        }.onFailure {
            Log.w(TAG, "Unable to read source test results", it)
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val PREFS_NAME = "source_tests"
        const val KEY_RESULTS = "results"
        const val TAG = "SourceTestStore"
    }
}
