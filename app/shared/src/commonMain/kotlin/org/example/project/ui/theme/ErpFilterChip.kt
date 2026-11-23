package org.example.project.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Плашка-фильтр с предсказуемыми состояниями.
 *
 * Заменяет `androidx.compose.material3.FilterChip`: в используемой версии Material3
 * наведение на невыбранную плашку давало серый фон с белым текстом — подпись
 * становилась нечитаемой. Здесь наведение только слегка подкрашивает фон акцентом,
 * а текст остаётся тёмным. Сигнатура совпадает с используемым подмножеством
 * параметров FilterChip, поэтому вызовы не меняются.
 */
@Composable
fun ErpFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)

    val background = when {
        selected && hovered && enabled -> accent.copy(alpha = 0.20f)
        selected -> accent.copy(alpha = 0.14f)
        hovered && enabled -> accent.copy(alpha = 0.07f)
        else -> scheme.surface.copy(alpha = 0.6f)
    }
    val borderColor = when {
        selected -> accent.copy(alpha = 0.45f)
        hovered && enabled -> accent.copy(alpha = 0.30f)
        else -> scheme.outlineVariant
    }
    val contentColor = if (selected) accent else scheme.onSurface

    Box(
        modifier = modifier
            .heightIn(min = 32.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .erpInteractive(interaction, shape, enabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(
                MaterialTheme.typography.labelLarge.copy(
                    color = contentColor,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
            ) {
                label()
            }
        }
    }
}
