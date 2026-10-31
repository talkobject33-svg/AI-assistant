package com.example.aiassistant.automation

import com.example.aiassistant.ai.ActionType
import com.example.aiassistant.ai.PhoneAction

/** Local policy. The AI can never override this. */
object ActionPermissionManager {
    /** System permission / install / security screens are never automated. */
    private val blockedPackages = setOf(
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.settings.intelligence"
    )

    private val sensitiveWords = setOf(
        "send", "call", "dial", "pay", "buy", "purchase", "order", "checkout", "subscribe",
        "delete", "remove", "erase", "uninstall", "post", "publish", "tweet", "share",
        "transfer", "withdraw", "donate", "submit", "confirm", "reset", "format", "install",
        "allow", "accept", "agree", "grant"
    )
    private val sensitivePhrases = listOf("place order", "factory reset", "clear data", "sign out", "log out")

    fun isBlockedPackage(pkg: String?) = pkg != null && pkg in blockedPackages

    /** Returns a human-readable reason if the user must confirm on screen first, else null. */
    fun confirmationReason(action: PhoneAction, matchedLabel: String?): String? {
        if (action.modelRequestsConfirmation) return "The assistant flagged this as sensitive."
        if (action.type != ActionType.CLICK_ELEMENT) return null
        val text = listOfNotNull(action.target, matchedLabel).joinToString(" ").lowercase()
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value }.toSet()
        val hit = sensitiveWords.firstOrNull { it in tokens } ?: sensitivePhrases.firstOrNull { it in text }
        return hit?.let { "This looks like it could send, buy, delete, share or consent to something (\"$it\")." }
    }
}
