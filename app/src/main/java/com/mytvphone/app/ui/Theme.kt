package com.mytvphone.app.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Material 3 浅 / 深双色板，跟随系统。
 *
 * 状态栏图标明暗与系统栏显隐统一由 AppNav 按当前路由写入 window，
 * 这里不再使用 SideEffect 重复设置——两处写同一个 window 会互相覆盖，
 * 在播放页进出时造成状态栏颜色闪烁。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF35618F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E7FB),
    onPrimaryContainer = Color(0xFF0B2C4D),
    secondary = Color(0xFF4E6076),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E5F2),
    onSecondaryContainer = Color(0xFF122536),
    tertiary = Color(0xFF5A5C7E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E0F9),
    onTertiaryContainer = Color(0xFF17173A),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE1E7EF),
    onSurfaceVariant = Color(0xFF434A55),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5FA),
    surfaceContainer = Color(0xFFECF1F7),
    surfaceContainerHigh = Color(0xFFE6ECF4),
    surfaceContainerHighest = Color(0xFFE0E7F0),
    outline = Color(0xFF747C88),
    outlineVariant = Color(0xFFC4CBD6),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color.Black,
    inverseSurface = Color(0xFF2E3136),
    inverseOnSurface = Color(0xFFF2F4F8),
    inversePrimary = Color(0xFFA8C9F2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C9F2),
    onPrimary = Color(0xFF103250),
    primaryContainer = Color(0xFF264C72),
    onPrimaryContainer = Color(0xFFD5E7FB),
    secondary = Color(0xFFB9C9DC),
    onSecondary = Color(0xFF243648),
    secondaryContainer = Color(0xFF3B4E62),
    onSecondaryContainer = Color(0xFFD9E5F2),
    tertiary = Color(0xFFC3C4EB),
    onTertiary = Color(0xFF2C2D50),
    tertiaryContainer = Color(0xFF414268),
    onTertiaryContainer = Color(0xFFE0E0F9),
    background = Color(0xFF0F1318),
    onBackground = Color(0xFFE2E6EC),
    surface = Color(0xFF0F1318),
    onSurface = Color(0xFFE2E6EC),
    surfaceVariant = Color(0xFF3A4450),
    onSurfaceVariant = Color(0xFFC3CBD6),
    surfaceContainerLowest = Color(0xFF0A0E12),
    surfaceContainerLow = Color(0xFF161C23),
    surfaceContainer = Color(0xFF1A2129),
    surfaceContainerHigh = Color(0xFF212A33),
    surfaceContainerHighest = Color(0xFF2B3540),
    outline = Color(0xFF8D96A3),
    outlineVariant = Color(0xFF434A55),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    scrim = Color.Black,
    inverseSurface = Color(0xFFE2E6EC),
    inverseOnSurface = Color(0xFF2E3136),
    inversePrimary = Color(0xFF3E6FA8),
)

/**
 * 字阶沿用 Material 3 默认（字号不会错），只把中文容易偏紧的正文行高提到 1.5，
 * 长句密排时不再吃力；其余角色保持 M3 基线。
 */
private val AppTypography = Typography().copy(
    bodyMedium = Typography().bodyMedium.copy(lineHeight = 21.sp),
    bodySmall = Typography().bodySmall.copy(lineHeight = 18.sp),
    titleSmall = Typography().titleSmall.copy(lineHeight = 22.sp),
)

/**
 * 收敛散落的硬编码圆角（抽屉 20dp / 卡片 10dp / 下划线 1dp 等）到 M3 的 5 档 shape 刻度。
 * 组件统一引用 shapes.* 而不是写死数值，后续要调圆角只改这里。
 */
private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MyTvPhoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = resolveScheme(context, darkTheme, dynamicColor)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

@Composable
private fun resolveScheme(
    context: Context,
    darkTheme: Boolean,
    dynamicColor: Boolean,
) = when {
    // 动态取色（Material You）：Android 12+ 跟随壁纸生成色板；默认关闭以保留本应用
    // 手工推导的品牌蓝——开启后整页配色会随系统壁纸变化。
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

    darkTheme -> DarkColors
    else -> LightColors
}
