package com.example.aiassistant.automation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.aiassistant.ai.ActionResult
import com.example.aiassistant.ai.ActionType
import com.example.aiassistant.ai.ExecutionStatus
import com.example.aiassistant.ai.PhoneAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class PendingAction(val action: PhoneAction, val description: String, val reason: String, val createdAt: Long)

/**
 * The only place Android actions are executed. The AI proposes; this class validates,
 * applies local policy, executes, and reports an honest result.
 */
class PhoneActionRouter(
    private val context: Context,
    private val appLauncher: AppLauncher,
    private val onStopSpeaking: () -> Unit
) {
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status
    private val _pending = MutableStateFlow<PendingAction?>(null)
    val pending: StateFlow<PendingAction?> = _pending
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    fun setPaused(value: Boolean) { _paused.value = value; if (value) _pending.value = null }

    suspend fun executeAction(action: PhoneAction, confirmed: Boolean = false): ActionResult {
        AutomationLogger.log("Router", "Execute ${action.type}${if (confirmed) " (confirmed)" else ""}")
        val result = withContext(Dispatchers.Default) {
            try { run(action, confirmed) }
            catch (e: Exception) { ActionResult(ExecutionStatus.FAILED, "The action failed unexpectedly.") }
        }
        _status.value = result.message
        AutomationLogger.log("Router", "${action.type} -> ${result.status}")
        return result
    }

    suspend fun confirmPending(): ActionResult? {
        val p = _pending.value ?: return null
        _pending.value = null
        if (SystemClock.elapsedRealtime() - p.createdAt > 60_000)
            return ActionResult(ExecutionStatus.FAILED, "That confirmation expired. Please ask again.")
        return executeAction(p.action, confirmed = true)
    }

    fun cancelPending() { _pending.value = null; _status.value = "Cancelled." }

    private suspend fun run(action: PhoneAction, confirmed: Boolean): ActionResult {
        when (action.type) {
            ActionType.STOP_CONTROL -> {
                setPaused(true)
                return done("Phone control stopped. Resume it in the app when you want it back.")
            }
            ActionType.STOP_SPEAKING -> { onStopSpeaking(); return done("Stopped speaking.") }
            else -> Unit
        }
        if (_paused.value) return ActionResult(ExecutionStatus.PERMISSION_REQUIRED,
            "Phone control is paused. The user can resume it in the app.")

        if (action.type == ActionType.OPEN_APP) return openApp(action)

        val service = AccessibilityAutomationService.instance
            ?: return ActionResult(ExecutionStatus.SERVICE_DISABLED,
                "Accessibility control is off. The user must enable it in Android settings.")

        return when (action.type) {
            ActionType.GO_BACK -> global(service, AccessibilityService.GLOBAL_ACTION_BACK, "Going back…", "Went back.")
            ActionType.GO_HOME -> global(service, AccessibilityService.GLOBAL_ACTION_HOME, "Going home…", "Went to the home screen.")
            ActionType.SHOW_RECENTS -> global(service, AccessibilityService.GLOBAL_ACTION_RECENTS, "Opening recent apps…", "Showing recent apps.")
            ActionType.SCROLL -> scroll(service, action)
            ActionType.CLICK_ELEMENT -> click(service, action, confirmed)
            ActionType.FOCUS_FIELD -> focusField(service, action)
            ActionType.TYPE_TEXT -> typeText(service, action)
            else -> ActionResult(ExecutionStatus.UNSUPPORTED, "That action isn't supported.")
        }
    }

    private fun done(msg: String) = ActionResult(ExecutionStatus.COMPLETED, msg)

    private suspend fun openApp(action: PhoneAction): ActionResult {
        val name = action.target!!
        _status.value = "Looking for $name…"
        return when (val r = appLauncher.resolve(name)) {
            AppLauncher.Resolution.NotFound ->
                ActionResult(ExecutionStatus.TARGET_NOT_FOUND, "No installed app matches \"$name\".")
            is AppLauncher.Resolution.Ambiguous ->
                ActionResult(ExecutionStatus.AMBIGUOUS_TARGET,
                    "Several apps match: ${r.options.joinToString { it.label }}. Ask the user which one.")
            is AppLauncher.Resolution.Found -> {
                _status.value = "Opening ${r.app.label}…"
                val service = AccessibilityAutomationService.instance
                val error = appLauncher.launch(r.app, service ?: context)
                if (error != null) return ActionResult(ExecutionStatus.FAILED, error)
                if (service == null) return ActionResult(ExecutionStatus.UNVERIFIED,
                    "Asked Android to open ${r.app.label}; can't confirm because accessibility control is off.")
                val deadline = SystemClock.elapsedRealtime() + 3000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (service.rootInActiveWindow?.packageName == r.app.packageName)
                        return done("Opened ${r.app.label}.")
                    delay(150)
                }
                ActionResult(ExecutionStatus.UNVERIFIED,
                    "Asked Android to open ${r.app.label} but it did not appear on screen. " +
                        "Android may block launches from the background; the optional \"display over other apps\" permission can help.")
            }
        }
    }

    private fun global(s: AccessibilityService, code: Int, working: String, ok: String): ActionResult {
        _status.value = working
        return if (s.performGlobalAction(code)) done(ok)
        else ActionResult(ExecutionStatus.FAILED, "Android didn't accept that action.")
    }

    private fun activeRoot(s: AccessibilityAutomationService): Pair<AccessibilityNodeInfo?, ActionResult?> {
        val root = s.rootInActiveWindow
            ?: return null to ActionResult(ExecutionStatus.FAILED, "No readable window is active right now.")
        if (ActionPermissionManager.isBlockedPackage(root.packageName?.toString()))
            return null to ActionResult(ExecutionStatus.BLOCKED,
                "This is a system permission or security screen. The user needs to handle it themselves.")
        return root to null
    }

    private suspend fun scroll(s: AccessibilityAutomationService, a: PhoneAction): ActionResult {
        val (_, blocked) = activeRoot(s)
        if (blocked != null) return blocked
        _status.value = "Scrolling ${a.direction!!.name.lowercase()}…"
        val ok = GestureController(s).scroll(a.direction, a.durationMs)
        return if (ok) done("Scrolled ${a.direction.name.lowercase()}.")
        else ActionResult(ExecutionStatus.FAILED, "The scroll gesture was cancelled or rejected.")
    }

    private suspend fun click(s: AccessibilityAutomationService, a: PhoneAction, confirmed: Boolean): ActionResult {
        val (root, blocked) = activeRoot(s)
        if (blocked != null) return blocked
        _status.value = "Searching accessible screen elements…"
        val target = a.target!!
        return when (val f = ScreenNodeInspector.find(root!!, target, ScreenNodeInspector.Mode.CLICKABLE)) {
            ScreenNodeInspector.Lookup.NotFound ->
                ActionResult(ExecutionStatus.TARGET_NOT_FOUND, "Couldn't find \"$target\" on the screen.")
            is ScreenNodeInspector.Lookup.Ambiguous ->
                ActionResult(ExecutionStatus.AMBIGUOUS_TARGET,
                    "Several elements match: ${f.labels.joinToString { "\"$it\"" }}. Ask the user which one.")
            is ScreenNodeInspector.Lookup.Found -> {
                val reason = ActionPermissionManager.confirmationReason(a, f.label)
                if (reason != null && !confirmed) {
                    _pending.value = PendingAction(a, "Tap \"${f.label.ifBlank { target }}\"", reason, SystemClock.elapsedRealtime())
                    _status.value = "Waiting for your confirmation…"
                    return ActionResult(ExecutionStatus.CONFIRMATION_REQUIRED,
                        "The user must confirm this on the screen first.")
                }
                _status.value = "Tapping ${f.label.ifBlank { target }}…"
                var ok = !f.needsGestureTap && f.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!ok) {
                    val r = android.graphics.Rect().also { f.node.getBoundsInScreen(it) }
                    if (!r.isEmpty) ok = GestureController(s).tap(r.exactCenterX(), r.exactCenterY())
                }
                if (ok) done("Tapped ${f.label.ifBlank { target }}.")
                else ActionResult(ExecutionStatus.FAILED, "Found \"${f.label}\" but the tap didn't go through.")
            }
        }
    }

    private fun focusField(s: AccessibilityAutomationService, a: PhoneAction): ActionResult {
        val (root, blocked) = activeRoot(s)
        if (blocked != null) return blocked
        _status.value = "Searching accessible screen elements…"
        return when (val f = ScreenNodeInspector.find(root!!, a.target!!, ScreenNodeInspector.Mode.EDITABLE)) {
            ScreenNodeInspector.Lookup.NotFound ->
                ActionResult(ExecutionStatus.TARGET_NOT_FOUND, "Couldn't find a text field labelled \"${a.target}\".")
            is ScreenNodeInspector.Lookup.Ambiguous ->
                ActionResult(ExecutionStatus.AMBIGUOUS_TARGET, "Several fields match: ${f.labels.joinToString { "\"$it\"" }}.")
            is ScreenNodeInspector.Lookup.Found -> {
                val focused = f.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val clicked = f.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (focused || clicked) done("Selected the ${f.label.ifBlank { "text" }} field.")
                else ActionResult(ExecutionStatus.FAILED, "Couldn't select that field.")
            }
        }
    }

    private suspend fun typeText(s: AccessibilityAutomationService, a: PhoneAction): ActionResult {
        val (root, blocked) = activeRoot(s)
        if (blocked != null) return blocked
        _status.value = "Typing…"
        var node = root!!.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        if (node == null) {
            val fields = ScreenNodeInspector.collect(root).filter { it.isEditable }
            node = fields.singleOrNull()
                ?: return ActionResult(ExecutionStatus.TARGET_NOT_FOUND,
                    if (fields.isEmpty()) "There is no text field on screen."
                    else "No field is selected. Ask the user which field to type into.")
        }
        if (node.isPassword) return ActionResult(ExecutionStatus.BLOCKED,
            "This is a password field. I don't type into password fields; the user should enter it themselves.")
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + a.text!!)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            return ActionResult(ExecutionStatus.FAILED, "The field didn't accept the text.")
        delay(200)
        node.refresh()
        return if (node.text?.toString()?.contains(a.text!!) == true) done("Typed the text.")
        else ActionResult(ExecutionStatus.UNVERIFIED, "Sent the text but couldn't confirm it appeared in the field.")
    }
}
