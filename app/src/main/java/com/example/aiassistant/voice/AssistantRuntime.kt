package com.example.aiassistant.voice

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.aiassistant.ai.ActionParser
import com.example.aiassistant.ai.ActionResult
import com.example.aiassistant.ai.ExecutionStatus
import com.example.aiassistant.ai.ParseResult
import com.example.aiassistant.automation.AppLauncher
import com.example.aiassistant.automation.AutomationLogger
import com.example.aiassistant.automation.PhoneActionRouter
import com.example.aiassistant.data.SecureKeyStore
import com.example.aiassistant.data.SettingsRepository
import com.example.aiassistant.memory.LocalConversationRepository
import com.example.aiassistant.permissions.PermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Wires the Live session, the action router, storage and the foreground service together. */
class AssistantRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val keyStore: SecureKeyStore,
    private val conversations: LocalConversationRepository,
    private val permissions: PermissionManager
) {
    val router = PhoneActionRouter(context, AppLauncher(context)) { manager.stopSpeaking() }

    val manager: GeminiLiveManager = GeminiLiveManager(
        context = context,
        scope = scope,
        settingsProvider = { settingsRepository.current() },
        apiKeyProvider = { keyStore.get() },
        networkAvailable = { permissions.isNetworkAvailable() },
        toolHandler = { name, args -> handleTool(name, args) },
        onTranscript = { role, text -> conversations.add(role, text) }
    )

    /** Latest saved assistant name (drives notifications and the service). */
    val assistantName = MutableStateFlow<String?>(null)
    private val _callStartedAt = MutableStateFlow<Long?>(null)
    val callStartedAt: StateFlow<Long?> = _callStartedAt

    init {
        scope.launch {
            settingsRepository.settings.filterNotNull().collect { s ->
                assistantName.value = s.assistantName
                conversations.setPersist(s.saveHistory)
                manager.restartIfConfigChanged(s)
            }
        }
        // When the session fully ends (user, error, idle timeout), release the foreground service.
        scope.launch {
            combine(manager.state, manager.connected) { st, c -> st.isActive || c }
                .distinctUntilChanged()
                .collect { open -> if (!open) { _callStartedAt.value = null; stopService() } }
        }
    }

    /** Call after the mic permission has been granted. */
    fun startSession() {
        if (manager.isSessionOpen) return
        if (!permissions.hasMicrophone()) { manager.reportPreflightError("Microphone permission is needed to talk."); return }
        if (keyStore.get().isNullOrBlank()) { manager.reportPreflightError("Add your Gemini API key in Settings first."); return }
        router.setPaused(false)
        _callStartedAt.value = System.currentTimeMillis()
        ContextCompat.startForegroundService(context, Intent(context, VoiceSessionService::class.java))
        manager.start()
    }

    fun endSession() { manager.stop() }

    /** Stops voice, speech, pending actions and phone control. */
    fun stopAll() {
        router.cancelPending()
        router.setPaused(true)
        manager.stop()
    }

    private fun stopService() { context.stopService(Intent(context, VoiceSessionService::class.java)) }

    fun confirmPending() {
        scope.launch {
            val r = router.confirmPending() ?: return@launch
            manager.sendText("(App note: the user confirmed on screen. Result: ${r.status} - ${r.message})", showInTranscript = false)
        }
    }

    fun cancelPending() {
        router.cancelPending()
        manager.sendText("(App note: the user cancelled that action on screen.)", showInTranscript = false)
    }

    private suspend fun handleTool(name: String, args: JSONObject?): JSONObject {
        val result: ActionResult = when (val parsed = ActionParser.parse(name, args)) {
            is ParseResult.Invalid -> {
                AutomationLogger.log("Tool", "Rejected: ${parsed.reason}")
                ActionResult(ExecutionStatus.UNSUPPORTED, "Rejected: ${parsed.reason}")
            }
            is ParseResult.Valid -> router.executeAction(parsed.action)
        }
        return result.toJson()
    }
}
