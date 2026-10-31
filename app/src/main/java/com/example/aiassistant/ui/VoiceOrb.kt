package com.example.aiassistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.aiassistant.voice.VoiceSessionState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

fun stateColor(state: VoiceSessionState): Color = when (state) {
    VoiceSessionState.LISTENING -> Hud.Cyan
    VoiceSessionState.SPEAKING -> Hud.Blue
    VoiceSessionState.PROCESSING, VoiceSessionState.EXECUTING_ACTION -> Hud.Amber
    VoiceSessionState.CONNECTING, VoiceSessionState.RECONNECTING, VoiceSessionState.INTERRUPTED -> Hud.Muted
    VoiceSessionState.ERROR -> Hud.Red
    VoiceSessionState.IDLE -> Hud.Cyan.copy(alpha = 0.45f)
}

/** The ring pulse, core size and wave height respond to the real measured audio level. */
@Composable
fun VoiceOrb(state: VoiceSessionState, level: Float, modifier: Modifier = Modifier, size: Dp = 230.dp) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    val smooth by animateFloatAsState(level.coerceIn(0f, 1f), tween(110), label = "level")
    val color = stateColor(state)
    val working = state == VoiceSessionState.PROCESSING || state == VoiceSessionState.EXECUTING_ACTION ||
        state == VoiceSessionState.CONNECTING || state == VoiceSessionState.RECONNECTING
    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2f
        for (i in 0..2) {
            val p = (pulse + i / 3f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.28f), radius = r * (0.52f + 0.48f * p + smooth * 0.08f), center = c,
                style = Stroke(2.dp.toPx()))
        }
        val core = r * 0.5f * (1f + smooth * 0.4f)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.3f), Color.Transparent), center = c, radius = core * 1.5f),
            radius = core * 1.5f, center = c)
        drawCircle(color, radius = core * 0.55f, center = c)
        if (working) {
            drawArc(color, startAngle = spin, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(c.x - r * 0.72f, c.y - r * 0.72f), size = Size(r * 1.44f, r * 1.44f),
                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
fun WaveBars(level: Float, color: Color, modifier: Modifier = Modifier, bars: Int = 28) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val smooth by animateFloatAsState(level.coerceIn(0f, 1f), tween(100), label = "waveLevel")
    Canvas(modifier.height(48.dp).width(220.dp)) {
        val gap = size.width / bars
        for (i in 0 until bars) {
            val shape = abs(sin(phase + i * 0.45f))
            val h = (4.dp.toPx() + (size.height - 4.dp.toPx()) * smooth * shape).coerceAtMost(size.height)
            drawRoundRect(color, topLeft = Offset(i * gap + gap * 0.2f, (size.height - h) / 2f),
                size = Size(gap * 0.6f, h), cornerRadius = CornerRadius(gap * 0.3f))
        }
    }
}
