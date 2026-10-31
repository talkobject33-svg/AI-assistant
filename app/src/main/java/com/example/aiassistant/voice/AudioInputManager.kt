package com.example.aiassistant.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** Captures 16 kHz, 16-bit, mono little-endian PCM (the Live API input format). */
class AudioInputManager(private val scope: CoroutineScope) {
    private var job: Job? = null
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    val isRunning: Boolean get() = job?.isActive == true

    @SuppressLint("MissingPermission")
    fun start(onChunk: (ByteArray) -> Unit, onError: (String) -> Unit): Boolean {
        if (isRunning) return true
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { onError("This device can't capture 16 kHz audio."); return false }
        val chunkBytes = SAMPLE_RATE * 2 / 25 // 40 ms
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunkBytes * 4))
        } catch (e: SecurityException) { onError("Microphone permission is missing."); return false }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release(); onError("The microphone could not be opened. Another app may be using it."); return false
        }
        // Echo cancellation / noise suppression reduce the assistant hearing its own voice.
        val sid = record.audioSessionId
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sid)?.also { it.enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sid)?.also { it.enabled = true } else null
        val agc = if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sid)?.also { it.enabled = true } else null
        try { record.startRecording() } catch (e: IllegalStateException) {
            record.release(); onError("Couldn't start recording."); return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release(); onError("Couldn't start recording."); return false
        }
        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(chunkBytes)
            try {
                while (isActive) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) { _level.value = rms(buf, n); onChunk(buf.copyOf(n)) }
                    else if (n < 0) { onError("Audio capture failed (code $n)."); break }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                aec?.release(); ns?.release(); agc?.release()
                _level.value = 0f
            }
        }
        return true
    }

    /** Releases the microphone (the capture loop releases the hardware as it exits). */
    fun stop() { job?.cancel(); job = null; _level.value = 0f }

    private fun rms(b: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)
            sum += (s.toShort().toDouble() / 32768.0).let { it * it }
            i += 2
        }
        return (sqrt(sum / (n / 2).coerceAtLeast(1)) * 4).toFloat().coerceIn(0f, 1f)
    }

    companion object { const val SAMPLE_RATE = 16_000 }
}
