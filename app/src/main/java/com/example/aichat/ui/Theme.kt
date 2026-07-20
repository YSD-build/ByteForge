package com.example.aichat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** AgentForge 毛玻璃+圆润主题 — 深色渐变底 + 半透明卡片 */
private val GlassColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF7C4DFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF7C4DFF).copy(alpha = 0.25f),
    onPrimaryContainer = Color(0xFFE8D5FF),
    secondary = Color(0xFF00E5FF),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF00E5FF).copy(alpha = 0.2f),
    tertiary = Color(0xFFFF6D00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFF6D00).copy(alpha = 0.2f),
    background = Color(0xFF0A0A1A),
    onBackground = Color.White,
    surface = Color.White.copy(alpha = 0.08f),
    onSurface = Color.White.copy(alpha = 0.92f),
    onSurfaceVariant = Color.White.copy(alpha = 0.60f),
    surfaceVariant = Color.White.copy(alpha = 0.05f),
    outline = Color.White.copy(alpha = 0.12f),
    error = Color(0xFFFF5252)
)

private val GlassShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

private val GlassTypography = androidx.compose.material3.Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.5.sp)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GlassColors,
        shapes = GlassShapes,
        typography = GlassTypography,
        content = content
    )
}
