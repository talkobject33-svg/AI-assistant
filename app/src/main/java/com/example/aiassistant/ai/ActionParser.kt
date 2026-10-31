package com.example.aiassistant.ai

import org.json.JSONObject

sealed interface ParseResult {
    data class Valid(val action: PhoneAction) : ParseResult
    data class Invalid(val reason: String) : ParseResult
}

/** Strict parser: unknown functions, unknown keys, wrong types or out-of-range values are all rejected. */
object ActionParser {
    private val allowedKeys = setOf("action", "target", "direction", "duration_ms", "text", "requires_confirmation")
    private const val MAX_TARGET = 80
    private const val MAX_TEXT = 500

    fun parse(functionName: String, args: JSONObject?): ParseResult {
        if (functionName != ActionSchema.FUNCTION_NAME) return ParseResult.Invalid("Unknown function.")
        if (args == null) return ParseResult.Invalid("Missing arguments.")
        val keys = args.keys().asSequence().toList()
        keys.firstOrNull { it !in allowedKeys }?.let { return ParseResult.Invalid("Unexpected argument.") }

        val type = (args.opt("action") as? String)?.let { s -> ActionType.values().firstOrNull { it.name == s } }
            ?: return ParseResult.Invalid("Unsupported or missing action.")

        val target = (args.opt("target") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val text = args.opt("text") as? String
        val dirName = args.opt("direction") as? String
        val direction = dirName?.let { d -> ScrollDirection.values().firstOrNull { it.name == d } }
        if (dirName != null && direction == null) return ParseResult.Invalid("Invalid direction.")
        val duration = (args.opt("duration_ms") as? Number)?.toInt() ?: 500
        if (duration !in 200..3000) return ParseResult.Invalid("duration_ms must be between 200 and 3000.")
        val confirm = args.opt("requires_confirmation") as? Boolean ?: false

        if (target != null && (target.length > MAX_TARGET || target.any { it.isISOControl() }))
            return ParseResult.Invalid("Invalid target.")
        if (text != null && (text.length > MAX_TEXT || text.any { it.isISOControl() && it != '\n' }))
            return ParseResult.Invalid("Invalid text.")

        when (type) {
            ActionType.OPEN_APP, ActionType.CLICK_ELEMENT, ActionType.FOCUS_FIELD ->
                if (target == null) return ParseResult.Invalid("${type.name} needs a target.")
            ActionType.SCROLL -> if (direction == null) return ParseResult.Invalid("SCROLL needs a direction.")
            ActionType.TYPE_TEXT -> if (text.isNullOrEmpty()) return ParseResult.Invalid("TYPE_TEXT needs text.")
            else -> Unit
        }
        return ParseResult.Valid(PhoneAction(type, target, direction, duration, text, confirm))
    }
}
