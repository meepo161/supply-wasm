package org.example.project.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp

/*
 * Кнопки Material 3 в форме системы.
 *
 * Material 3 всегда рисует кнопки «таблеткой» и не берёт форму из темы, поэтому
 * экраны импортируют эти обёртки вместо androidx.compose.material3.*Button.
 * Сигнатуры совпадают, меняется только оформление по умолчанию:
 * - Button: основное действие, сплошная заливка основным цветом, без тени;
 * - OutlinedButton: второстепенное действие, рамка цветом действия;
 * - TextButton: служебное действие (отмена, обновить, показать ещё).
 * У всех скругление 6 dp и курсор-рука над активной кнопкой.
 */

private val ErpButtonShape = RoundedCornerShape(6.dp)

private fun Modifier.handCursor(enabled: Boolean): Modifier =
    pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ErpButtonShape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = androidx.compose.material3.Button(
    onClick = onClick,
    modifier = modifier.handCursor(enabled),
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content
)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ErpButtonShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BorderStroke(
        1.dp,
        if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.outlineVariant
    ),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = androidx.compose.material3.OutlinedButton(
    onClick = onClick,
    modifier = modifier.handCursor(enabled),
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content
)

/**
 * Вкладка: выбранная основным цветом, невыбранные нейтральным текстом.
 * В Material 3 по умолчанию обе синие, и невыбранные выглядят как ссылки.
 */
@Composable
fun Tab(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    selectedContentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    unselectedContentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    interactionSource: MutableInteractionSource? = null
) = androidx.compose.material3.Tab(
    selected = selected,
    onClick = onClick,
    modifier = modifier.handCursor(enabled),
    enabled = enabled,
    text = text?.let { content ->
        {
            androidx.compose.material3.ProvideTextStyle(
                MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold
                    else androidx.compose.ui.text.font.FontWeight.Normal
                ),
                content
            )
        }
    },
    icon = icon,
    selectedContentColor = selectedContentColor,
    unselectedContentColor = unselectedContentColor,
    interactionSource = interactionSource
)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ErpButtonShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = androidx.compose.material3.TextButton(
    onClick = onClick,
    modifier = modifier.handCursor(enabled),
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content
)
