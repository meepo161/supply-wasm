package org.example.project.ui.bom

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import org.example.project.dto.BomLineDto
import org.example.project.dto.BomRevisionStatus
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ProductModelStatus
import org.example.project.dto.UserOptionDto
import org.example.project.dto.WarehouseDto
import org.example.project.ui.components.SearchableDropdown

@Composable
fun UserOptionDropdown(
    users: List<UserOptionDto>,
    selected: UserOptionDto?,
    onSelect: (UserOptionDto) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Сотрудник"
) {
    SearchableDropdown(
        items = users,
        selected = selected,
        onSelect = onSelect,
        onClear = onClear,
        label = label,
        itemText = { it.fullName },
        modifier = modifier,
        emptyText = "Нет активных сотрудников"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductModelStatusDropdown(
    selected: ProductModelStatus,
    onSelect: (ProductModelStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected.localizedName(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Статус модели") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ProductModelStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.localizedName()) },
                    onClick = {
                        onSelect(status)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BomRevisionStatusDropdown(
    selected: BomRevisionStatus?,
    onSelect: (BomRevisionStatus?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Статус ревизии"
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(null) + BomRevisionStatus.entries
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.localizedName() ?: "Все",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status?.localizedName() ?: "Все") },
                    onClick = {
                        onSelect(status)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BomNomenclatureDropdown(
    items: List<NomenclatureDto>,
    selected: NomenclatureDto?,
    onSelect: (NomenclatureDto) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Номенклатура"
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.code} · ${it.name}" } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text("${item.code} · ${item.name}") },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BomWarehouseDropdown(
    warehouses: List<WarehouseDto>,
    selected: WarehouseDto?,
    onSelect: (WarehouseDto) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Склад",
    allowEmpty: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val options = if (allowEmpty) listOf(null) + warehouses else warehouses
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.code} · ${it.name}" } ?: "Не выбран",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { warehouse ->
                DropdownMenuItem(
                    text = { Text(warehouse?.let { "${it.code} · ${it.name}" } ?: "Не выбран") },
                    onClick = {
                        warehouse?.let(onSelect)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BomLineDropdown(
    lines: List<BomLineDto>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Родительская строка"
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(null) + lines
    val selected = lines.find { it.id == selectedId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.let { "#${it.id} ${it.childNomenclatureId}" } ?: "Корень",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { line ->
                DropdownMenuItem(
                    text = { Text(line?.let { "#${it.id} номенклатура ${it.childNomenclatureId}" } ?: "Корень") },
                    onClick = {
                        onSelect(line?.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
