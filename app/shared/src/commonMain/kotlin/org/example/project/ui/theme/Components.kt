package org.example.project.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults

/**
 * Фон рабочей области: ровный нейтральный цвет темы.
 * Пятна, свечения и псевдосетка убраны: фон не должен спорить с таблицами.
 */
@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        content = content
    )
}

/**
 * Видимая рамка клавиатурного фокуса и курсор-рука для собственных
 * кликабельных элементов (у них отключена стандартная индикация Material).
 */
@Composable
fun Modifier.erpInteractive(
    interaction: MutableInteractionSource,
    shape: Shape,
    enabled: Boolean = true,
    focusColor: Color = MaterialTheme.colorScheme.primary
): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    return this
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .then(if (focused) Modifier.border(2.dp, focusColor, shape) else Modifier)
}

/**
 * Карточка рабочей области: непрозрачная поверхность, тонкая граница.
 * Тени нет: карточка лежит на странице, а не парит. Кликабельная карточка
 * отвечает на наведение рамкой основного цвета, на фокус рамкой 2 dp.
 * Имя `GlassCard` сохранено ради совместимости вызовов.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    gradient: List<Color>? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    elevated: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalBrandPalette.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val clickable = onClick != null

    val background = when {
        gradient != null -> gradient.first()
        clickable && pressed -> scheme.surfaceContainerHigh
        clickable && hovered -> scheme.surfaceContainerLow
        else -> palette.glassOverlay
    }
    val borderColor = when {
        clickable && hovered -> scheme.primary.copy(alpha = 0.55f)
        else -> palette.glassBorder.first()
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(background, shape)
            .border(1.dp, borderColor, shape)
            .then(
                if (onClick != null) {
                    Modifier
                        .erpInteractive(interaction, shape)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick
                        )
                } else {
                    Modifier
                }
            )
            .padding(contentPadding)
    ) {
        Column(content = content)
    }
}

/**
 * Основная кнопка: сплошная заливка смыслового цвета (по умолчанию основной синий),
 * белый текст с контрастом не ниже 4.5:1. Наведение затемняет заливку,
 * нажатие затемняет сильнее; тени и свечения нет.
 * Имя `GradientButton` сохранено ради совместимости вызовов.
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gradient: List<Color> = Gradients.Primary,
    leadingIcon: ErpIconGlyph? = null,
    compact: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(6.dp)
    val base = gradient.first()
    val fill = when {
        !enabled -> scheme.surfaceContainerHighest
        pressed -> base.shade(0.78f)
        hovered -> base.shade(0.88f)
        else -> base
    }
    val contentColor = if (enabled) Color.White else scheme.onSurfaceVariant

    Box(
        modifier = modifier
            .heightIn(min = if (compact) 34.dp else 40.dp)
            .clip(shape)
            .background(fill, shape)
            .erpInteractive(interaction, shape, enabled, focusColor = scheme.onBackground)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 7.dp else 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                ErpIcon(
                    glyph = leadingIcon,
                    tint = contentColor,
                    modifier = Modifier.size(if (compact) 15.dp else 16.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Затемнение цвета для состояний наведения и нажатия. */
internal fun Color.shade(factor: Float): Color = Color(red * factor, green * factor, blue * factor, alpha)

/**
 * Второстепенная кнопка: контурная, текст цветом действия.
 * Наведение даёт лёгкую подложку, нажатие плотнее, отключённая бледнеет.
 */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ErpIconGlyph? = null,
    tint: Color? = null
) {
    val scheme = MaterialTheme.colorScheme
    val accent = tint ?: scheme.primary
    val textColor = if (tint != null) readableAccent(accent) else accent
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(6.dp)
    val fill = when {
        !enabled -> Color.Transparent
        pressed -> accent.copy(alpha = 0.16f)
        hovered -> accent.copy(alpha = 0.08f)
        else -> scheme.surface
    }
    Box(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, if (enabled) accent.copy(alpha = 0.45f) else scheme.outlineVariant, shape)
            .erpInteractive(interaction, shape, enabled)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                ErpIcon(
                    glyph = leadingIcon,
                    tint = if (enabled) textColor else scheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) textColor else scheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1
            )
        }
    }
}

