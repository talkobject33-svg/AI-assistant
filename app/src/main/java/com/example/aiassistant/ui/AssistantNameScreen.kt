package com.example.aiassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aiassistant.assistantApp
import com.example.aiassistant.memory.AssistantNameValidator
import kotlinx.coroutines.launch

@Composable
fun AssistantNameScreen(isFirstRun: Boolean, currentName: String?, onDone: () -> Unit, onBack: (() -> Unit)?) {
    val app = LocalContext.current.assistantApp
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(currentName.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        scope.launch {
            val err = app.settingsRepository.setAssistantName(text)
            if (err == null) onDone() else error = err
        }
    }

    Screen {
        if (onBack != null) TextButton(onClick = onBack) { Text("‹ Back", color = Hud.Cyan) }
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(if (isFirstRun) "Create Your AI Assistant" else "Change Name", color = Hud.Text, fontSize = 30.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(8.dp))
            Text(
                if (isFirstRun) "Give your personal AI assistant a name." else "The new name applies to the next thing you say, and to every future session.",
                color = Hud.Muted, fontSize = 16.sp
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= AssistantNameValidator.MAX_LENGTH + 5) { text = it; error = null } },
                label = { Text("Enter assistant name") },
                placeholder = { Text("JARVIS, YESMIN, FRIDAY, ALEX, MYRA…") },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: "${text.trim().length}/${AssistantNameValidator.MAX_LENGTH}") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { submit() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (isFirstRun) "Create Assistant" else "Save name")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Saying the name does not wake the app. Wake-word detection isn't implemented; start a conversation with the button.",
                color = Hud.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
