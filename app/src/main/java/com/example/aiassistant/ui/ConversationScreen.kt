package com.example.aiassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiassistant.assistantApp
import com.example.aiassistant.memory.Role
import com.example.aiassistant.voice.GeminiLiveManager
import com.example.aiassistant.voice.VoiceSessionState
import kotlinx.coroutines.delay

@Composable
private fun Bubble(text: String, mine: Boolean, partial: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                .background(if (mine) Color(0xFF12315F) else Hud.Surface).padding(horizontal = 12.dp, vertical = 8.dp)
        ) { Text(text, color = if (partial) Hud.Muted else Hud.Text, fontSize = 15.sp) }
    }
}

@Composable
fun ConversationScreen(onBack: () -> Unit, onStart: () -> Unit) {
    val app = LocalContext.current.assistantApp
    val rt = app.runtime
    val settings by app.settingsRepository.settings.collectAsStateWithLifecycle()
    val state by rt.manager.state.collectAsStateWithLifecycle()
    val connected by rt.manager.connected.collectAsStateWithLifecycle()
    val attempt by rt.manager.reconnectAttempt.collectAsStateWithLifecycle()
    val error by rt.manager.errorMessage.collectAsStateWithLifecycle()
    val level by rt.manager.audioLevel.collectAsStateWithLifecycle(0f)
    val partialUser by rt.manager.partialUser.collectAsStateWithLifecycle()
    val partialAssistant by rt.manager.partialAssistant.collectAsStateWithLifecycle()
    val messages by app.conversations.messages.collectAsStateWithLifecycle()
    val status by rt.router.status.collectAsStateWithLifecycle()
    val pending by rt.router.pending.collectAsStateWithLifecycle()
    val startedAt by rt.callStartedAt.collectAsStateWithLifecycle()
    val name = settings?.assistantName ?: "Assistant"
    val sessionOpen = connected || state.isActive

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) { while (startedAt != null) { now = System.currentTimeMillis(); delay(1000) } }
    val seconds = startedAt?.let { ((now - it) / 1000).coerceAtLeast(0) } ?: 0L

    val listState = rememberLazyListState()
    val extra = (if (partialUser.isNotEmpty()) 1 else 0) + (if (partialAssistant.isNotEmpty()) 1 else 0)
    LaunchedEffect(messages.size, partialUser, partialAssistant) {
        val total = messages.size + extra
        if (total > 0) listState.animateScrollToItem(total - 1)
    }
    var input by remember { mutableStateOf("") }

    Screen {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Home", color = Hud.Cyan) }
            Column(Modifier.weight(1f)) {
                Text(name, color = Hud.Text, fontSize = 18.sp)
                val label = if (state == VoiceSessionState.RECONNECTING) "Reconnecting ($attempt/${GeminiLiveManager.MAX_RETRIES})"
                else if (state == VoiceSessionState.IDLE && connected) "Paused. Tap Talk" else state.label
                Text(label, color = stateColor(state), fontSize = 13.sp)
            }
            if (sessionOpen) Text("%02d:%02d".format(seconds / 60, seconds % 60), color = Hud.Muted, fontSize = 14.sp)
        }
        if (state == VoiceSessionState.LISTENING || state == VoiceSessionState.SPEAKING) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { WaveBars(level, stateColor(state)) }
        }
        error?.let { Text(it, color = Hud.Red, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp)) }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { Bubble(it.text, it.role == Role.USER) }
            if (partialUser.isNotEmpty()) item { Bubble(partialUser, true, partial = true) }
            if (partialAssistant.isNotEmpty()) item { Bubble(partialAssistant, false, partial = true) }
        }

        status?.let { Text(it, color = Hud.Cyan, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        pending?.let { p ->
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(Hud.Surface).padding(12.dp)) {
                Text("Confirm: ${p.description}", color = Hud.Amber, fontSize = 15.sp)
                Text(p.reason, color = Hud.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { rt.confirmPending() }) { Text("Confirm") }
                    OutlinedButton(onClick = { rt.cancelPending() }) { Text("Cancel") }
                }
            }
        }

        if (sessionOpen) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Type a message") })
                TextButton(enabled = input.isNotBlank() && connected, onClick = { rt.manager.sendText(input.trim()); input = "" }) { Text("Send") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!sessionOpen) {
                Button(onClick = onStart, modifier = Modifier.weight(1f).height(52.dp)) { Text("Start Conversation") }
            } else {
                if (state == VoiceSessionState.IDLE && connected) {
                    Button(onClick = { rt.manager.resumeListening() }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Talk") }
                } else {
                    OutlinedButton(onClick = { rt.manager.stopSpeaking() }, enabled = state == VoiceSessionState.SPEAKING,
                        modifier = Modifier.weight(1f).height(52.dp)) { Text("Interrupt") }
                }
                Button(onClick = { rt.endSession() }, modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Hud.Red, contentColor = Hud.Bg)) { Text("End Call") }
            }
        }
    }
}
