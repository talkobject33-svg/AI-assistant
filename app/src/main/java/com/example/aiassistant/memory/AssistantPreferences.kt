package com.example.aiassistant.memory

enum class VoiceLanguage(val label: String) {
    ENGLISH("English"),
    HINDI("Hindi"),
    HINGLISH("Hinglish"),
    OTHER("Other")
}

/** Assistant identity + user preferences. Conversation text and long-term memory are stored separately. */
data class AssistantSettings(
    val assistantName: String? = null,
    val language: VoiceLanguage = VoiceLanguage.ENGLISH,
    val customLanguage: String = "",
    val modelId: String = DEFAULT_MODEL,
    val voiceName: String = "Kore",
    val continuousConversation: Boolean = true,
    val bargeIn: Boolean = true,
    val saveHistory: Boolean = true
) {
    companion object {
        /** Configurable in Settings. Verify against the current Gemini Live docs; model IDs change. */
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
        val VOICES = listOf("Kore", "Puck", "Charon", "Fenrir", "Aoede")
    }
}

sealed interface NameValidation {
    data class Valid(val name: String) : NameValidation
    data class Invalid(val reason: String) : NameValidation
}

object AssistantNameValidator {
    const val MAX_LENGTH = 24
    private val allowed = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} '\\-.]*$")

    fun validate(raw: String): NameValidation {
        val cleaned = raw.trim().replace(Regex("\\s+"), " ")
        return when {
            cleaned.isEmpty() -> NameValidation.Invalid("Enter a name for your assistant.")
            cleaned.length > MAX_LENGTH -> NameValidation.Invalid("Use $MAX_LENGTH characters or fewer.")
            !allowed.matches(cleaned) ->
                NameValidation.Invalid("Use letters, numbers, spaces, hyphens, apostrophes or periods only.")
            else -> NameValidation.Valid(cleaned)
        }
    }
}
