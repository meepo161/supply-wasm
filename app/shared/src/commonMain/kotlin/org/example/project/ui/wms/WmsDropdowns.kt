package org.example.project.ui.wms

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.NomenclatureType
import org.example.project.dto.ReceiptDto
import org.example.project.dto.ReceiptSourceType
import org.example.project.dto.ReceiptStatus
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
import org.example.project.dto.WriteOffReasonType
import org.example.project.dto.WriteOffStatus
import org.example.project.dto.InventoryScopeType
import org.example.project.dto.InventoryStatus
import org.example.project.ui.components.SearchableDropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarehouseDropdown(
    warehouses: List<WarehouseDto>,
    selected: WarehouseDto?,
    onSelect: (WarehouseDto) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Склад",
    placeholder: String = "Не выбран",
    isError: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.code} · ${it.name}" } ?: placeholder,
            onValueChange = {},
            readOnly = true,
            isError = isError,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            warehouses.forEach { warehouse ->
                DropdownMenuItem(
                    text = { Text("${warehouse.code} · ${warehouse.name}") },
                    onClick = {
                        onSelect(warehouse)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Список длиннее этого порога получает строку поиска по коду. */
private const val SEARCHABLE_LIST_THRESHOLD = 8

/**
 * Выпадающий список с необязательным поиском. Выбранное значение показывается с начала строки
 * (курсор в нуле), поэтому длинное «код · название» не обрезается слева (W-14).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> WmsPickerField(
    items: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: String,
    fieldText: (T) -> String,
    menuText: (T) -> String,
    modifier: Modifier,
    emptyText: String,
    placeholder: String,
    searchable: Boolean,
    isError: Boolean = false,
    supportingText: String? = null,
    onClear: (() -> Unit)? = null,
    clearText: String = "Не выбрано",
    onCreateNew: (() -> Unit)? = null,
    createNewLabel: String = "+ Создать"
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val needle = query.text.trim()
    val filtered = if (searchable && needle.isNotEmpty()) items.filter { menuText(it).contains(needle, ignoreCase = true) } else items
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = it
            if (it) query = TextFieldValue("")
        },
        modifier = modifier
    ) {
        val editing = searchable && expanded
        val shown = if (editing) query else TextFieldValue(selected?.let(fieldText).orEmpty(), TextRange.Zero)
        OutlinedTextField(
            value = shown,
            onValueChange = {
                if (searchable) {
                    query = it
                    expanded = true
                }
            },
            readOnly = !searchable,
            isError = isError,
            label = { Text(label) },
            placeholder = { Text(if (searchable) "$placeholder · начните ввод" else placeholder) },
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = (if (searchable) Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable) else Modifier.menuAnchor())
                .fillMaxWidth(),
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
            if (filtered.isEmpty()) {
                DropdownMenuItem(text = { Text(emptyText) }, onClick = { expanded = false }, enabled = false)
            }
            filtered.forEach { item ->
                DropdownMenuItem(
                    text = { Text(menuText(item)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
            if (onCreateNew != null) {
                DropdownMenuItem(
                    text = { Text(createNewLabel) },
                    onClick = {
                        expanded = false
                        onCreateNew()
                    }
                )
            }
        }
    }
}

@Composable
fun LocationDropdown(
    locations: List<StorageLocationDto>,
    selected: StorageLocationDto?,
    onSelect: (StorageLocationDto) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Ячейка",
    /** Служебная транзитная ячейка IN-TRANSIT заполняется перемещениями сама — вручную её не выбирают. */
    includeTransit: Boolean = false,
    emptyText: String = "На складе нет подходящих ячеек",
    isError: Boolean = false,
    /** Показывать под полем склад, к которому относятся ячейки списка. */
    showWarehouse: Boolean = true,
    onClear: (() -> Unit)? = null,
    clearText: String = "Не выбрана",
    onCreateLocation: (() -> Unit)? = null
) {
    val options = if (includeTransit) locations else locations.filterNot { it.code == TRANSIT_LOCATION_CODE }
    val names = LocalWmsNames.current
    val warehouseIds = options.map { it.warehouseId }.distinct()
    val warehouseLabel = if (showWarehouse) {
        names.warehouseOrNull(selected?.warehouseId ?: warehouseIds.singleOrNull())?.let { "Склад: $it" }
    } else {
        null
    }
    // Если в списке ячейки нескольких складов, в пункте меню показываем код склада.
    val mixedWarehouses = warehouseIds.size > 1
    WmsPickerField(
        items = options,
        selected = selected,
        onSelect = onSelect,
        label = label,
        fieldText = { "${it.code} · ${it.name}" },
        menuText = { location ->
            val prefix = if (mixedWarehouses) {
                names.warehouseOrNull(location.warehouseId)?.substringBefore(" · ")?.let { "$it / " }.orEmpty()
            } else {
                ""
            }
            "$prefix${location.code} · ${location.name} (${location.kind.localizedName()})"
        },
        modifier = modifier,
        emptyText = emptyText,
        placeholder = "Не выбрана",
        searchable = options.size > SEARCHABLE_LIST_THRESHOLD,
        isError = isError,
        supportingText = warehouseLabel,
        onClear = onClear,
        clearText = clearText,
        onCreateNew = onCreateLocation,
        createNewLabel = "+ Добавить ячейку"
    )
}

/** Код служебной транзитной ячейки, которую сервер создаёт при первой отгрузке перемещения. */
internal const val TRANSIT_LOCATION_CODE = "IN-TRANSIT"

@Composable
fun NomenclatureDropdown(
    items: List<NomenclatureDto>,
    selected: NomenclatureDto?,
    onSelect: (NomenclatureDto) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Номенклатура",
    isError: Boolean = false,
    onCreateNomenclature: (() -> Unit)? = null
) {
    WmsPickerField(
        items = items,
        selected = selected,
        onSelect = onSelect,
        label = label,
        fieldText = { "${it.code} · ${it.name}" },
        menuText = { "${it.code} · ${it.name}" },
        modifier = modifier,
        emptyText = "Нет доступной номенклатуры",
        placeholder = "Выберите",
        searchable = true,
        isError = isError,
        onCreateNew = onCreateNomenclature,
        createNewLabel = "+ Создать номенклатуру"
    )
}

@Composable
fun ReceiptDropdown(
    receipts: List<ReceiptDto>,
    selected: ReceiptDto?,
    onSelect: (ReceiptDto) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Приёмка"
) {
    SearchableDropdown(
        items = receipts,
        selected = selected,
        onSelect = onSelect,
        label = label,
        itemText = { "${it.number} · ${it.status.localizedName()}" },
        modifier = modifier,
        emptyText = "Приёмки не найдены"
    )
}

@Composable
fun ReceiptStatusDropdown(
    selected: ReceiptStatus?,
    onSelect: (ReceiptStatus?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Статус"
) {
    SearchableDropdown(
        items = ReceiptStatus.entries,
        selected = selected,
        onSelect = onSelect,
        onClear = { onSelect(null) },
        clearText = "Все статусы",
        label = label,
        itemText = { it.localizedName() },
        modifier = modifier
    )
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
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
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
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
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
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
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
            label = { Text("Источник приёмки *") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            // D-61: приёмку по счёту оформляет снабжение из карточки счёта, вручную её не создают.
            ReceiptSourceType.entries.filter { it != ReceiptSourceType.INVOICE }.forEach { type ->
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
    modifier: Modifier = Modifier,
    label: String = "Основание резерва"
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
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
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
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
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
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
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
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
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
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
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
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteOffReasonTypeDropdown(
    selected: WriteOffReasonType,
    onSelect: (WriteOffReasonType) -> Unit,
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
            label = { Text("Причина списания") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            WriteOffReasonType.entries.forEach { type ->
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
fun WriteOffStatusDropdown(
    selected: WriteOffStatus?,
    onSelect: (WriteOffStatus?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Статус"
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(null) + WriteOffStatus.entries
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
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
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
fun InventoryScopeTypeDropdown(
    selected: InventoryScopeType,
    onSelect: (InventoryScopeType) -> Unit,
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
            label = { Text("Область инвентаризации") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            InventoryScopeType.entries.forEach { type ->
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
fun InventoryStatusDropdown(
    selected: InventoryStatus?,
    onSelect: (InventoryStatus?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Статус"
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(null) + InventoryStatus.entries
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
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
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
