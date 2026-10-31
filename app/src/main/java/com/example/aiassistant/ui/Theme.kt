package com.example.aiassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Hud {
    val Bg = Color(0xFF050B1A)
    val Surface = Color(0xFF0B1630)
    val Cyan = Color(0xFF22D3EE)
    val Blue = Color(0xFF3B82F6)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFF87171)
    val Green = Color(0xFF34D399)
    val Text = Color(0xFFE6F1FF)
    val Muted = Color(0xFF8CA3C7)
    val Line = Color(0xFF28406E)
}

/** Dark-first theme: the assistant UI is always dark. */
@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Hud.Cyan, onPrimary = Hud.Bg, secondary = Hud.Blue,
            background = Hud.Bg, onBackground = Hud.Text,
            surface = Hud.Surface, onSurface = Hud.Text, onSurfaceVariant = Hud.Muted,
            surfaceVariant = Color(0xFF0F1D3D), error = Hud.Red, outline = Hud.Line
        ),
        content = content
    )
}

@Composable
fun Screen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Hud.Bg).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
        content = content
    )
}

@Composable
fun StatusPill(label: String, value: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Hud.Surface)
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text("$label: $value", color = Hud.Text, fontSize = 13.sp)
    }
}
