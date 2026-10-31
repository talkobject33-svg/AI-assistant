package com.example.aiassistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.aiassistant.assistantApp

@Composable
fun AssistantNavHost() {
    val context = LocalContext.current
    val app = context.assistantApp
    val loaded by app.settingsRepository.settings.collectAsStateWithLifecycle()
    val first = loaded
    if (first == null) { Box(Modifier.fillMaxSize().background(Hud.Bg)); return }

    val nav = rememberNavController()
    val start = remember { if (first.assistantName == null) "setup" else "home" }
    var micDenied by remember { mutableStateOf(false) }

    // Permissions are requested only when the user starts a conversation.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (app.permissions.hasMicrophone()) { app.runtime.startSession(); nav.navigate("conversation") } else micDenied = true
    }
    val startConversation = {
        if (app.permissions.hasMicrophone()) { app.runtime.startSession(); nav.navigate("conversation") }
        else launcher.launch(app.permissions.requiredRuntimePermissions())
    }

    NavHost(nav, startDestination = start) {
        composable("setup") {
            AssistantNameScreen(isFirstRun = true, currentName = null, onBack = null,
                onDone = { nav.navigate("home") { popUpTo("setup") { inclusive = true } } })
        }
        composable("rename") {
            val s by app.settingsRepository.settings.collectAsStateWithLifecycle()
            AssistantNameScreen(isFirstRun = false, currentName = s?.assistantName,
                onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
        }
        composable("home") {
            HomeScreen(onStart = startConversation, onOpenConversation = { nav.navigate("conversation") },
                onSettings = { nav.navigate("settings") }, onAccessibility = { nav.navigate("accessibility") })
        }
        composable("conversation") { ConversationScreen(onBack = { nav.popBackStack() }, onStart = startConversation) }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() }, onRename = { nav.navigate("rename") }, onAccessibility = { nav.navigate("accessibility") })
        }
        composable("accessibility") { AccessibilitySetupScreen(onBack = { nav.popBackStack() }) }
    }

    if (micDenied) AlertDialog(
        onDismissRequest = { micDenied = false },
        title = { Text("Microphone needed") },
        text = { Text("Voice conversation needs the microphone. You can allow it in the app's permission settings. Typed chat still works once connected.") },
        confirmButton = {
            TextButton(onClick = { micDenied = false; context.startActivity(app.permissions.appDetailsIntent()) }) { Text("Open settings") }
        },
        dismissButton = { TextButton(onClick = { micDenied = false }) { Text("Not now") } }
    )
}
