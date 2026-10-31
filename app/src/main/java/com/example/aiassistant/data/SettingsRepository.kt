package com.example.aiassistant.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.aiassistant.memory.AssistantNameValidator
import com.example.aiassistant.memory.AssistantSettings
import com.example.aiassistant.memory.NameValidation
import com.example.aiassistant.memory.VoiceLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "assistant_settings")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val NAME = stringPreferencesKey("assistant_name")
        val LANG = stringPreferencesKey("voice_language")
        val CUSTOM_LANG = stringPreferencesKey("custom_language")
        val MODEL = stringPreferencesKey("model_id")
        val VOICE = stringPreferencesKey("voice_name")
        val CONTINUOUS = booleanPreferencesKey("continuous")
        val BARGE_IN = booleanPreferencesKey("barge_in")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
    }

    private val flow: Flow<AssistantSettings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    /** null until the first read finishes (lets the UI avoid flashing the wrong screen). */
    val settings: StateFlow<AssistantSettings?> =
        flow.map<AssistantSettings, AssistantSettings?> { it }.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun current(): AssistantSettings = flow.first()

    private fun Preferences.toSettings() = AssistantSettings(
        assistantName = this[K.NAME]?.takeIf { it.isNotBlank() },
        language = runCatching { VoiceLanguage.valueOf(this[K.LANG] ?: "") }.getOrDefault(VoiceLanguage.ENGLISH),
        customLanguage = this[K.CUSTOM_LANG].orEmpty(),
        modelId = this[K.MODEL]?.takeIf { it.isNotBlank() } ?: AssistantSettings.DEFAULT_MODEL,
        voiceName = this[K.VOICE] ?: "Kore",
        continuousConversation = this[K.CONTINUOUS] ?: true,
        bargeIn = this[K.BARGE_IN] ?: true,
        saveHistory = this[K.SAVE_HISTORY] ?: true
    )

    /** Returns an error message, or null on success. */
    suspend fun setAssistantName(raw: String): String? =
        when (val v = AssistantNameValidator.validate(raw)) {
            is NameValidation.Invalid -> v.reason
            is NameValidation.Valid -> { context.dataStore.edit { it[K.NAME] = v.name }; null }
        }

    suspend fun setLanguage(language: VoiceLanguage) = context.dataStore.edit { it[K.LANG] = language.name }
    suspend fun setCustomLanguage(text: String) =
        context.dataStore.edit { it[K.CUSTOM_LANG] = text.filter { c -> c.isLetter() || c == ' ' }.trim().take(40) }
    suspend fun setVoice(name: String) = context.dataStore.edit { it[K.VOICE] = name }
    suspend fun setContinuous(v: Boolean) = context.dataStore.edit { it[K.CONTINUOUS] = v }
    suspend fun setBargeIn(v: Boolean) = context.dataStore.edit { it[K.BARGE_IN] = v }
    suspend fun setSaveHistory(v: Boolean) = context.dataStore.edit { it[K.SAVE_HISTORY] = v }

    /** Returns an error message, or null on success. */
    suspend fun setModelId(raw: String): String? {
        val id = raw.trim().removePrefix("models/")
        if (!Regex("^[A-Za-z0-9._\\-]{3,80}$").matches(id)) return "Model ID may contain letters, numbers, dots, dashes and underscores."
        context.dataStore.edit { it[K.MODEL] = id }
        return null
    }
}

/** Gemini API key, stored encrypted with an Android Keystore-backed master key. Never logged. */
class SecureKeyStore(private val context: Context) {
    private val prefs by lazy {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "secure_keys", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    private val _hasKey = MutableStateFlow(false)
    val hasKey: StateFlow<Boolean> = _hasKey

    init { _hasKey.value = !get().isNullOrBlank() }

    fun get(): String? = runCatching { prefs.getString(KEY, null) }.getOrNull()

    /** Returns an error message, or null on success. */
    fun set(raw: String): String? {
        val k = raw.trim()
        if (!Regex("^[A-Za-z0-9_\\-]{20,200}$").matches(k)) return "That doesn't look like a valid API key (no spaces, 20+ characters)."
        return runCatching { prefs.edit().putString(KEY, k).apply(); _hasKey.value = true; null as String? }
            .getOrElse { "Secure storage is unavailable on this device." }
    }

    fun clear() { runCatching { prefs.edit().remove(KEY).apply() }; _hasKey.value = false }

    private companion object { const val KEY = "gemini_api_key" }
}
