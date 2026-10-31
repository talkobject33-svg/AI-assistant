package com.example.aiassistant.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Passive until the router calls into it. It reacts to nothing on its own: no event is
 * forwarded anywhere, and it only acts when PhoneActionRouter executes a validated action.
 */
class AccessibilityAutomationService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        AutomationLogger.log("Accessibility", "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        release(); return super.onUnbind(intent)
    }

    override fun onDestroy() { release(); super.onDestroy() }

    private fun release() {
        if (instance === this) instance = null
        _connected.value = false
        AutomationLogger.log("Accessibility", "Service disconnected")
    }

    companion object {
        @Volatile var instance: AccessibilityAutomationService? = null
            private set
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected
    }
}