/**
 * Заголовок секции: название, счётчик-бирка и действие справа.
 * Цветная полоса слева убрана: заголовок держится размером и весом.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (count != null) {
                        Spacer(Modifier.width(8.dp))
                        PillBadge(text = count.toString(), accent = accent)
                    }
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        action?.let { Row(verticalAlignment = Alignment.CenterVertically, content = it) }
    }
}

/** Акцент, затемнённый так, чтобы белый текст на нём или он сам на светлом фоне давал 4.5:1 (WCAG AA). */
fun Color.deepened(): Color = Color(red * 0.6f, green * 0.6f, blue * 0.6f, alpha)

/**
 * Цвет мелкого текста акцентом. Яркие акценты (янтарный, бирюзовый) на светлой теме
 * не набирают 4.5:1, поэтому там текст рисуется затемнённым оттенком того же цвета;
 * на тёмной теме исходный акцент читается и остаётся как есть.
 */
@Composable
fun readableAccent(accent: Color): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) accent.deepened() else accent

/**
 * Бирка статуса или счётчика. Прямоугольная, со скруглением 4 dp, как
 * маркировочная бирка на оборудовании: это повторяющийся мотив интерфейса.
 * Статус всегда передан текстом, цвет только дублирует его.
 */
@Composable
fun PillBadge(
    text: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    filled: Boolean = false
) {
    val solid = accent.deepened()
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (filled) solid else accent.copy(alpha = 0.11f))
            .border(1.dp, accent.copy(alpha = if (filled) 0.6f else 0.28f), shape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) Color.White else readableAccent(accent)
        )
    }
}

/** Аватар с инициалами на сплошной приглушённой подложке, цвет стабилен для имени. */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    gradient: List<Color>? = null
) {
    val colors = gradient ?: remember(name) { avatarColorFor(name).let { listOf(it, it) } }
    val initials = remember(name) {
        // Слова без букв («—», «·», номер вида «T-2026-00001» оставляет свою букву) пропускаются.
        name.trim().split(" ", ".").filter { word -> word.any { it.isLetter() } }
            .take(2)
            .map { word -> word.first { it.isLetter() }.uppercaseChar().toString() }
            .joinToString("")
            .ifBlank { "?" }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(colors.first()),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/** Приглушённые оттенки для аватаров: различимы между собой, белые инициалы читаются. */
private val AvatarColors = listOf(
    Color(0xFF2F5A93), Color(0xFF2F6B5E), Color(0xFF6B4E2E),
    Color(0xFF5A4B7A), Color(0xFF7A3E46), Color(0xFF3F5566)
)

private fun avatarColorFor(seed: String): Color {
    var hash = 7
    seed.forEach { hash = hash * 31 + it.code }
    return AvatarColors[(hash % AvatarColors.size + AvatarColors.size) % AvatarColors.size]
}

/**
 * Значок модуля: иконка цветом модуля на бледной подложке того же цвета.
 * Цвет помогает узнать раздел, но не перекрикивает данные.
 */
@Composable
fun IconBadge(
    glyph: ErpIconGlyph,
    modifier: Modifier = Modifier,
    gradient: List<Color> = Gradients.Primary,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp
) {
    val dark = LocalBrandPalette.current.isDark
    val accent = gradient.first()
    val iconTint = if (dark) accent.lifted() else accent
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.2f))
            .background(accent.copy(alpha = if (dark) 0.22f else 0.10f))
            .border(1.dp, accent.copy(alpha = if (dark) 0.35f else 0.22f), RoundedCornerShape(size * 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        ErpIcon(glyph = glyph, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}

/** Осветление тёмного цвета модуля для иконок на тёмной теме. */
internal fun Color.lifted(): Color = Color(
    red + (1f - red) * 0.45f,
    green + (1f - green) * 0.45f,
    blue + (1f - blue) * 0.45f,
    alpha
)

/**
 * Пустое состояние: заголовок называет причину, текст подсказывает следующее действие.
 * Иконка необязательна и ставится, только если она относится к содержимому.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    glyph: ErpIconGlyph? = null,
    accent: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (glyph != null && glyph != ErpIconGlyph.Sparkle) {
            ErpIcon(glyph = glyph, tint = accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(10.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (text != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Строка «параметр → значение» с аккуратной типографикой. */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(160.dp)
        )
        androidx.compose.foundation.text.selection.SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = valueColor
            )
        }
    }
}

