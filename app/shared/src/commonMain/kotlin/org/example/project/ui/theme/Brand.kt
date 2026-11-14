package org.example.project.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Палитра бренда ERP «Авиаагрегат-Н».
 *
 * Ориентир — фирменная айдентика avem.ru: глубокий синий акцент, светлый
 * воздушный фон, тёмный графитовый текст. Градиенты строятся на связке
 * «синий → индиго → фиолетовый» с циановыми и бирюзовыми акцентами.
 */
object Brand {

    // Основные акценты
    val Blue = Color(0xFF1668E3)
    val BlueBright = Color(0xFF3B8CFF)
    val BlueDeep = Color(0xFF0B3FA6)
    val Indigo = Color(0xFF4F46E5)
    val Violet = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF22D3EE)
    val Teal = Color(0xFF0D9488)
    val Mint = Color(0xFF34D399)
    val Amber = Color(0xFFF59E0B)
    val Orange = Color(0xFFFB7143)
    val Rose = Color(0xFFF43F5E)
    val Crimson = Color(0xFFE11D48)

    // Нейтральные
    val Ink = Color(0xFF0E1726)
    val Graphite = Color(0xFF1B2433)
    val Slate = Color(0xFF5A6A85)
    val Mist = Color(0xFFF4F7FC)
    val Cloud = Color(0xFFFFFFFF)

    // Тёмная тема
    val NightBase = Color(0xFF070B14)
    val NightSurface = Color(0xFF101827)
    val NightElevated = Color(0xFF16203440)
}

/**
 * Набор градиентов приложения. Используется и в фоне страницы, и в
 * акцентных карточках, и в кнопках, чтобы интерфейс выглядел единым.
 */
object Gradients {

    val Primary = listOf(Brand.Blue, Brand.Indigo, Brand.Violet)
    val Ocean = listOf(Color(0xFF0EA5E9), Brand.Blue, Brand.Indigo)
    val Aurora = listOf(Brand.Cyan, Brand.Teal, Brand.Indigo)
    val Sunset = listOf(Color(0xFFFF9A62), Brand.Orange, Brand.Rose)
    val Berry = listOf(Brand.Violet, Brand.Rose, Brand.Crimson)
    val Emerald = listOf(Brand.Mint, Color(0xFF10B981), Brand.Teal)
    val AmberGlow = listOf(Color(0xFFFCD34D), Brand.Amber, Brand.Orange)
    val Slate = listOf(Color(0xFF8CA0BD), Color(0xFF64748B), Color(0xFF475569))
    val Sidebar = listOf(Color(0xFF0B1428), Color(0xFF102A5C), Color(0xFF1B1440))

    fun primary(): Brush = Brush.linearGradient(Primary)
    fun ocean(): Brush = Brush.linearGradient(Ocean)
    fun aurora(): Brush = Brush.linearGradient(Aurora)
    fun sunset(): Brush = Brush.linearGradient(Sunset)
    fun berry(): Brush = Brush.linearGradient(Berry)
    fun emerald(): Brush = Brush.linearGradient(Emerald)
    fun amber(): Brush = Brush.linearGradient(AmberGlow)
    fun sidebar(): Brush = Brush.linearGradient(Sidebar)
}

/**
 * Токены фона и «стекла» для светлой и тёмной темы.
 */
@Immutable
data class BrandPalette(
    val isDark: Boolean,
    val pageGradient: List<Color>,
    val blobs: List<Color>,
    val heroGradient: List<Color>,
    val sidebarGradient: List<Color>,
    val glassOverlay: Color,
    val glassBorder: List<Color>,
    val shadowTint: Color
)

val LightPalette = BrandPalette(
    isDark = false,
    pageGradient = listOf(Color(0xFFF7F9FE), Color(0xFFF1F5FC), Color(0xFFEEF3FB)),
    blobs = listOf(
        Brand.Blue.copy(alpha = 0.18f),
        Brand.Violet.copy(alpha = 0.16f),
        Brand.Cyan.copy(alpha = 0.14f)
    ),
    heroGradient = listOf(Brand.Blue, Brand.Indigo, Brand.Violet),
    sidebarGradient = Gradients.Sidebar,
    glassOverlay = Color(0xF2FFFFFF),
    glassBorder = listOf(Color.White, Brand.Blue.copy(alpha = 0.10f)),
    shadowTint = Brand.Blue
)

val DarkPalette = BrandPalette(
    isDark = true,
    pageGradient = listOf(Color(0xFF080D17), Color(0xFF0B1220), Color(0xFF0A1020)),
    blobs = listOf(
        Brand.Blue.copy(alpha = 0.22f),
        Brand.Violet.copy(alpha = 0.20f),
        Brand.Cyan.copy(alpha = 0.12f)
    ),
    heroGradient = listOf(Color(0xFF1B2B5C), Color(0xFF2A1E5C), Color(0xFF3B1B57)),
    sidebarGradient = listOf(Color(0xFF060A13), Color(0xFF0C1730), Color(0xFF15112E)),
    glassOverlay = Color(0xE6121A28),
    glassBorder = listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.03f)),
    shadowTint = Brand.BlueDeep
)

val LocalBrandPalette = staticCompositionLocalOf { LightPalette }

/** Акцентная пара цветов для мягких «тонов» (chip, плашка). */
@Immutable
data class Tone(val container: Color, val content: Color, val accent: Color)

fun tone(accent: Color, dark: Boolean): Tone = if (dark) {
    Tone(
        container = accent.copy(alpha = 0.18f),
        content = Color.White.copy(alpha = 0.92f),
        accent = accent
    )
} else {
    Tone(
        container = accent.copy(alpha = 0.12f),
        content = accent.copy(alpha = 1f),
        accent = accent
    )
}
