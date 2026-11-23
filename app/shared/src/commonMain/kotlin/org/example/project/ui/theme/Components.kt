package org.example.project.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults

/** Мягкий градиентный фон страницы с цветными «свечениями». */
@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = LocalBrandPalette.current
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(palette.pageGradient))
    ) {
        // Цветные размытые пятна создают глубину без тяжёлого blur-эффекта.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(palette.blobs[0], Color.Transparent),
                        center = Offset(220f, 80f),
                        radius = 1400f
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(palette.blobs[1], Color.Transparent),
                        center = Offset(1800f, 320f),
                        radius = 1600f
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(palette.blobs[2], Color.Transparent),
                        center = Offset(900f, 1500f),
                        radius = 1500f
                    )
                )
        )
        // Тонкая «сетка» для техничности.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color.Transparent, scheme.outlineVariant.copy(alpha = 0.35f), Color.Transparent)
                    )
                )
                .alpha(0.25f)
        )
        content()
    }
}

/**
 * «Стеклянная» карточка: полупрозрачная поверхность, тонкая градиентная
 * рамка, мягкая тень и подсветка при наведении.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    gradient: List<Color>? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 18.dp,
    elevated: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalBrandPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val lift by animateFloatAsState(
        targetValue = if (hovered && onClick != null) 1f else 0f,
        animationSpec = tween(220),
        label = "glassLift"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = tween(120),
        label = "glassPress"
    )

    val backgroundBrush = if (gradient != null) {
        Brush.linearGradient(gradient)
    } else {
        Brush.linearGradient(
            listOf(
                palette.glassOverlay,
                palette.glassOverlay.copy(alpha = (palette.glassOverlay.alpha * 0.82f).coerceAtLeast(0.1f))
            )
        )
    }

    Box(
        modifier = modifier
            .scale(pressScale)
            .clip(shape)
            .background(backgroundBrush, shape)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        palette.glassBorder[0].copy(alpha = 0.55f + 0.35f * lift),
                        palette.glassBorder[1]
                    )
                ),
                shape = shape
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
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

/** Кнопка с градиентной заливкой и подсветкой. */
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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    val lift by animateFloatAsState(
        targetValue = if (hovered && enabled) 1f else 0f,
        animationSpec = tween(200),
        label = "btnLift"
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = if (enabled) (4 + 8 * lift).dp else 0.dp,
                shape = shape,
                clip = false
            )
            .clip(shape)
            .background(
                Brush.linearGradient(
                    if (enabled) gradient
                    else gradient.map { it.copy(alpha = 0.35f) }
                ),
                shape
            )
            .border(
                1.dp,
                Color.White.copy(alpha = 0.18f + 0.2f * lift),
                shape
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 9.dp else 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                ErpIcon(
                    glyph = leadingIcon,
                    tint = Color.White.copy(alpha = if (enabled) 1f else 0.6f),
                    modifier = Modifier.size(if (compact) 15.dp else 17.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = if (enabled) 1f else 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Второстепенная «стеклянная» кнопка. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ErpIconGlyph? = null,
    tint: Color? = null
) {
    val palette = LocalBrandPalette.current
    val scheme = MaterialTheme.colorScheme
    val accent = tint ?: scheme.primary
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    val bg by animateFloatAsState(
        targetValue = if (hovered) 0.16f else 0.08f,
        animationSpec = tween(180),
        label = "softBtn"
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(accent.copy(alpha = if (palette.isDark) bg * 1.4f else bg), shape)
            .border(1.dp, accent.copy(alpha = 0.22f), shape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                ErpIcon(glyph = leadingIcon, tint = accent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) accent else accent.copy(alpha = 0.45f),
                maxLines = 1
            )
        }
    }
}

/** Заголовок секции с необязательным счётчиком и действием справа. */
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
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.35f))))
            )
            Spacer(Modifier.width(10.dp))
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

/** Компактный бейдж-«таблетка». */
@Composable
fun PillBadge(
    text: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    filled: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) accent else accent.copy(alpha = 0.13f))
            .border(1.dp, accent.copy(alpha = if (filled) 0.6f else 0.2f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) Color.White else accent
        )
    }
}

/** Круглый/скруглённый аватар с инициалами и градиентом по имени. */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    gradient: List<Color>? = null
) {
    val colors = gradient ?: gradientFor(name)
    val initials = remember(name) {
        name.trim().split(" ", ".").filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercaseChar().toString() }
            .joinToString("")
            .ifBlank { "?" }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.34f))
            .background(Brush.linearGradient(colors)),
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

private fun gradientFor(seed: String): List<Color> {
    val palettes = listOf(
        Gradients.Primary, Gradients.Ocean, Gradients.Aurora,
        Gradients.Sunset, Gradients.Berry, Gradients.Emerald, Gradients.AmberGlow
    )
    var hash = 7
    seed.forEach { hash = hash * 31 + it.code }
    return palettes[(hash % palettes.size + palettes.size) % palettes.size]
}

/** Иконка в градиентной плашке. */
@Composable
fun IconBadge(
    glyph: ErpIconGlyph,
    modifier: Modifier = Modifier,
    gradient: List<Color> = Gradients.Primary,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center
    ) {
        ErpIcon(glyph = glyph, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/** Пустое состояние. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    glyph: ErpIconGlyph = ErpIconGlyph.Sparkle,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.18f), accent.copy(alpha = 0.04f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            ErpIcon(glyph = glyph, tint = accent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
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

/** Небольшой индикатор загрузки в виде пульсирующих градиентных точек. */
@Composable
fun GradientSpinner(modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val colors = Gradients.Primary
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(size))
                .background(Brush.sweepGradient(colors + colors.first()))
                .alpha(0.2f)
        )
        Box(
            Modifier
                .size(size * 0.55f)
                .clip(RoundedCornerShape(size))
                .background(Brush.linearGradient(colors))
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
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.linearGradient(
                        listOf(
                            scheme.surfaceContainerHigh,
                            scheme.surfaceVariant.copy(alpha = 0.55f),
                            scheme.surfaceContainerHigh
                        )
                    )
                )
        )
    }
}

/** Декоративное кольцо-градиент (используется на герое и в login). */
@Composable
fun GradientRing(modifier: Modifier = Modifier, colors: List<Color> = Gradients.Aurora) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.05f
        drawCircle(
            brush = Brush.sweepGradient(colors + colors.first()),
            radius = size.minDimension / 2f - stroke / 2f,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
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
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                ErpIcon(
                    glyph = ErpIconGlyph.ChevronLeft,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        IconBadge(glyph, gradient = gradient, size = 40.dp, iconSize = 20.dp)
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
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outline.copy(alpha = 0.7f),
            focusedContainerColor = if (palette.isDark) scheme.surfaceContainerHigh else scheme.surfaceContainerLow,
            unfocusedContainerColor = if (palette.isDark) scheme.surfaceContainer.copy(alpha = 0.6f)
            else scheme.surfaceContainerLow.copy(alpha = 0.7f),
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
    width: Dp = 520.dp,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    confirmGradient: List<Color> = Gradients.Primary,
    dismissText: String = "Отмена",
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val palette = LocalBrandPalette.current
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = modifier
                .widthIn(max = width)
                .fillMaxWidth()
                .shadow(28.dp, RoundedCornerShape(26.dp))
                .clip(RoundedCornerShape(26.dp))
                .background(palette.glassOverlay)
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(26.dp))
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(gradient))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        ErpIcon(glyph, Color.White, Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        ErpIcon(ErpIconGlyph.Close, Color.White, Modifier.size(15.dp))
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 18.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