/**
 * Индикатор загрузки. Вращение здесь единственная постоянная анимация
 * в интерфейсе: оно показывает, что запрос ещё выполняется (MOTION 1).
 */
@Composable
fun GradientSpinner(modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(size * 0.7f),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeWidth = 3.dp
        )
    }
}

/** Скелетон-плашка для состояния загрузки. */
@Composable
fun SkeletonLine(
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    widthFraction: Float = 1f
) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(maxWidth * widthFraction)
                .height(height)
                .clip(RoundedCornerShape(3.dp))
                .background(scheme.surfaceContainerHighest)
        )
    }
}

/**
 * Собственный набор иконок (векторно, без внешних ресурсов), чтобы
 * интерфейс выглядел цельно и не зависел от material-icons.
 */
enum class ErpIconGlyph {
    Home, Tasks, Projects, Warehouse, Layers, Cart, Documents, Bell, Shield, User,
    Search, Plus, Refresh, Logout, ChevronRight, ChevronLeft, Moon, Sun,
    CheckCircle, Clock, Alert, Sparkle, Menu, Filter, Send, Close, Grid, TrendUp,
    MoreHoriz, Attachment
}

@Composable
fun ErpIcon(
    glyph: ErpIconGlyph,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 1.9.dp
) {
    Canvas(modifier) {
        drawGlyph(glyph, tint, strokeWidth.toPx())
    }
}

