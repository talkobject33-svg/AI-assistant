package com.example.aiassistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiassistant.ai.ActionType
import com.example.aiassistant.ai.PhoneAction
import com.example.aiassistant.assistantApp
import com.example.aiassistant.automation.AccessibilityAutomationService
import com.example.aiassistant.automation.ScreenNodeInspector
import kotlinx.coroutines.launch

@Composable
fun AccessibilitySetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.assistantApp
    val scope = rememberCoroutineScope()
    val connected by AccessibilityAutomationService.connected.collectAsStateWithLifecycle()
    val paused by app.runtime.router.paused.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(app.permissions.canDrawOverlays()) }
    var testResult by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { overlay = app.permissions.canDrawOverlays() }

    Screen {
        TextButton(onClick = onBack) { Text("‹ Back", color = Hud.Cyan) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Phone control", color = Hud.Text, fontSize = 26.sp)
            Spacer(Modifier.height(8.dp))
            StatusPill("Accessibility service", if (connected) "Enabled" else "Disabled", if (connected) Hud.Green else Hud.Red)
            Spacer(Modifier.height(12.dp))
            Text(
                "Phone control uses an Android accessibility service so the assistant can tap, scroll and type on screen when you ask. " +
                    "It acts only on your spoken or typed requests, it can't act on its own, and it never types into password fields. " +
                    "Sending, buying, deleting, sharing and consent buttons always need your confirmation on screen. " +
                    "Chat and voice work without it.",
                color = Hud.Muted, fontSize = 15.sp
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { context.startActivity(app.permissions.accessibilitySettingsIntent()) }, Modifier.fillMaxWidth()) {
                Text("Open accessibility settings")
            }
            Spacer(Modifier.height(8.dp))
            Text("You must switch the service on yourself. The app cannot enable it for you.", color = Hud.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Text("Can't switch it on? (Android 13 and later, installed from an APK)", color = Hud.Text, fontSize = 15.sp)
            Text(
                "Android greys the toggle out for apps installed outside the Play Store. Open App info, tap the ⋮ menu, choose " +
                    "\"Allow restricted settings\", then return to accessibility settings.",
                color = Hud.Muted, fontSize = 13.sp
            )
            OutlinedButton(onClick = { context.startActivity(app.permissions.appDetailsIntent()) }, Modifier.fillMaxWidth()) { Text("Open App info") }

            Spacer(Modifier.height(20.dp))
            Text("Test", color = Hud.Text, fontSize = 18.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = {
                    val root = AccessibilityAutomationService.instance?.rootInActiveWindow
                    testResult = if (root == null) "No readable window." else
                        "Read ${ScreenNodeInspector.collect(root).size} visible elements in ${root.packageName}."
                }) { Text("Inspect screen") }
                OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = {
                    scope.launch {
                        val r = app.runtime.router.executeAction(PhoneAction(ActionType.GO_HOME))
                        testResult = "${r.status}: ${r.message}"
                    }
                }) { Text("Go to home") }
            }
            testResult?.let { Text(it, color = Hud.Cyan, fontSize = 14.sp) }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Phone control active", color = Hud.Text, fontSize = 16.sp)
                    Text("Turn off to stop all taps, scrolls and app launches without disabling the service.", color = Hud.Muted, fontSize = 13.sp)
                }
                Switch(checked = !paused, onCheckedChange = { app.runtime.router.setPaused(!it) })
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(enabled = connected, onClick = { AccessibilityAutomationService.instance?.disableSelf() }, modifier = Modifier.fillMaxWidth()) {
                Text("Disable the accessibility service")
            }

            Spacer(Modifier.height(20.dp))
            Text("Optional: reliable app launching", color = Hud.Text, fontSize = 16.sp)
            Text(
                "Android can block apps from opening other apps while they run in the background. Allowing \"Display over other apps\" is a documented exemption. " +
                    "Status: ${if (overlay) "allowed" else "not allowed"}.",
                color = Hud.Muted, fontSize = 13.sp
            )
            OutlinedButton(onClick = { context.startActivity(app.permissions.overlaySettingsIntent()) }, Modifier.fillMaxWidth()) {
                Text("Open overlay permission")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
