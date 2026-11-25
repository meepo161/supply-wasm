package org.example.project.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.SoftButton

/**
 * Режимы отображения списков (перенесено из supply2 ViewModeToggle).
 */
enum class ListViewMode(val displayName: String) {
    TABLE("Таблица"),
    COMPACT("Компактный список"),
    CARDS("Карточки")
}

@Composable
fun ViewModeToggle(
    current: ListViewMode,
    supported: Set<ListViewMode> = setOf(ListViewMode.TABLE, ListViewMode.COMPACT),
    onChange: (ListViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        SoftButton(
            text = "Вид: ${current.displayName}",
            onClick = { expanded = true },
            leadingIcon = ErpIconGlyph.Grid
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ListViewMode.entries.filter { it in supported }.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = mode.displayName,
                            color = if (mode == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    onClick = {
                        onChange(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}
