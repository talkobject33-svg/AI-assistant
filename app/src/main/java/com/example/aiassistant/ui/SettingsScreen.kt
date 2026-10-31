package com.example.aiassistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiassistant.assistantApp
import com.example.aiassistant.automation.AccessibilityAutomationService
import com.example.aiassistant.automation.AutomationLogger
import com.example.aiassistant.memory.AssistantSettings
import com.example.aiassistant.memory.VoiceLanguage
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(title, color = Hud.Cyan, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Hud.Surface).padding(14.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Hud.Text, fontSize = 15.sp)
            Text(detail, color = Hud.Muted, fontSize = 12.sp)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onRename: () -> Unit, onAccessibility: () -> Unit) {
    val context = LocalContext.current
    val app = context.assistantApp
    val repo = app.settingsRepository
    val scope = rememberCoroutineScope()
    val settings by repo.settings.collectAsStateWithLifecycle()
    val s = settings ?: AssistantSettings()
    val hasKey by app.keyStore.hasKey.collectAsStateWithLifecycle()
    val a11y by AccessibilityAutomationService.connected.collectAsStateWithLifecycle()
    val messages by app.conversations.messages.collectAsStateWithLifecycle()
    val diag by app.runtime.manager.diagnostics.collectAsStateWithLifecycle()
    val log by AutomationLogger.entries.collectAsStateWithLifecycle()

    var mic by remember { mutableStateOf(app.permissions.hasMicrophone()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { mic = app.permissions.hasMicrophone() }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { mic = it }

    var keyInput by remember { mutableStateOf("") }
    var keyMessage by remember { mutableStateOf<String?>(null) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var modelInput by remember(s.modelId) { mutableStateOf(s.modelId) }
    var modelMessage by remember { mutableStateOf<String?>(null) }
    var customLang by remember(s.customLanguage) { mutableStateOf(s.customLanguage) }
    var confirmClear by remember { mutableStateOf(false) }

    Screen {
        TextButton(onClick = onBack) { Text("‹ Back", color = Hud.Cyan) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Settings", color = Hud.Text, fontSize = 26.sp)

            Section("Assistant") {
                Text("Name: ${s.assistantName ?: "not set"}", color = Hud.Text, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRename) { Text("Change Name") }
            }

            Section("AI provider") {
                Text("Google Gemini (Live API)", color = Hud.Text, fontSize = 16.sp)
                Text("The assistant uses your chosen name; Gemini is the model behind it.", color = Hud.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text(if (hasKey) "API key: saved (hidden)" else "API key: not set", color = if (hasKey) Hud.Green else Hud.Amber, fontSize = 14.sp)
                OutlinedTextField(keyInput, { keyInput = it; keyMessage = null }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Gemini API key") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { keyMessage = app.keyStore.set(keyInput) ?: "Saved.".also { keyInput = "" } }) { Text("Save key") }
                    OutlinedButton(enabled = hasKey, onClick = { app.keyStore.clear(); keyMessage = "Key removed." }) { Text("Remove") }
                }
                keyMessage?.let { Text(it, color = Hud.Cyan, fontSize = 13.sp) }
                Spacer(Modifier.height(10.dp))
                Text("Active model: ${s.modelId}", color = Hud.Text, fontSize = 14.sp)
                OutlinedTextField(modelInput, { modelInput = it; modelMessage = null }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Live model ID") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { modelMessage = repo.setModelId(modelInput) ?: "Model saved." } }) { Text("Save model") }
                    OutlinedButton(onClick = { modelInput = AssistantSettings.DEFAULT_MODEL }) { Text("Use default") }
                }
                modelMessage?.let { Text(it, color = Hud.Cyan, fontSize = 13.sp) }
                Spacer(Modifier.height(10.dp))
                Button(enabled = hasKey && !testing, onClick = {
                    testing = true; testMessage = "Testing…"
                    scope.launch {
                        val key = app.keyStore.get().orEmpty()
                        testMessage = app.runtime.manager.testConnection(key, s.modelId).message
                        testing = false
                    }
                }) { Text("Test connection") }
                testMessage?.let { Text(it, color = Hud.Cyan, fontSize = 13.sp) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "A pasted key is for development only: it lives encrypted on this phone, and anyone who extracts it could use your quota. " +
                        "For a released app, have your own server issue short-lived Live API tokens instead of shipping a permanent key. " +
                        "Live API use is metered and may need billing; quota errors are reported here.",
                    color = Hud.Muted, fontSize = 12.sp
                )
            }

            Section("Voice") {
                Text("Language", color = Hud.Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VoiceLanguage.values().forEach { l ->
                        FilterChip(selected = s.language == l, onClick = { scope.launch { repo.setLanguage(l) } }, label = { Text(l.label) })
                    }
                }
                if (s.language == VoiceLanguage.OTHER) {
                    OutlinedTextField(customLang, { customLang = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Language name (e.g. Bengali)") })
                    TextButton(onClick = { scope.launch { repo.setCustomLanguage(customLang) } }) { Text("Save language") }
                    Text("Works only if the selected model supports that language.", color = Hud.Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("Voice", color = Hud.Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistantSettings.VOICES.take(3).forEach { v ->
                        FilterChip(selected = s.voiceName == v, onClick = { scope.launch { repo.setVoice(v) } }, label = { Text(v) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistantSettings.VOICES.drop(3).forEach { v ->
                        FilterChip(selected = s.voiceName == v, onClick = { scope.launch { repo.setVoice(v) } }, label = { Text(v) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("Continuous conversation", "Keep listening after each answer", s.continuousConversation) { scope.launch { repo.setContinuous(it) } }
                ToggleRow("Allow interrupting", "Off = mic muted while the assistant speaks (best on speakerphone)", s.bargeIn) { scope.launch { repo.setBargeIn(it) } }
                Text("Changing name, language, voice or model restarts an active session so the new setup applies.", color = Hud.Muted, fontSize = 12.sp)
            }

            Section("Permissions") {
                Text("Microphone: ${if (mic) "granted" else "not granted"}", color = if (mic) Hud.Green else Hud.Amber, fontSize = 15.sp)
                if (!mic) OutlinedButton(onClick = { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }) { Text("Grant microphone") }
                Spacer(Modifier.height(8.dp))
                Text("Accessibility (phone control): ${if (a11y) "enabled" else "disabled"}", color = if (a11y) Hud.Green else Hud.Amber, fontSize = 15.sp)
                OutlinedButton(onClick = onAccessibility) { Text("Set up phone control") }
            }

            Section("Local data") {
                Text("Assistant name: ${s.assistantName ?: "-"}", color = Hud.Text, fontSize = 14.sp)
                Text("Language: ${s.language.label}  Voice: ${s.voiceName}", color = Hud.Text, fontSize = 14.sp)
                Text("Stored messages: ${messages.size}", color = Hud.Text, fontSize = 14.sp)
                messages.lastOrNull()?.let { Text("Latest: ${DateFormat.getDateTimeInstance().format(Date(it.timestamp))}", color = Hud.Muted, fontSize = 12.sp) }
                Text("Long-term memory: not implemented. Only the name, settings and this transcript are stored.", color = Hud.Muted, fontSize = 12.sp)
                ToggleRow("Save conversation history", "Off = transcript stays in memory only and is deleted from storage", s.saveHistory) { scope.launch { repo.setSaveHistory(it) } }
                OutlinedButton(onClick = { confirmClear = true }) { Text("Clear conversation history") }
            }

            Section("Privacy") {
                Text(
                    "While a session is active, microphone audio and your typed messages are streamed to Google's Gemini service, which returns speech. " +
                        "Screen content is never sent automatically; the assistant only gets short results like \"Tapped Search\". " +
                        "Your name and settings stay on this phone, except the name is included in the instructions sent to Gemini.",
                    color = Hud.Muted, fontSize = 13.sp
                )
            }

            Section("Diagnostics") {
                Text("Model: ${diag.modelId.ifBlank { "-" }}  Sessions: ${diag.sessionsStarted}  Reconnects: ${diag.reconnects}", color = Hud.Text, fontSize = 13.sp)
                Text("Audio sent: ${diag.audioKbSent} KB  received: ${diag.audioKbReceived} KB  dropped: ${diag.droppedChunks}", color = Hud.Text, fontSize = 13.sp)
                if (diag.lastClose.isNotBlank()) Text("Last close: ${diag.lastClose}", color = Hud.Muted, fontSize = 12.sp)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Hud.Line)
                log.takeLast(12).reversed().forEach {
                    Text("${it.tag}: ${it.message}", color = Hud.Muted, fontSize = 11.sp, maxLines = 2)
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(onClick = { app.runtime.stopAll() }, Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Hud.Red, contentColor = Hud.Bg)) { Text("Stop all assistant activity") }
            Text("Ends the voice session, releases the microphone and pauses phone control.", color = Hud.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(28.dp))
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear conversation history?") },
        text = { Text("This deletes the saved transcript from this phone. It can't be undone.") },
        confirmButton = { TextButton(onClick = { app.conversations.clear(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )
}
