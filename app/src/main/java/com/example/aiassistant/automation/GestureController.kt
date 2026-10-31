package com.example.aiassistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.example.aiassistant.ai.ScrollDirection
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GestureController(private val service: AccessibilityService) {
    private val width get() = service.resources.displayMetrics.widthPixels
    private val height get() = service.resources.displayMetrics.heightPixels

    /** Taps only inside the real screen bounds. Returns true if the gesture completed. */
    suspend fun tap(x: Float, y: Float): Boolean {
        if (x < 0f || y < 0f || x > width || y > height) return false
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 60)
    }

    suspend fun scroll(direction: ScrollDirection, durationMs: Int): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        val path = Path()
        // The finger moves opposite to the direction the content is revealed.
        when (direction) {
            ScrollDirection.DOWN -> { path.moveTo(w / 2, h * 0.72f); path.lineTo(w / 2, h * 0.32f) }
            ScrollDirection.UP -> { path.moveTo(w / 2, h * 0.32f); path.lineTo(w / 2, h * 0.72f) }
            ScrollDirection.RIGHT -> { path.moveTo(w * 0.8f, h / 2); path.lineTo(w * 0.2f, h / 2) }
            ScrollDirection.LEFT -> { path.moveTo(w * 0.2f, h / 2); path.lineTo(w * 0.8f, h / 2) }
        }
        return dispatch(path, durationMs.toLong().coerceIn(200, 3000))
    }

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!accepted && cont.isActive) cont.resume(false)
    }
}
