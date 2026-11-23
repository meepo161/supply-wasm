package org.example.project.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Палитра бренда ERP «Авиаагрегат-Н».
 *
 * Направление (DESIGN.md): промышленный, спокойный, точный интерфейс.
 * - Основной цвет: заводской синий, единственный цвет действий и выбора.
 * - Нейтральная база: графит и холодный серый, на них держится вся плотность данных.
 * - Акцент: сигнальный янтарь, только для отметки текущего места (раздел меню).
 * Остальные оттенки ниже семантические (статусы, тип модуля) и приглушены, чтобы
 * таблицы читались раньше декора. Неоновых и фиолетовых переходов нет.
 */
object Brand {

    // Основные и семантические оттенки (приглушённые)
    val Blue = Color(0xFF1C5FC9)
    val BlueBright = Color(0xFF4A82DB)
    val BlueDeep = Color(0xFF163F82)
    val Indigo = Color(0xFF44589E)
    val Violet = Color(0xFF6E5A9C)
    val Cyan = Color(0xFF2B87A6)
    val Teal = Color(0xFF0F7A70)
    val Mint = Color(0xFF2E8F5B)
    val Amber = Color(0xFFC98510)
    val Orange = Color(0xFFC75A27)
    val Rose = Color(0xFFC63B4B)
    val Crimson = Color(0xFFAA2A3A)

    // Нейтральные
    val Ink = Color(0xFF111821)
    val Graphite = Color(0xFF1A222D)
    val Slate = Color(0xFF55637A)
    val Mist = Color(0xFFF3F5F8)
    val Cloud = Color(0xFFFFFFFF)

    // Тёмная тема
    val NightBase = Color(0xFF0C1118)
    val NightSurface = Color(0xFF131A23)
    val NightElevated = Color(0xFF1A222D)

    /** Сигнальный акцент: метка текущего раздела. */
    val Signal = Color(0xFFE0A21B)
}

/**
 * Заливки модулей и действий.
 *
 * Исторически это были трёхцветные градиенты; API (`List<Color>`) сохранён, чтобы
 * не трогать сотни вызовов, но каждая заливка теперь сплошная: оба цвета совпадают.
 * Цвет несёт смысл (основное действие, успех, предупреждение, опасность), а не декор.
 * Все оттенки дают белому тексту контраст не ниже 4.5:1.
 */
object Gradients {

    private fun solid(color: Color) = listOf(color, color)

    /** Основное действие. */
    val Primary = solid(Color(0xFF1C5FC9))
    /** Справочные и складские разделы. */
    val Ocean = solid(Color(0xFF1F4F8F))
    /** Производство, процессы. */
    val Aurora = solid(Color(0xFF0F7067))
    /** Предупреждение, сроки. */
    val Sunset = solid(Color(0xFFB8501F))
    /** Опасное или необратимое действие. */
    val Berry = solid(Color(0xFFA8283A))
    /** Успешное завершение, приёмка. */
    val Emerald = solid(Color(0xFF237A4B))
    /** Внимание, ожидание. */
    val AmberGlow = solid(Color(0xFF9A6206))
    /** Нейтральное, служебное. */
    val Slate = solid(Color(0xFF4B576B))
    /** Боковая панель навигации. */
    val Sidebar = solid(Color(0xFF151C26))

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
 * Токены поверхностей для светлой и тёмной темы.
 * Поверхности непрозрачные: «стекло» и цветные пятна убраны, глубину задают
 * границы и один уровень тени у диалогов.
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
    pageGradient = listOf(Color(0xFFF2F4F7), Color(0xFFF2F4F7)),
    blobs = listOf(Color.Transparent, Color.Transparent, Color.Transparent),
    heroGradient = listOf(Color(0xFF1A2533), Color(0xFF1A2533)),
    sidebarGradient = Gradients.Sidebar,
    glassOverlay = Color(0xFFFFFFFF),
    glassBorder = listOf(Color(0xFFD9DFE7), Color(0xFFD9DFE7)),
    shadowTint = Color(0xFF111821)
)

val DarkPalette = BrandPalette(
    isDark = true,
    pageGradient = listOf(Color(0xFF0C1118), Color(0xFF0C1118)),
    blobs = listOf(Color.Transparent, Color.Transparent, Color.Transparent),
    heroGradient = listOf(Color(0xFF1A2330), Color(0xFF1A2330)),
    sidebarGradient = listOf(Color(0xFF080C12), Color(0xFF080C12)),
    glassOverlay = Color(0xFF151C26),
    glassBorder = listOf(Color(0xFF2A3442), Color(0xFF2A3442)),
    shadowTint = Color.Black
)

val LocalBrandPalette = staticCompositionLocalOf { LightPalette }

/** Акцентная пара цветов для мягких «тонов» (chip, плашка). */
@Immutable
data class Tone(val container: Color, val content: Color, val accent: Color)

fun tone(accent: Color, dark: Boolean): Tone = if (dark) {
    Tone(
        container = accent.copy(alpha = 0.20f),
        content = Color.White.copy(alpha = 0.92f),
        accent = accent
    )
} else {
    Tone(
        container = accent.copy(alpha = 0.10f),
        content = accent.copy(alpha = 1f),
        accent = accent
    )
}
