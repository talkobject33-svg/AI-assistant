package com.example.aiassistant.voice

enum class VoiceSessionState(val label: String) {
    IDLE("Idle"),
    CONNECTING("Connecting"),
    LISTENING("Listening"),
    PROCESSING("Thinking"),
    EXECUTING_ACTION("Working on your phone"),
    SPEAKING("Speaking"),
    INTERRUPTED("Interrupted"),
    RECONNECTING("Reconnecting"),
    ERROR("Error");

    /** True while a session (or an attempt to reach one) exists. */
    val isActive: Boolean get() = this != IDLE && this != ERROR
}
