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
    primaryContainer = Color(0xFFDCE6F7),
    onPrimaryContainer = Color(0xFF0E2E63),
    secondary = Color(0xFF44546B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E6EE),
    onSecondaryContainer = Color(0xFF1A2533),
    tertiary = Brand.Teal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3ECE8),
    onTertiaryContainer = Color(0xFF00352F),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFBE3E1),
    onErrorContainer = Color(0xFF5C0B07),
    background = Color(0xFFE7EDF5),
    onBackground = Brand.Ink,
    surface = Color(0xFFFFFFFF),
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFE3E9F2),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F6FA),
    surfaceContainer = Color(0xFFEDF1F7),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFD5DEE9),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFC5D0DF),
    inverseSurface = Color(0xFF1A2330),
    inverseOnSurface = Color(0xFFEEF1F5),
    scrim = Color(0x8010151C)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8DB4F2),
    onPrimary = Color(0xFF0A2552),
    primaryContainer = Color(0xFF1D3A6B),
    onPrimaryContainer = Color(0xFFD8E4F8),
    secondary = Color(0xFFB4BFCF),
    onSecondary = Color(0xFF1E2835),
    secondaryContainer = Color(0xFF2B3544),
    onSecondaryContainer = Color(0xFFDDE3EC),
    tertiary = Color(0xFF6CCBBC),
    onTertiary = Color(0xFF00332D),
    tertiaryContainer = Color(0xFF0E4740),
    onTertiaryContainer = Color(0xFFC4EDE6),
    error = Color(0xFFF2A39B),
    onError = Color(0xFF4E0B06),
    errorContainer = Color(0xFF6A1A15),
    onErrorContainer = Color(0xFFFCE0DD),
    background = Color(0xFF0C1118),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF151C26),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF1E2632),
    onSurfaceVariant = Color(0xFFA9B4C3),
    surfaceContainerLowest = Color(0xFF0E141C),
    surfaceContainerLow = Color(0xFF131A23),
    surfaceContainer = Color(0xFF18202A),
    surfaceContainerHigh = Color(0xFF1E2631),
    surfaceContainerHighest = Color(0xFF252E3A),
    outline = Color(0xFF465263),
    outlineVariant = Color(0xFF2A3442),
    inverseSurface = Color(0xFFE6EAF0),
    inverseOnSurface = Color(0xFF18202A),
    scrim = Color(0xCC05080C)
)

/**
 * Типографика: системный шрифт Compose (гарантированная кириллица без новых
 * зависимостей), плотные заголовки и цифры, спокойный основной текст.
 */
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

/**
 * Сдержанные скругления: 4 dp у бирок и полей, 6-8 dp у кнопок и карточек,
 * 12 dp только у диалогов. Форма различает тип элемента, а не украшает его.
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp)
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
            shapes = AppShapes
        ) {
            // Текст без явного цвета иначе берёт чёрный по умолчанию и пропадает на тёмной теме.
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides colorScheme.onBackground,
                content = content
            )
        }
    }
}
