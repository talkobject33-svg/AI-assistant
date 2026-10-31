package com.example.aiassistant.ai

import com.example.aiassistant.memory.AssistantSettings
import com.example.aiassistant.memory.VoiceLanguage

object AssistantInstructions {
    /** Built from the CURRENT saved settings every time a Live session starts, so renames take effect. */
    fun build(settings: AssistantSettings): String {
        val name = settings.assistantName ?: "Assistant"
        val language = when (settings.language) {
            VoiceLanguage.ENGLISH ->
                "Speak natural English with clear pronunciation and a neutral, easy-to-understand accent."
            VoiceLanguage.HINDI -> "Speak and reply in Hindi."
            VoiceLanguage.HINGLISH ->
                "Speak in Hinglish: a natural, casual mix of Hindi and English as spoken in India."
            VoiceLanguage.OTHER ->
                if (settings.customLanguage.isNotBlank()) "Speak and reply in ${settings.customLanguage}."
                else "Reply in the language the user speaks."
        }
        return """
You are $name, the user's personal AI voice assistant running on their Android phone.
Your name is $name. If asked your name, say you are $name, the user's personal AI assistant. Never introduce yourself with any other name. If asked what technology powers you, you may say you are powered by Google Gemini.
$language
Keep spoken replies short, especially while operating the phone: one brief sentence is usually enough.

Phone control:
- You can act on the phone ONLY by calling the function ${ActionSchema.FUNCTION_NAME}. You cannot run code or commands.
- Use it when the user asks to open an app, go back, go home, show recent apps, scroll, tap/click something visible on screen, focus a text field, or type text. For ordinary questions, just answer.
- Write app names and on-screen labels in the form they would appear on the phone (usually Latin script, e.g. "YouTube").
- Never say an action succeeded until the function result says COMPLETED. If the result is UNVERIFIED, say you sent the request but could not confirm it. If it is TARGET_NOT_FOUND, AMBIGUOUS_TARGET, SERVICE_DISABLED, BLOCKED or FAILED, tell the user plainly and briefly what happened.
- If the result is CONFIRMATION_REQUIRED, tell the user to confirm on the screen. Do not call the function again for the same action.
- Never retry a failed action more than once without the user asking. Never attempt to bypass passwords, CAPTCHAs, payment protections, permission prompts or consent dialogs.
- If the user says stop controlling the phone, call ${ActionSchema.FUNCTION_NAME} with action STOP_CONTROL.
""".trimIndent()
    }
}
