package com.mytvphone.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4F8CFF),
    onPrimary = Color.White,
    secondary = Color(0xFF7AA8FF),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF171B22),
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF1D222B),
    onSurfaceVariant = Color(0xFFA8B0BE),
    outline = Color(0xFF2A313C),
    error = Color(0xFFE06C6C),
)

@Composable
fun MyTvPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
