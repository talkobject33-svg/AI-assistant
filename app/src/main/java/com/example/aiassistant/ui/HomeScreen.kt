package com.example.aiassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiassistant.assistantApp
import com.example.aiassistant.automation.AccessibilityAutomationService
import com.example.aiassistant.memory.Role
import com.example.aiassistant.voice.GeminiLiveManager
import com.example.aiassistant.voice.VoiceSessionState
import java.util.Calendar

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when { h < 5 -> "Good evening"; h < 12 -> "Good morning"; h < 17 -> "Good afternoon"; else -> "Good evening" }
}

@Composable
fun HomeScreen(
    onStart: () -> Unit,
    onOpenConversation: () -> Unit,
    onSettings: () -> Unit,
    onAccessibility: () -> Unit
) {
    val app = LocalContext.current.assistantApp
    val rt = app.runtime
    val settings by app.settingsRepository.settings.collectAsStateWithLifecycle()
    val state by rt.manager.state.collectAsStateWithLifecycle()
    val connected by rt.manager.connected.collectAsStateWithLifecycle()
    val attempt by rt.manager.reconnectAttempt.collectAsStateWithLifecycle()
    val error by rt.manager.errorMessage.collectAsStateWithLifecycle()
    val level by rt.manager.audioLevel.collectAsStateWithLifecycle(0f)
    val a11y by AccessibilityAutomationService.connected.collectAsStateWithLifecycle()
    val paused by rt.router.paused.collectAsStateWithLifecycle()
    val messages by app.conversations.messages.collectAsStateWithLifecycle()

    val name = settings?.assistantName ?: "Assistant"
    val sessionOpen = connected || state.isActive

    val liveLabel = when {
        state == VoiceSessionState.ERROR -> "Error" to Hud.Red
        state == VoiceSessionState.CONNECTING -> "Connecting…" to Hud.Amber
        state == VoiceSessionState.RECONNECTING -> "Reconnecting ($attempt/${GeminiLiveManager.MAX_RETRIES})" to Hud.Amber
        connected -> "Connected" to Hud.Green
        else -> "Not connected" to Hud.Muted
    }
    val phoneLabel = when {
        !a11y -> "Disabled" to Hud.Muted
        paused -> "Paused" to Hud.Amber
        else -> "Enabled" to Hud.Green
    }

    Screen {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Hud.Muted) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${greeting()}. I'm $name.", color = Hud.Text, fontSize = 26.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(14.dp))
            StatusPill("Gemini Live", liveLabel.first, liveLabel.second)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.clickable { onAccessibility() }) { StatusPill("Phone Control", phoneLabel.first, phoneLabel.second) }
            Spacer(Modifier.height(8.dp))
            VoiceOrb(state, level)
            Text(if (state == VoiceSessionState.IDLE && connected) "Tap to talk" else state.label, color = stateColor(state), fontSize = 16.sp)
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Hud.Red, fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp))
            if (!sessionOpen) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Start Conversation") }
            } else {
                Button(onClick = onOpenConversation, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Open Conversation") }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { rt.manager.stopSpeaking() },
                        enabled = state == VoiceSessionState.SPEAKING, modifier = Modifier.weight(1f)) { Text("Stop Speaking") }
                    Button(onClick = { rt.endSession() }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Hud.Red, contentColor = Hud.Bg)) { Text("End Conversation") }
                }
            }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Hud.Surface).padding(14.dp)) {
                Text("Recent conversation", color = Hud.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                if (messages.isEmpty()) Text("Nothing yet. Say hello.", color = Hud.Muted, fontSize = 14.sp)
                messages.takeLast(3).forEach {
                    Text((if (it.role == Role.USER) "You: " else "$name: ") + it.text, color = Hud.Text, fontSize = 14.sp,
                        maxLines = 2, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
