package com.example.aiassistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** Plays 24 kHz, 16-bit, mono little-endian PCM (the Live API output format). */
class AudioOutputManager(context: Context, private val scope: CoroutineScope) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var track: AudioTrack? = null
    private var queue = Channel<ByteArray>(Channel.UNLIMITED)
    private var writer: Job? = null
    private var focus: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private val pending = AtomicInteger(0)
    @Volatile private var playUntil = 0L

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    fun start(): Boolean {
        if (track != null) return true
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, SAMPLE_RATE)) // ~500 ms
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (e: Exception) { return false }
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); return false }
        routeToCommunicationDevice()
        t.play()
        track = t
        queue = Channel(Channel.UNLIMITED)
        val q = queue
        writer = scope.launch(Dispatchers.IO) {
            for (chunk in q) {
                val tr = track ?: break
                runCatching { tr.write(chunk, 0, chunk.size) }
                val now = SystemClock.elapsedRealtime()
                playUntil = maxOf(now, playUntil) + chunk.size * 1000L / (SAMPLE_RATE * 2)
                _level.value = rms(chunk)
                pending.decrementAndGet()
            }
        }
        return true
    }

    fun enqueue(pcm: ByteArray) {
        if (track == null) return
        pending.incrementAndGet()
        queue.trySend(pcm)
    }

    val isSpeaking: Boolean get() = pending.get() > 0 || SystemClock.elapsedRealtime() < playUntil

    /** Immediately discards everything queued and silences playback (barge-in / Stop Speaking). */
    fun flush() {
        while (queue.tryReceive().isSuccess) pending.decrementAndGet()
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
        playUntil = 0L
        _level.value = 0f
    }

    fun stop() {
        writer?.cancel(); writer = null
        queue.close()
        track?.let { runCatching { it.pause(); it.flush(); it.stop() }; it.release() }
        track = null
        pending.set(0); playUntil = 0L; _level.value = 0f
        restoreRouting()
    }

    @Suppress("DEPRECATION")
    private fun routeToCommunicationDevice() {
        val am = audioManager ?: return
        previousMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
            .build().also { am.requestAudioFocus(it) }
        if (Build.VERSION.SDK_INT >= 31) {
            val preferredTypes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
            val devices = am.availableCommunicationDevices
            (devices.firstOrNull { it.type in preferredTypes }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })
                ?.let { am.setCommunicationDevice(it) }
        } else {
            am.isSpeakerphoneOn = true
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreRouting() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
        else am.isSpeakerphoneOn = false
        focus?.let { am.abandonAudioFocusRequest(it) }; focus = null
        am.mode = previousMode
    }

    private fun rms(b: ByteArray): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < b.size) {
            val s = ((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort().toDouble() / 32768.0
            sum += s * s; i += 2
        }
        return (sqrt(sum / (b.size / 2).coerceAtLeast(1)) * 4).toFloat().coerceIn(0f, 1f)
    }

    companion object { const val SAMPLE_RATE = 24_000 }
}
