package com.example.aiassistant.automation

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory diagnostics log. Never put API keys or typed text content in here. */
object AutomationLogger {
    data class Entry(val time: Long, val tag: String, val message: String)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun log(tag: String, message: String) {
        Log.d("Assistant/$tag", message)
        _entries.value = (_entries.value + Entry(System.currentTimeMillis(), tag, message)).takeLast(200)
    }

    fun clear() { _entries.value = emptyList() }
}
