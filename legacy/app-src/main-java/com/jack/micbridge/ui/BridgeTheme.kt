package com.jack.micbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF2559C7), onPrimary = Color.White,
    primaryContainer = Color(0xFFE9EFFF), onPrimaryContainer = Color(0xFF193D87),
    secondary = Color(0xFF526174), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EDF3), onSecondaryContainer = Color(0xFF28374B),
    background = Color(0xFFF5F7FA), onBackground = Color(0xFF192536),
    surface = Color.White, onSurface = Color(0xFF192536),
    surfaceVariant = Color(0xFFEDF1F6), onSurfaceVariant = Color(0xFF59677A),
    surfaceContainer = Color(0xFFF0F3F8), surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFE9EEF6),
    outline = Color(0xFF778397), outlineVariant = Color(0xFFDFE5ED),
    error = Color(0xFFAB2939), onError = Color.White,
    errorContainer = Color(0xFFFCECEF), onErrorContainer = Color(0xFF8F1C2D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFC6FF), onPrimary = Color(0xFF12316C),
    primaryContainer = Color(0xFF263D68), onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFB6C3D6), onSecondary = Color(0xFF253245),
    secondaryContainer = Color(0xFF303E52), onSecondaryContainer = Color(0xFFDCE5F3),
    background = Color(0xFF111923), onBackground = Color(0xFFE8EEF7),
    surface = Color(0xFF1A2432), onSurface = Color(0xFFE8EEF7),
    surfaceVariant = Color(0xFF273446), onSurfaceVariant = Color(0xFFB1BFD2),
    surfaceContainer = Color(0xFF202C3B), surfaceContainerLow = Color(0xFF1A2432),
    surfaceContainerHigh = Color(0xFF2A384C),
    outline = Color(0xFF8392A8), outlineVariant = Color(0xFF354459),
    error = Color(0xFFFFB2BF), onError = Color(0xFF640F23),
    errorContainer = Color(0xFF442530), onErrorContainer = Color(0xFFFFD9E0),
)

@Immutable
data class BridgeStatusColors(
    val safe: Color,
    val safeContainer: Color,
    val attention: Color,
    val attentionContainer: Color,
)

val LocalBridgeStatusColors = staticCompositionLocalOf {
    BridgeStatusColors(Color(0xFF19664E), Color(0xFFE9F4EF), Color(0xFF8C5700), Color(0xFFFFF2DE))
}

private val BridgeTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

@Composable
fun MicBridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val status = if (dark) {
        BridgeStatusColors(Color(0xFF9FDAC1), Color(0xFF213C34), Color(0xFFF2C57E), Color(0xFF413522))
    } else {
        BridgeStatusColors(Color(0xFF19664E), Color(0xFFE9F4EF), Color(0xFF8C5700), Color(0xFFFFF2DE))
    }
    CompositionLocalProvider(LocalBridgeStatusColors provides status) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = BridgeTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
            content = content,
        )
    }
}
