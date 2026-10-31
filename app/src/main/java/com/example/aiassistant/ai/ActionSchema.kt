package com.example.aiassistant.ai

import org.json.JSONArray
import org.json.JSONObject

/** The complete allowlist of things the AI may ask the phone layer to do. */
enum class ActionType {
    OPEN_APP, GO_BACK, GO_HOME, SHOW_RECENTS, SCROLL, CLICK_ELEMENT, FOCUS_FIELD, TYPE_TEXT,
    STOP_CONTROL, STOP_SPEAKING
}

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

data class PhoneAction(
    val type: ActionType,
    val target: String? = null,
    val direction: ScrollDirection? = null,
    val durationMs: Int = 500,
    val text: String? = null,
    /** The model may ask for MORE caution; it can never remove a local confirmation requirement. */
    val modelRequestsConfirmation: Boolean = false
) {
    fun describe(): String = when (type) {
        ActionType.OPEN_APP -> "Open $target"
        ActionType.GO_BACK -> "Go back"
        ActionType.GO_HOME -> "Go to the home screen"
        ActionType.SHOW_RECENTS -> "Show recent apps"
        ActionType.SCROLL -> "Scroll ${direction?.name?.lowercase()}"
        ActionType.CLICK_ELEMENT -> "Tap \"$target\""
        ActionType.FOCUS_FIELD -> "Select the field \"$target\""
        ActionType.TYPE_TEXT -> "Type text (${text?.length ?: 0} characters)"
        ActionType.STOP_CONTROL -> "Stop controlling the phone"
        ActionType.STOP_SPEAKING -> "Stop speaking"
    }
}

enum class ExecutionStatus {
    COMPLETED, UNVERIFIED, FAILED, TARGET_NOT_FOUND, AMBIGUOUS_TARGET,
    SERVICE_DISABLED, PERMISSION_REQUIRED, UNSUPPORTED, CONFIRMATION_REQUIRED, BLOCKED
}

data class ActionResult(val status: ExecutionStatus, val message: String) {
    fun toJson(): JSONObject = JSONObject().put("status", status.name).put("message", message)
}

object ActionSchema {
    const val FUNCTION_NAME = "phone_action"

    /** Function declaration sent in the Live session setup. */
    fun toolDeclarations(): JSONArray {
        val props = JSONObject()
            .put("action", JSONObject().put("type", "STRING")
                .put("enum", JSONArray(ActionType.values().map { it.name }))
                .put("description", "The phone action to perform."))
            .put("target", JSONObject().put("type", "STRING")
                .put("description", "OPEN_APP: the app name. CLICK_ELEMENT / FOCUS_FIELD: the visible label or description of the element."))
            .put("direction", JSONObject().put("type", "STRING")
                .put("enum", JSONArray(ScrollDirection.values().map { it.name }))
                .put("description", "SCROLL only. DOWN reveals content further down the page."))
            .put("duration_ms", JSONObject().put("type", "INTEGER")
                .put("description", "SCROLL only, 200-3000. Longer means slower."))
            .put("text", JSONObject().put("type", "STRING")
                .put("description", "TYPE_TEXT only: the text to enter in the currently selected field."))
            .put("requires_confirmation", JSONObject().put("type", "BOOLEAN")
                .put("description", "Set true if the action could be irreversible or sensitive."))
        val fn = JSONObject()
            .put("name", FUNCTION_NAME)
            .put("description", "Perform one action on the user's Android phone. The phone validates every request and may refuse it.")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", props)
                .put("required", JSONArray(listOf("action"))))
        return JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(fn)))
    }
}
