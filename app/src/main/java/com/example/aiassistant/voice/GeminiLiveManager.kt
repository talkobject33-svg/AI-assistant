package com.example.aiassistant.voice

import android.os.SystemClock
import android.util.Base64
import com.example.aiassistant.ai.ActionSchema
import com.example.aiassistant.ai.AssistantInstructions
import com.example.aiassistant.automation.AutomationLogger
import com.example.aiassistant.memory.AssistantSettings
import com.example.aiassistant.memory.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** Wire format for the Gemini Live API (BidiGenerateContent over WebSocket, v1beta). */
object LiveProtocol {
    const val ENDPOINT =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val INPUT_MIME = "audio/pcm;rate=16000"

    fun url(apiKey: String) = ENDPOINT + "?key=" + URLEncoder.encode(apiKey, "UTF-8")

    fun setup(modelId: String, instructions: String?, voice: String, tools: JSONArray?): String {
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
        val setup = JSONObject()
            .put("model", "models/" + modelId.removePrefix("models/"))
            .put("generationConfig", gen)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        if (instructions != null)
            setup.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instructions))))
        if (tools != null) setup.put("tools", tools)
        return JSONObject().put("setup", setup).toString()
    }

    fun audioChunk(base64: String) =
        "{\"realtimeInput\":{\"audio\":{\"data\":\"$base64\",\"mimeType\":\"$INPUT_MIME\"}}}"

    fun text(t: String) = JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()

    fun toolResponse(id: String, name: String, response: JSONObject) = JSONObject().put("toolResponse",
        JSONObject().put("functionResponses", JSONArray().put(
            JSONObject().put("id", id).put("name", name).put("response", response)))).toString()
}

data class LiveError(val message: String, val retryable: Boolean)

object LiveErrorClassifier {
    fun classify(closeCode: Int?, reason: String?, httpCode: Int?, error: Throwable?): LiveError {
        val r = (reason ?: error?.message ?: "").lowercase()
        return when {
            httpCode == 401 || httpCode == 403 || "api key" in r || "api_key" in r || "permission" in r ->
                LiveError("The Gemini API key was rejected or lacks access. Check it in Settings.", false)
            httpCode == 429 || "quota" in r || "exhausted" in r || "billing" in r ->
                LiveError("Gemini quota or billing limit reached. Check your plan and try again later.", false)
            httpCode == 404 || "not found" in r || "not supported" in r || "not implemented" in r ->
                LiveError("This model ID isn't supported for Live sessions on your account. Change it in Settings.", false)
            httpCode == 400 -> LiveError("Gemini rejected the request. Check the model ID in Settings.", false)
            error is UnknownHostException || error is ConnectException || error is SocketTimeoutException ->
                LiveError("Network unavailable.", true)
            else -> LiveError(reason?.takeIf { it.isNotBlank() }?.take(120) ?: "Connection lost.", true)
        }
    }
}

/** Opens a throwaway session, waits for setupComplete, and closes it. */
object LiveConnectionTester {
    data class Result(val ok: Boolean, val message: String)

    suspend fun test(client: OkHttpClient, apiKey: String, modelId: String): Result = try {
        withTimeout(12_000) {
            suspendCancellableCoroutine<Result> { cont ->
                val done = AtomicBoolean(false)
                fun finish(r: Result) { if (done.compareAndSet(false, true) && cont.isActive) cont.resume(r) }
                val ws = client.newWebSocket(Request.Builder().url(LiveProtocol.url(apiKey)).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(LiveProtocol.setup(modelId, null, "Kore", null))
                        }
                        private fun inspect(webSocket: WebSocket, text: String) {
                            if (text.contains("setupComplete")) {
                                finish(Result(true, "Connected. The model \"$modelId\" accepted a Live session."))
                                webSocket.close(1000, null)
                            }
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) = inspect(webSocket, text)
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = inspect(webSocket, bytes.utf8())
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            finish(Result(false, LiveErrorClassifier.classify(code, reason, null, null).message))
                            webSocket.close(1000, null)
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            finish(Result(false, LiveErrorClassifier.classify(null, null, response?.code, t).message))
                        }
                    })
                cont.invokeOnCancellation { ws.cancel() }
            }
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Result(false, "Timed out waiting for Gemini. Check your connection.")
    }
}

