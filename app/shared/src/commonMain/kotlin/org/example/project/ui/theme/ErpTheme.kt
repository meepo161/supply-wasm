package org.example.project.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE9FF),
    onPrimaryContainer = Color(0xFF0A2E6B),
    secondary = Brand.Indigo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E1FF),
    onSecondaryContainer = Color(0xFF241A66),
    tertiary = Brand.Teal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFF5EE),
    onTertiaryContainer = Color(0xFF00382F),
    error = Color(0xFFD92D3C),
    onError = Color.White,
    errorContainer = Color(0xFFFFE1E3),
    onErrorContainer = Color(0xFF61000B),
    background = Color(0xFFF5F8FD),
    onBackground = Brand.Ink,
    surface = Color(0xFFFFFFFF),
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFEDF2FA),
    onSurfaceVariant = Brand.Slate,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFE),
    surfaceContainer = Color(0xFFF1F5FC),
    surfaceContainerHigh = Color(0xFFE9EFF9),
    surfaceContainerHighest = Color(0xFFE1E8F5),
    outline = Color(0xFFC7D2E4),
    outlineVariant = Color(0xFFE0E8F4),
    inverseSurface = Color(0xFF1B2433),
    inverseOnSurface = Color(0xFFEFF3FA),
    scrim = Color(0x66101826)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB0FF),
    onPrimary = Color(0xFF04224F),
    primaryContainer = Color(0xFF1B3B75),
    onPrimaryContainer = Color(0xFFD9E5FF),
    secondary = Color(0xFFB5ACFF),
    onSecondary = Color(0xFF211A57),
    secondaryContainer = Color(0xFF33286E),
    onSecondaryContainer = Color(0xFFE7E2FF),
    tertiary = Color(0xFF57DBC6),
    onTertiary = Color(0xFF00352E),
    tertiaryContainer = Color(0xFF0B4A43),
    onTertiaryContainer = Color(0xFFB5F2E7),
    error = Color(0xFFFF8B93),
    onError = Color(0xFF52000B),
    errorContainer = Color(0xFF6E1020),
    onErrorContainer = Color(0xFFFFDFE1),
    background = Color(0xFF080D17),
    onBackground = Color(0xFFE9EEF8),
    surface = Color(0xFF0E1522),
    onSurface = Color(0xFFE9EEF8),
    surfaceVariant = Color(0xFF18202F),
    onSurfaceVariant = Color(0xFFA3B1C8),
    surfaceContainerLowest = Color(0xFF0A101A),
    surfaceContainerLow = Color(0xFF0F1725),
    surfaceContainer = Color(0xFF131C2C),
    surfaceContainerHigh = Color(0xFF182233),
    surfaceContainerHighest = Color(0xFF1E2A3D),
    outline = Color(0xFF3C4A61),
    outlineVariant = Color(0xFF26313F),
    inverseSurface = Color(0xFFE9EEF8),
    inverseOnSurface = Color(0xFF131C2C),
    scrim = Color(0xCC04070D)
)

/** Типографика: плотные заголовки, спокойный основной текст. */
private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
)

/** Мягкие, «воздушные» скругления. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ErpTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val palette = remember(darkTheme) { if (darkTheme) DarkPalette else LightPalette }
    CompositionLocalProvider(LocalBrandPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
