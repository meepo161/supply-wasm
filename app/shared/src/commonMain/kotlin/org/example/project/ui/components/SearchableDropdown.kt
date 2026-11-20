package org.example.project.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SearchableDropdown(
    items: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: String,
    itemText: (T) -> String,
    modifier: Modifier = Modifier,
    emptyText: String = "Нет доступных вариантов",
    onClear: (() -> Unit)? = null,
    clearText: String = "Не выбрано"
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filteredItems = items.filter { itemText(it).contains(query.trim(), ignoreCase = true) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = it
            if (it) query = ""
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = if (expanded) query else selected?.let(itemText).orEmpty(),
            onValueChange = {
                query = it
                expanded = true
            },
            label = { Text(label) },
            placeholder = { Text("Выберите или начните ввод") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (onClear != null) {
                DropdownMenuItem(
                    text = { Text(clearText) },
                    onClick = {
                        onClear()
                        expanded = false
                    }
                )
            }
            if (filteredItems.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(emptyText) },
                    enabled = false,
                    onClick = {}
                )
            } else {
                filteredItems.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(itemText(item)) },
                        onClick = {
                            onSelect(item)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