data class LiveDiagnostics(
    val modelId: String = "", val sessionsStarted: Int = 0, val reconnects: Int = 0,
    val audioKbSent: Long = 0, val audioKbReceived: Long = 0, val droppedChunks: Int = 0, val lastClose: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class)
class GeminiLiveManager(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
    private val settingsProvider: suspend () -> AssistantSettings,
    private val apiKeyProvider: () -> String?,
    private val networkAvailable: () -> Boolean,
    private val toolHandler: suspend (name: String, args: JSONObject?) -> JSONObject,
    private val onTranscript: (Role, String) -> Unit
) {
    /** All state changes are serialized on this single-threaded dispatcher. */
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    val audioIn = AudioInputManager(scope)
    val audioOut = AudioOutputManager(context, scope)

    val state = MutableStateFlow(VoiceSessionState.IDLE)
    val connected = MutableStateFlow(false)
    val errorMessage = MutableStateFlow<String?>(null)
    val partialUser = MutableStateFlow("")
    val partialAssistant = MutableStateFlow("")
    val reconnectAttempt = MutableStateFlow(0)
    val diagnostics = MutableStateFlow(LiveDiagnostics())
    val audioLevel: Flow<Float> = combine(state, audioIn.level, audioOut.level) { s, i, o ->
        if (s == VoiceSessionState.SPEAKING) o else i
    }

    val isSessionOpen: Boolean get() = connected.value || state.value.isActive
    val activeSettings: AssistantSettings get() = settings

    @Volatile private var ready = false
    @Volatile private var userStopped = true
    @Volatile private var sessionId = 0
    @Volatile private var socket: WebSocket? = null
    @Volatile private var settings = AssistantSettings()
    private var configSignature = ""
    private var retry = 0
    private var reconnectJob: Job? = null
    private var monitorJob: Job? = null
    private val userBuf = StringBuilder()
    private val asstBuf = StringBuilder()
    private var turnCompleteSeen = false
    private var suppressAudio = false
    private var toolsInFlight = 0
    private var lastInputAt = 0L
    private var lastActivity = 0L
    private var userSpoke = false
    private var turnSeq = 0
    private var listeningEnabled = true
    private val toolJobs = ConcurrentHashMap<String, Job>()
    private val bytesSent = AtomicLong()
    private val bytesRecv = AtomicLong()
    private val dropped = AtomicInteger()
    private var sessionsStarted = 0
    private var reconnects = 0
    private var lastClose = ""

    // ---------- public API ----------

    fun start() {
        scope.launch(serial) {
            if (state.value.isActive || connected.value) return@launch
            userStopped = false; retry = 0; listeningEnabled = true
            errorMessage.value = null; reconnectAttempt.value = 0
            sessionsStarted++
            startMonitor()
            connect(initial = true)
        }
    }

    /** Ends the session and releases the microphone immediately. */
    fun stop() {
        userStopped = true
        audioIn.stop()
        audioOut.flush()
        scope.launch(serial) {
            teardown()
            reconnectAttempt.value = 0
            if (state.value != VoiceSessionState.ERROR) state.value = VoiceSessionState.IDLE
        }
    }

    fun stopSpeaking() {
        audioOut.flush()
        scope.launch(serial) {
            if (state.value == VoiceSessionState.SPEAKING) {
                suppressAudio = true // drop the rest of this turn's audio; the server may still be generating
                flushTranscripts()
                finishTurn()
            }
        }
    }

    fun resumeListening() {
        scope.launch(serial) {
            if (!ready || state.value != VoiceSessionState.IDLE) return@launch
            listeningEnabled = true
            if (startMic()) state.value = VoiceSessionState.LISTENING
        }
    }

    fun sendText(text: String, showInTranscript: Boolean = true) {
        scope.launch(serial) {
            if (!ready) return@launch
            suppressAudio = false
            if (socket?.send(LiveProtocol.text(text)) == true) {
                if (showInTranscript) onTranscript(Role.USER, text)
                touch()
            }
        }
    }

    /** Applies a changed name/language/model/voice by starting a fresh session with new instructions. */
    fun restartIfConfigChanged(newSettings: AssistantSettings) {
        scope.launch(serial) {
            if (userStopped || !(ready || state.value.isActive)) return@launch
            if (signature(newSettings) == configSignature) return@launch
            AutomationLogger.log("Live", "Settings changed; restarting session")
            retry = 0
            scheduleReconnect(LiveError("Settings changed.", true), immediate = true)
        }
    }

    fun reportPreflightError(message: String) { scope.launch(serial) { fail(message) } }

    suspend fun testConnection(apiKey: String, modelId: String) = LiveConnectionTester.test(client, apiKey, modelId)

    // ---------- connection ----------

    private fun signature(s: AssistantSettings) = "${s.assistantName}|${s.language}|${s.customLanguage}|${s.modelId}|${s.voiceName}"

    private suspend fun connect(initial: Boolean) {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) { fail("Add your Gemini API key in Settings first."); return }
        if (!networkAvailable()) {
            val err = LiveError("No internet connection.", true)
            if (initial) fail(err.message) else scheduleReconnect(err)
            return
        }
        val s = settingsProvider()
        settings = s; configSignature = signature(s)
        diagnostics.value = diagnostics.value.copy(modelId = s.modelId)
        state.value = if (initial) VoiceSessionState.CONNECTING else VoiceSessionState.RECONNECTING
        val id = ++sessionId
        touch()
        socket = client.newWebSocket(Request.Builder().url(LiveProtocol.url(key)).build(), Listener(id, s))
    }

    private inner class Listener(val id: Int, val s: AssistantSettings) : WebSocketListener() {
        private fun post(block: () -> Unit) { scope.launch(serial) { if (id == sessionId) block() } }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (id != sessionId) return
            // The CURRENT saved name/language is injected here on every session start and reconnect.
            webSocket.send(LiveProtocol.setup(s.modelId, AssistantInstructions.build(s), s.voiceName, ActionSchema.toolDeclarations()))
        }
        override fun onMessage(webSocket: WebSocket, text: String) = post { handle(text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { val t = bytes.utf8(); post { handle(t) } }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(1000, null) }
            onDisconnected(id, code, reason, null, null)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDisconnected(id, code, reason, null, null)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            onDisconnected(id, null, null, t, response?.code)
    }

    private fun onDisconnected(id: Int, code: Int?, reason: String?, error: Throwable?, http: Int?) {
        scope.launch(serial) {
            if (id != sessionId || userStopped) return@launch
            lastClose = "code=${code ?: http ?: "-"} ${reason.orEmpty().take(80)}"
            AutomationLogger.log("Live", "Disconnected: $lastClose")
            scheduleReconnect(LiveErrorClassifier.classify(code, reason, http, error))
        }
    }

    /** Bounded retries with exponential backoff (1s, 2s, 4s, 8s). */
    private fun scheduleReconnect(err: LiveError, immediate: Boolean = false) {
        teardownMedia()
        if (!err.retryable || retry >= MAX_RETRIES) { fail(err.message); return }
        retry++; reconnects++
        reconnectAttempt.value = retry
        state.value = VoiceSessionState.RECONNECTING
        reconnectJob?.cancel()
        reconnectJob = scope.launch(serial) {
            delay(if (immediate) 0L else 1000L shl (retry - 1))
            if (!userStopped) connect(initial = false)
        }
    }

    private fun fail(message: String) {
        AutomationLogger.log("Live", "Failed: $message")
        teardown()
        errorMessage.value = message
        state.value = VoiceSessionState.ERROR
    }

    private fun teardownMedia() {
        ready = false; connected.value = false
        audioIn.stop(); audioOut.stop()
        toolJobs.values.forEach { it.cancel() }; toolJobs.clear(); toolsInFlight = 0
        socket?.let { runCatching { it.close(1000, "client closing") } }; socket = null
        sessionId++
        flushTranscripts()
    }

    private fun teardown() {
        reconnectJob?.cancel(); reconnectJob = null
        monitorJob?.cancel(); monitorJob = null
        teardownMedia()
    }

    // ---------- server messages ----------

    private fun handle(text: String) {
        val j = try { JSONObject(text) } catch (e: Exception) { return }
        if (j.has("setupComplete")) onSetupComplete()
        j.optJSONObject("serverContent")?.let { onServerContent(it) }
        j.optJSONObject("toolCall")?.let { onToolCall(it) }
        j.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { a ->
            for (i in 0 until a.length()) toolJobs[a.optString(i)]?.cancel()
        }
        if (j.has("goAway")) {
            AutomationLogger.log("Live", "Server sent goAway; reconnecting")
            scheduleReconnect(LiveError("The server is ending this session.", true), immediate = true)
        }
    }

    private fun onSetupComplete() {
        ready = true; connected.value = true
        retry = 0; reconnectAttempt.value = 0; errorMessage.value = null
        touch()
        if (!audioOut.start()) { fail("Audio playback couldn't start."); return }
        if (listeningEnabled) {
            if (startMic()) state.value = VoiceSessionState.LISTENING
        } else state.value = VoiceSessionState.IDLE
    }

    private fun startMic(): Boolean =
        audioIn.start({ onMicChunk(it) }, { msg -> scope.launch(serial) { fail(msg) } })

    private fun onMicChunk(chunk: ByteArray) {
        if (!ready) return
        // Half-duplex mode: with barge-in off, don't send mic audio while the assistant is talking.
        if (state.value == VoiceSessionState.SPEAKING && !settings.bargeIn) return
        val ws = socket ?: return
        if (ws.queueSize() > 512 * 1024) { dropped.incrementAndGet(); return }
        if (ws.send(LiveProtocol.audioChunk(Base64.encodeToString(chunk, Base64.NO_WRAP))))
            bytesSent.addAndGet(chunk.size.toLong())
    }

    private fun onServerContent(sc: JSONObject) {
        if (sc.optBoolean("interrupted")) {
            audioOut.flush(); suppressAudio = false; turnCompleteSeen = false
            flushTranscripts()
            if (listeningEnabled) state.value = VoiceSessionState.INTERRUPTED
        }
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            if (asstBuf.isNotEmpty()) flushAssistant()
            userBuf.append(it); partialUser.value = userBuf.toString()
            lastInputAt = SystemClock.elapsedRealtime(); userSpoke = true; turnCompleteSeen = false
            touch()
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            if (userBuf.isNotEmpty()) flushUser()
            asstBuf.append(it); partialAssistant.value = asstBuf.toString()
            touch()
        }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                if (!inline.optString("mimeType").startsWith("audio/pcm")) continue
                turnCompleteSeen = false; userSpoke = false
                if (suppressAudio) continue
                val pcm = try { Base64.decode(inline.optString("data"), Base64.DEFAULT) } catch (e: IllegalArgumentException) { continue }
                bytesRecv.addAndGet(pcm.size.toLong())
                if (userBuf.isNotEmpty()) flushUser()
                if (state.value != VoiceSessionState.SPEAKING) state.value = VoiceSessionState.SPEAKING
                audioOut.enqueue(pcm)
                touch()
            }
        }
        if (sc.optBoolean("turnComplete")) {
            turnCompleteSeen = true; suppressAudio = false
            val t = ++turnSeq
            // Transcription events aren't ordered relative to turnComplete, so flush shortly after.
            scope.launch(serial) { delay(700); if (t == turnSeq) flushAssistant() }
        }
    }

    // ---------- tool calls ----------

    private fun onToolCall(tc: JSONObject) {
        val calls = tc.optJSONArray("functionCalls") ?: return
        turnCompleteSeen = false; touch()
        val sid = sessionId
        for (i in 0 until calls.length()) {
            val c = calls.optJSONObject(i) ?: continue
            val id = c.optString("id"); val name = c.optString("name"); val args = c.optJSONObject("args")
            toolsInFlight++
            state.value = VoiceSessionState.EXECUTING_ACTION
            toolJobs[id] = scope.launch(Dispatchers.Default) {
                var response: JSONObject? = null
                try { response = toolHandler(name, args) }
                catch (e: CancellationException) { /* cancelled by server or stop */ }
                catch (e: Exception) { response = JSONObject().put("status", "FAILED").put("message", "The action failed.") }
                withContext(NonCancellable + serial) {
                    toolJobs.remove(id)
                    toolsInFlight = (toolsInFlight - 1).coerceAtLeast(0)
                    if (response != null && sid == sessionId && ready) {
                        socket?.send(LiveProtocol.toolResponse(id, name, response))
                        turnCompleteSeen = false
                    }
                    if (toolsInFlight == 0 && state.value == VoiceSessionState.EXECUTING_ACTION && ready)
                        state.value = VoiceSessionState.PROCESSING
                }
            }
        }
    }

    // ---------- monitor / helpers ----------

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch(serial) {
            var tick = 0
            while (isActive) {
                delay(60); tick++
                val now = SystemClock.elapsedRealtime()
                if (ready) {
                    when (state.value) {
                        VoiceSessionState.SPEAKING, VoiceSessionState.PROCESSING ->
                            if (turnCompleteSeen && toolsInFlight == 0 && !audioOut.isSpeaking) finishTurn()
                        VoiceSessionState.INTERRUPTED -> state.value = VoiceSessionState.LISTENING
                        VoiceSessionState.LISTENING ->
                            if (userSpoke && now - lastInputAt > 800) state.value = VoiceSessionState.PROCESSING
                        else -> Unit
                    }
                    if (now - lastActivity > IDLE_TIMEOUT_MS) {
                        AutomationLogger.log("Live", "Idle timeout")
                        userStopped = true
                        teardown()
                        errorMessage.value = "Session ended after 5 minutes without activity."
                        state.value = VoiceSessionState.IDLE
                        return@launch
                    }
                }
                if (tick % 8 == 0) diagnostics.value = diagnostics.value.copy(
                    sessionsStarted = sessionsStarted, reconnects = reconnects, lastClose = lastClose,
                    audioKbSent = bytesSent.get() / 1024, audioKbReceived = bytesRecv.get() / 1024,
                    droppedChunks = dropped.get())
            }
        }
    }

    /** The assistant finished its turn: keep listening (continuous) or release the mic until "Talk" is tapped. */
    private fun finishTurn() {
        turnCompleteSeen = false
        if (settings.continuousConversation) {
            state.value = VoiceSessionState.LISTENING
        } else {
            audioIn.stop(); listeningEnabled = false
            state.value = VoiceSessionState.IDLE
        }
    }

    private fun touch() { lastActivity = SystemClock.elapsedRealtime() }

    private fun flushUser() {
        val t = userBuf.toString().trim(); userBuf.clear(); partialUser.value = ""
        if (t.isNotEmpty()) onTranscript(Role.USER, t)
    }

    private fun flushAssistant() {
        val t = asstBuf.toString().trim(); asstBuf.clear(); partialAssistant.value = ""
        if (t.isNotEmpty()) onTranscript(Role.ASSISTANT, t)
    }

    private fun flushTranscripts() { flushUser(); flushAssistant() }

    companion object {
        const val MAX_RETRIES = 4
        const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
