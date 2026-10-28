package com.example.comic.data

import android.content.Context
import com.example.comic.model.ReadingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ReadingSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("reading_settings", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(loadMode())
    val mode: StateFlow<ReadingMode> = _mode.asStateFlow()

    fun setMode(mode: ReadingMode) {
        if (_mode.value == mode) return
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    private fun loadMode(): ReadingMode {
        val saved = prefs.getString(KEY_MODE, null) ?: return ReadingMode.LTR
        return runCatching { ReadingMode.valueOf(saved) }.getOrDefault(ReadingMode.LTR)
    }

    private companion object {
        const val KEY_MODE = "mode"
    }
}
