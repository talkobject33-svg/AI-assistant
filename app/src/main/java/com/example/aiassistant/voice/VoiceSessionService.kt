package com.example.aiassistant.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.aiassistant.MainActivity
import com.example.aiassistant.R
import com.example.aiassistant.assistantApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive as a visible microphone foreground service while a voice session runs
 * (Android only allows microphone capture to continue in the background this way).
 * It holds no logic of its own: the session lives in AssistantRuntime.
 */
class VoiceSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            assistantApp.runtime.endSession(); stopSelf(); return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(assistantApp.runtime.assistantName.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        scope.launch {
            // Notifications follow a renamed assistant immediately.
            assistantApp.runtime.assistantName.collect { name ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(name))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        assistantApp.runtime.endSession() // never leave the microphone running
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Voice session", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(name: String?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, VoiceSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${name ?: "Assistant"} is listening")
            .setContentText("Voice session active. Tap to open, or stop below.")
            .setContentIntent(open)
            .addAction(0, "End session", stop)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "voice_session"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.aiassistant.STOP"
    }
}
