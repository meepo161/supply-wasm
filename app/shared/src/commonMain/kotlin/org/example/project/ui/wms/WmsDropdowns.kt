package org.example.project.ui.wms

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
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.NomenclatureType
import org.example.project.dto.ReceiptSourceType
import org.example.project.dto.ReservationReasonType
import org.example.project.dto.ReservationStatus
import org.example.project.dto.StorageLocationDto
import org.example.project.dto.StorageLocationKind
import org.example.project.dto.StorageLocationPurpose
import org.example.project.dto.WarehouseDto
import org.example.project.dto.IssueStatus
import org.example.project.dto.ReturnInspectionDecision
import org.example.project.dto.ReturnStatus
import org.example.project.dto.TransferStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarehouseDropdown(
    warehouses: List<WarehouseDto>,
    selected: WarehouseDto?,
    onSelect: (WarehouseDto) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Склад"
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.code} — ${it.name}" } ?: "Все",
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
            warehouses.forEach { warehouse ->
                DropdownMenuItem(
                    text = { Text("${warehouse.code} — ${warehouse.name}") },
                    onClick = {
                        onSelect(warehouse)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationDropdown(
    locations: List<StorageLocationDto>,
    selected: StorageLocationDto?,
    onSelect: (StorageLocationDto) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Ячейка"
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.code} — ${it.name}" } ?: "Все",
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
            locations.forEach { location ->
                DropdownMenuItem(
                    text = { Text("${location.code} — ${location.name} (${location.kind.localizedName()})") },
                    onClick = {
                        onSelect(location)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NomenclatureDropdown(
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
            value = selected?.let { "${it.code} — ${it.name}" } ?: "Все",
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
                    text = { Text("${item.code} — ${item.name}") },
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
fun StorageLocationKindDropdown(
    selected: StorageLocationKind,
    onSelect: (StorageLocationKind) -> Unit,
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
            label = { Text("Тип ячейки") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            StorageLocationKind.entries.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind.localizedName()) },
                    onClick = {
                        onSelect(kind)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageLocationPurposeDropdown(
    selected: StorageLocationPurpose,
    onSelect: (StorageLocationPurpose) -> Unit,
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
            label = { Text("Назначение") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            StorageLocationPurpose.entries.forEach { purpose ->
                DropdownMenuItem(
                    text = { Text(purpose.localizedName()) },
                    onClick = {
                        onSelect(purpose)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NomenclatureTypeDropdown(
    selected: NomenclatureType,
    onSelect: (NomenclatureType) -> Unit,
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
            label = { Text("Тип номенклатуры") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            NomenclatureType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.localizedName()) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptSourceTypeDropdown(
    selected: ReceiptSourceType,
    onSelect: (ReceiptSourceType) -> Unit,
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
            label = { Text("Источник приёмки") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ReceiptSourceType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.localizedName()) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationReasonTypeDropdown(
    selected: ReservationReasonType,
    onSelect: (ReservationReasonType) -> Unit,
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
            label = { Text("Основание резерва") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ReservationReasonType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.localizedName()) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationStatusDropdown(
    selected: ReservationStatus?,
    onSelect: (ReservationStatus?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Статус"
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(null) + ReservationStatus.entries
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
fun TransferStatusDropdown(
    selected: TransferStatus?,
    onSelect: (TransferStatus?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Статус"
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(null) + TransferStatus.entries
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
fun IssueStatusDropdown(
    selected: IssueStatus?,
    onSelect: (IssueStatus?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Статус"
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(null) + IssueStatus.entries
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
fun ReturnStatusDropdown(
    selected: ReturnStatus?,
    onSelect: (ReturnStatus?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Статус"
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(null) + ReturnStatus.entries
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
fun ReturnInspectionDecisionDropdown(
    selected: ReturnInspectionDecision,
    onSelect: (ReturnInspectionDecision) -> Unit,
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
            label = { Text("Решение по строке") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ReturnInspectionDecision.entries.forEach { decision ->
                DropdownMenuItem(
                    text = { Text(decision.localizedName()) },
                    onClick = {
                        onSelect(decision)
                        expanded = false
                    }
                )
            }
        }
    }
}
