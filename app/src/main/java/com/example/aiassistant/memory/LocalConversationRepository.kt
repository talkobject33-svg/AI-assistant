package com.example.aiassistant.memory

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Role { USER, ASSISTANT }

data class ChatMessage(val id: Long, val role: Role, val text: String, val timestamp: Long)

/** Local conversation transcript. Persisted only while "Save conversation history" is on. */
class LocalConversationRepository(context: Context) {
    private val file = File(context.filesDir, "conversation.json")
    private val lock = Any()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    @Volatile private var persist = true
    private var nextId = 1L

    init { load() }

    private fun load() = synchronized(lock) {
        if (!file.exists()) return@synchronized
        runCatching {
            val arr = JSONArray(file.readText())
            val list = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                ChatMessage(o.getLong("id"), Role.valueOf(o.getString("role")), o.getString("text"), o.getLong("ts"))
            }
            nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1
            _messages.value = list
        }
    }

    fun setPersist(enabled: Boolean) = synchronized(lock) {
        persist = enabled
        if (enabled) save() else file.delete()
    }

    fun add(role: Role, text: String) = synchronized(lock) {
        if (text.isBlank()) return@synchronized
        _messages.value = (_messages.value + ChatMessage(nextId++, role, text.trim(), System.currentTimeMillis()))
            .takeLast(MAX_MESSAGES)
        save()
    }

    fun clear() = synchronized(lock) {
        _messages.value = emptyList()
        file.delete()
    }

    private fun save() {
        if (!persist) return
        runCatching {
            val arr = JSONArray()
            _messages.value.forEach {
                arr.put(JSONObject().put("id", it.id).put("role", it.role.name).put("text", it.text).put("ts", it.timestamp))
            }
            file.writeText(arr.toString())
        }
    }

    companion object { const val MAX_MESSAGES = 300 }
}