private fun DrawScope.drawGlyph(glyph: ErpIconGlyph, color: Color, sw: Float) {
    val w = size.width
    val h = size.height
    val s = minOf(w, h)
    val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * w, y * h)

    when (glyph) {
        ErpIconGlyph.Home -> {
            val path = Path().apply {
                moveTo(0.12f * w, 0.5f * h)
                lineTo(0.5f * w, 0.18f * h)
                lineTo(0.88f * w, 0.5f * h)
            }
            drawPath(path, color, style = stroke)
            val body = Path().apply {
                moveTo(0.22f * w, 0.46f * h)
                lineTo(0.22f * w, 0.84f * h)
                lineTo(0.78f * w, 0.84f * h)
                lineTo(0.78f * w, 0.46f * h)
            }
            drawPath(body, color, style = stroke)
            drawLine(color, p(0.43f, 0.84f), p(0.43f, 0.63f), sw, StrokeCap.Round)
            drawLine(color, p(0.43f, 0.63f), p(0.57f, 0.63f), sw, StrokeCap.Round)
            drawLine(color, p(0.57f, 0.63f), p(0.57f, 0.84f), sw, StrokeCap.Round)
        }
        ErpIconGlyph.Tasks -> {
            drawRoundRect(
                color, topLeft = p(0.16f, 0.14f), size = Size(0.68f * w, 0.72f * h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.1f * s), style = stroke
            )
            val check = Path().apply {
                moveTo(0.32f * w, 0.44f * h)
                lineTo(0.44f * w, 0.56f * h)
                lineTo(0.68f * w, 0.32f * h)
            }
            drawPath(check, color, style = stroke)
            drawLine(color, p(0.32f, 0.68f), p(0.58f, 0.68f), sw * 0.9f, StrokeCap.Round)
        }
        ErpIconGlyph.Projects -> {
            drawRoundRect(
                color, topLeft = p(0.14f, 0.2f), size = Size(0.32f * w, 0.28f * h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.07f * s), style = stroke
            )
            drawRoundRect(
                color, topLeft = p(0.54f, 0.2f), size = Size(0.32f * w, 0.28f * h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.07f * s), style = stroke
            )
            drawRoundRect(
                color, topLeft = p(0.14f, 0.56f), size = Size(0.32f * w, 0.28f * h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.07f * s), style = stroke
            )
            drawRoundRect(
                color, topLeft = p(0.54f, 0.56f), size = Size(0.32f * w, 0.28f * h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.07f * s), style = stroke
            )
        }
        ErpIconGlyph.Warehouse -> {
            val roof = Path().apply {
                moveTo(0.1f * w, 0.44f * h)
                lineTo(0.5f * w, 0.16f * h)
                lineTo(0.9f * w, 0.44f * h)
            }
            drawPath(roof, color, style = stroke)
            drawRoundRect(
                color, topLeft = p(0.18f, 0.44f), size = Size(0.64f * w, 0.4f * h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.06f * s), style = stroke
            )
            drawLine(color, p(0.38f, 0.84f), p(0.38f, 0.62f), sw, StrokeCap.Round)
            drawLine(color, p(0.38f, 0.62f), p(0.62f, 0.62f), sw, StrokeCap.Round)
            drawLine(color, p(0.62f, 0.62f), p(0.62f, 0.84f), sw, StrokeCap.Round)
        }
        ErpIconGlyph.Layers -> {
            val top = Path().apply {
                moveTo(0.5f * w, 0.16f * h)
                lineTo(0.88f * w, 0.36f * h)
                lineTo(0.5f * w, 0.56f * h)
                lineTo(0.12f * w, 0.36f * h)
                close()
            }
            drawPath(top, color, style = stroke)
            drawLine(color, p(0.12f, 0.52f), p(0.5f, 0.72f), sw, StrokeCap.Round)
            drawLine(color, p(0.5f, 0.72f), p(0.88f, 0.52f), sw, StrokeCap.Round)
            drawLine(color, p(0.12f, 0.68f), p(0.5f, 0.88f), sw, StrokeCap.Round)
            drawLine(color, p(0.5f, 0.88f), p(0.88f, 0.68f), sw, StrokeCap.Round)
        }
        ErpIconGlyph.Cart -> {
            drawLine(color, p(0.12f, 0.2f), p(0.26f, 0.2f), sw, StrokeCap.Round)
            drawLine(color, p(0.26f, 0.2f), p(0.38f, 0.62f), sw, StrokeCap.Round)
            drawLine(color, p(0.38f, 0.62f), p(0.82f, 0.62f), sw, StrokeCap.Round)
            drawLine(color, p(0.32f, 0.34f), p(0.88f, 0.34f), sw, StrokeCap.Round)
            drawCircle(color, radius = 0.055f * s, center = p(0.46f, 0.8f), style = stroke)
            drawCircle(color, radius = 0.055f * s, center = p(0.76f, 0.8f), style = stroke)
        }
        ErpIconGlyph.Documents -> {
            val path = Path().apply {
                moveTo(0.26f * w, 0.12f * h)
                lineTo(0.6f * w, 0.12f * h)
                lineTo(0.78f * w, 0.3f * h)
                lineTo(0.78f * w, 0.88f * h)
                lineTo(0.26f * w, 0.88f * h)
                close()
            }
            drawPath(path, color, style = stroke)
            drawLine(color, p(0.6f, 0.12f), p(0.6f, 0.3f), sw, StrokeCap.Round)
            drawLine(color, p(0.6f, 0.3f), p(0.78f, 0.3f), sw, StrokeCap.Round)
            drawLine(color, p(0.38f, 0.52f), p(0.66f, 0.52f), sw * 0.85f, StrokeCap.Round)
            drawLine(color, p(0.38f, 0.68f), p(0.58f, 0.68f), sw * 0.85f, StrokeCap.Round)
        }
        ErpIconGlyph.Bell -> {
            val path = Path().apply {
                moveTo(0.24f * w, 0.66f * h)
                lineTo(0.24f * w, 0.44f * h)
                cubicTo(0.24f * w, 0.26f * h, 0.35f * w, 0.16f * h, 0.5f * w, 0.16f * h)
                cubicTo(0.65f * w, 0.16f * h, 0.76f * w, 0.26f * h, 0.76f * w, 0.44f * h)
                lineTo(0.76f * w, 0.66f * h)
                lineTo(0.84f * w, 0.74f * h)
                lineTo(0.16f * w, 0.74f * h)
                close()
            }
            drawPath(path, color, style = stroke)
            drawLine(color, p(0.42f, 0.84f), p(0.58f, 0.84f), sw, StrokeCap.Round)
        }
        ErpIconGlyph.Shield -> {
            val path = Path().apply {
                moveTo(0.5f * w, 0.12f * h)
                lineTo(0.82f * w, 0.26f * h)
                lineTo(0.82f * w, 0.5f * h)
                cubicTo(0.82f * w, 0.72f * h, 0.68f * w, 0.84f * h, 0.5f * w, 0.9f * h)
                cubicTo(0.32f * w, 0.84f * h, 0.18f * w, 0.72f * h, 0.18f * w, 0.5f * h)
                lineTo(0.18f * w, 0.26f * h)
                close()
            }
            drawPath(path, color, style = stroke)
            val check = Path().apply {
                moveTo(0.36f * w, 0.5f * h)
                lineTo(0.47f * w, 0.61f * h)
                lineTo(0.66f * w, 0.4f * h)
            }
            drawPath(check, color, style = stroke)
        }
        ErpIconGlyph.User -> {
            drawCircle(color, radius = 0.15f * s, center = p(0.5f, 0.36f), style = stroke)
            val path = Path().apply {
                moveTo(0.2f * w, 0.86f * h)
                cubicTo(0.2f * w, 0.68f * h, 0.34f * w, 0.6f * h, 0.5f * w, 0.6f * h)
                cubicTo(0.66f * w, 0.6f * h, 0.8f * w, 0.68f * h, 0.8f * w, 0.86f * h)
            }
            drawPath(path, color, style = stroke)
        }
        ErpIconGlyph.Search -> {
            drawCircle(color, radius = 0.26f * s, center = p(0.44f, 0.44f), style = stroke)
            drawLine(color, p(0.64f, 0.64f), p(0.84f, 0.84f), sw, StrokeCap.Round)
        }
        ErpIconGlyph.Plus -> {
            drawLine(color, p(0.5f, 0.18f), p(0.5f, 0.82f), sw, StrokeCap.Round)
            drawLine(color, p(0.18f, 0.5f), p(0.82f, 0.5f), sw, StrokeCap.Round)
        }
        ErpIconGlyph.Refresh -> {
            drawArc(
                color = color, startAngle = 40f, sweepAngle = 280f, useCenter = false,
                topLeft = p(0.18f, 0.18f), size = Size(0.64f * w, 0.64f * h),
                style = stroke
            )
            val head = Path().apply {
                moveTo(0.76f * w, 0.14f * h)
                lineTo(0.82f * w, 0.36f * h)
                lineTo(0.6f * w, 0.32f * h)
            }
            drawPath(head, color, style = stroke)
        }
        ErpIconGlyph.Logout -> {
            drawRoundRect(
                color, topLeft = p(0.16f, 0.16f), size = Size(0.4f * w, 0.68f * h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.06f * s), style = stroke
            )
            drawLine(color, p(0.5f, 0.5f), p(0.86f, 0.5f), sw, StrokeCap.Round)
            drawLine(color, p(0.72f, 0.36f), p(0.86f, 0.5f), sw, StrokeCap.Round)
            drawLine(color, p(0.72f, 0.64f), p(0.86f, 0.5f), sw, StrokeCap.Round)
        }
        ErpIconGlyph.ChevronRight -> {
            drawLine(color, p(0.4f, 0.24f), p(0.64f, 0.5f), sw, StrokeCap.Round)
            drawLine(color, p(0.64f, 0.5f), p(0.4f, 0.76f), sw, StrokeCap.Round)
        }
        ErpIconGlyph.ChevronLeft -> {
            drawLine(color, p(0.6f, 0.24f), p(0.36f, 0.5f), sw, StrokeCap.Round)
            drawLine(color, p(0.36f, 0.5f), p(0.6f, 0.76f), sw, StrokeCap.Round)
        }
        ErpIconGlyph.Moon -> {
            val path = Path().apply {
                moveTo(0.62f * w, 0.16f * h)
                cubicTo(0.36f * w, 0.22f * h, 0.24f * w, 0.44f * h, 0.32f * w, 0.66f * h)
                cubicTo(0.4f * w, 0.86f * h, 0.66f * w, 0.9f * h, 0.84f * w, 0.74f * h)
                cubicTo(0.6f * w, 0.72f * h, 0.46f * w, 0.44f * h, 0.62f * w, 0.16f * h)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        ErpIconGlyph.Sun -> {
            drawCircle(color, radius = 0.2f * s, center = p(0.5f, 0.5f), style = stroke)
            val rays = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
            rays.forEach { angle ->
                val rad = angle * kotlin.math.PI.toFloat() / 180f
                val dx = kotlin.math.cos(rad)
                val dy = kotlin.math.sin(rad)
                drawLine(
                    color,
                    Offset(0.5f * w + dx * 0.32f * w, 0.5f * h + dy * 0.32f * h),
                    Offset(0.5f * w + dx * 0.42f * w, 0.5f * h + dy * 0.42f * h),
                    sw, StrokeCap.Round
                )
            }
        }
        ErpIconGlyph.CheckCircle -> {
            drawCircle(color, radius = 0.36f * s, center = p(0.5f, 0.5f), style = stroke)
            val check = Path().apply {
                moveTo(0.34f * w, 0.5f * h)
                lineTo(0.46f * w, 0.62f * h)
                lineTo(0.68f * w, 0.38f * h)
            }
            drawPath(check, color, style = stroke)
        }
        ErpIconGlyph.Clock -> {
            drawCircle(color, radius = 0.36f * s, center = p(0.5f, 0.5f), style = stroke)
            drawLine(color, p(0.5f, 0.3f), p(0.5f, 0.52f), sw, StrokeCap.Round)
            drawLine(color, p(0.5f, 0.52f), p(0.66f, 0.6f), sw, StrokeCap.Round)
        }
        ErpIconGlyph.Alert -> {
            val path = Path().apply {
                moveTo(0.5f * w, 0.16f * h)
                lineTo(0.9f * w, 0.82f * h)
                lineTo(0.1f * w, 0.82f * h)
                close()
            }
            drawPath(path, color, style = stroke)
            drawLine(color, p(0.5f, 0.38f), p(0.5f, 0.58f), sw, StrokeCap.Round)
            drawCircle(color, radius = sw * 0.62f, center = p(0.5f, 0.69f))
        }
        ErpIconGlyph.Sparkle -> {
            val path = Path().apply {
                moveTo(0.5f * w, 0.12f * h)
                cubicTo(0.56f * w, 0.38f * h, 0.62f * w, 0.44f * h, 0.88f * w, 0.5f * h)
                cubicTo(0.62f * w, 0.56f * h, 0.56f * w, 0.62f * h, 0.5f * w, 0.88f * h)
                cubicTo(0.44f * w, 0.62f * h, 0.38f * w, 0.56f * h, 0.12f * w, 0.5f * h)
                cubicTo(0.38f * w, 0.44f * h, 0.44f * w, 0.38f * h, 0.5f * w, 0.12f * h)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        ErpIconGlyph.Menu -> {
            drawLine(color, p(0.16f, 0.3f), p(0.84f, 0.3f), sw, StrokeCap.Round)
            drawLine(color, p(0.16f, 0.5f), p(0.84f, 0.5f), sw, StrokeCap.Round)
            drawLine(color, p(0.16f, 0.7f), p(0.84f, 0.7f), sw, StrokeCap.Round)
        }
        ErpIconGlyph.Filter -> {
            val path = Path().apply {
                moveTo(0.14f * w, 0.24f * h)
                lineTo(0.86f * w, 0.24f * h)
                lineTo(0.58f * w, 0.54f * h)
                lineTo(0.58f * w, 0.82f * h)
                lineTo(0.42f * w, 0.74f * h)
                lineTo(0.42f * w, 0.54f * h)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        ErpIconGlyph.Send -> {
            val path = Path().apply {
                moveTo(0.14f * w, 0.5f * h)
                lineTo(0.86f * w, 0.18f * h)
                lineTo(0.62f * w, 0.84f * h)
                lineTo(0.5f * w, 0.56f * h)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        ErpIconGlyph.Close -> {
            drawLine(color, p(0.26f, 0.26f), p(0.74f, 0.74f), sw, StrokeCap.Round)
            drawLine(color, p(0.74f, 0.26f), p(0.26f, 0.74f), sw, StrokeCap.Round)
        }
        ErpIconGlyph.Grid -> {
            drawLine(color, p(0.16f, 0.34f), p(0.84f, 0.34f), sw, StrokeCap.Round)
            drawLine(color, p(0.16f, 0.5f), p(0.84f, 0.5f), sw, StrokeCap.Round)
            drawLine(color, p(0.16f, 0.66f), p(0.84f, 0.66f), sw, StrokeCap.Round)
            drawCircle(color, radius = sw * 0.6f, center = p(0.2f, 0.18f))
            drawCircle(color, radius = sw * 0.6f, center = p(0.2f, 0.82f))
        }
        ErpIconGlyph.TrendUp -> {
            drawLine(color, p(0.14f, 0.76f), p(0.4f, 0.5f), sw, StrokeCap.Round)
            drawLine(color, p(0.4f, 0.5f), p(0.58f, 0.66f), sw, StrokeCap.Round)
            drawLine(color, p(0.58f, 0.66f), p(0.86f, 0.28f), sw, StrokeCap.Round)
            drawLine(color, p(0.68f, 0.26f), p(0.86f, 0.28f), sw, StrokeCap.Round)
            drawLine(color, p(0.86f, 0.28f), p(0.86f, 0.46f), sw, StrokeCap.Round)
        }
        ErpIconGlyph.MoreHoriz -> {
            drawCircle(color, radius = sw * 0.75f, center = p(0.24f, 0.5f))
            drawCircle(color, radius = sw * 0.75f, center = p(0.5f, 0.5f))
            drawCircle(color, radius = sw * 0.75f, center = p(0.76f, 0.5f))
        }
        ErpIconGlyph.Attachment -> {
            val path = Path().apply {
                moveTo(0.64f * w, 0.18f * h)
                lineTo(0.3f * w, 0.52f * h)
                cubicTo(0.18f * w, 0.64f * h, 0.32f * w, 0.8f * h, 0.46f * w, 0.66f * h)
                lineTo(0.78f * w, 0.34f * h)
                cubicTo(0.92f * w, 0.2f * h, 0.76f * w, 0.04f * h, 0.62f * w, 0.18f * h)
            }
            drawPath(path, color, style = stroke)
        }
    }
}

/**
 * Единая шапка внутренней страницы: иконка модуля, заголовок, пояснение
 * и опциональная кнопка «Назад».
 */
@Composable
fun PageHeader(
    title: String,
    glyph: ErpIconGlyph,
    gradient: List<Color> = Gradients.Primary,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            ErpIconButton(
                glyph = ErpIconGlyph.ChevronLeft,
                contentDescription = "Назад",
                onClick = onBack
            )
            Spacer(Modifier.width(12.dp))
        }
        IconBadge(glyph, gradient = gradient, size = 36.dp, iconSize = 18.dp)
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions?.let { Row(verticalAlignment = Alignment.CenterVertically, content = it) }
    }
}


/**
 * Единое поле ввода в стиле системы: скругление, мягкая заливка,
 * иконка слева и подпись.
 */
@Composable
fun ErpTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    glyph: ErpIconGlyph? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 5,
    isPassword: Boolean = false,
    placeholder: String? = null,
    supportingText: String? = null
) {
    val scheme = MaterialTheme.colorScheme
    val palette = LocalBrandPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        supportingText = supportingText?.let { { Text(it, style = MaterialTheme.typography.labelSmall) } },
        leadingIcon = glyph?.let { g ->
            { ErpIcon(g, scheme.onSurfaceVariant, Modifier.size(18.dp)) }
        },
        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(6.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outline,
            focusedContainerColor = if (palette.isDark) scheme.surfaceContainerHigh else scheme.surface,
            unfocusedContainerColor = if (palette.isDark) scheme.surfaceContainer else scheme.surface,
            focusedLabelColor = scheme.primary,
            unfocusedLabelColor = scheme.onSurfaceVariant
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Настраиваемое диалоговое окно в фирменном стиле: градиентная «шапка»,
 * стеклянная поверхность и собственные кнопки.
 */
@Composable
fun ErpDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    glyph: ErpIconGlyph = ErpIconGlyph.Sparkle,
    gradient: List<Color> = Gradients.Primary,
    width: Dp = 620.dp,
    maxBodyHeight: Dp = 620.dp,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    confirmGradient: List<Color> = Gradients.Primary,
    dismissText: String = "Отмена",
    extraActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val palette = LocalBrandPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = modifier
                .padding(horizontal = 24.dp, vertical = 18.dp)
                .widthIn(max = width)
                .fillMaxWidth()
                // Диалог единственный «парящий» слой, поэтому тень остаётся только здесь.
                .shadow(16.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(palette.glassOverlay)
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(12.dp))
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (glyph != ErpIconGlyph.Sparkle) {
                        IconBadge(glyph, gradient = gradient, size = 32.dp, iconSize = 16.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurface
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    ErpIconButton(
                        glyph = ErpIconGlyph.Close,
                        contentDescription = "Закрыть",
                        onClick = onDismiss
                    )
                }
                HorizontalDivider(color = scheme.outlineVariant)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxBodyHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )

                HorizontalDivider(color = scheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (extraActions != null) {
                        extraActions()
                        Spacer(Modifier.width(10.dp))
                    }
                    SoftButton(text = dismissText, onClick = onDismiss)
                    if (confirmText != null && onConfirm != null) {
                        Spacer(Modifier.width(10.dp))
                        GradientButton(
                            text = confirmText,
                            onClick = onConfirm,
                            enabled = confirmEnabled,
                            gradient = confirmGradient
                        )
                    }
                }
            }
        }
    }
}

/**
 * Служебная кнопка-иконка (третий уровень иерархии: назад, закрыть, обновить).
 * Без рамки в покое; наведение даёт подложку, фокус рамку 2 dp.
 * Область нажатия 36 dp, у иконки есть доступное имя.
 */
@Composable
fun ErpIconButton(
    glyph: ErpIconGlyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(shape)
            .background(
                when {
                    !enabled -> Color.Transparent
                    pressed -> scheme.surfaceContainerHighest
                    hovered -> scheme.surfaceContainerHigh
                    else -> Color.Transparent
                }
            )
            .erpInteractive(interaction, shape, enabled)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semantics { this.contentDescription = contentDescription; role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        ErpIcon(
            glyph = glyph,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp)
        )
    }
}
