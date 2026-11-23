package org.example.project.ui.wms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.BarcodePackageType
import org.example.project.dto.CreateNomenclatureBarcodeRequest
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ReceiptSourceType
import org.example.project.dto.StorageLocationDto
import org.example.project.dto.WarehouseDto
import org.example.project.ui.projects.ErrorMessageCard

@Composable
fun CreateWarehouseDialog(
    form: CreateWarehouseForm,
    error: org.example.project.dto.ApiError?,
    onFormChange: (CreateWarehouseForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый склад") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                OutlinedTextField(
                    value = form.code,
                    onValueChange = { onFormChange(form.copy(code = it)) },
                    label = { Text("Код *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { onFormChange(form.copy(name = it)) },
                    label = { Text("Название *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.address,
                    onValueChange = { onFormChange(form.copy(address = it)) },
                    label = { Text("Адрес") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        },
        confirmButton = { Button(onClick = onCreate) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun CreateLocationDialog(
    form: CreateLocationForm,
    warehouses: List<WarehouseDto>,
    error: org.example.project.dto.ApiError?,
    onFormChange: (CreateLocationForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая ячейка") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                if (warehouses.isEmpty()) {
                    Text(
                        text = "Нет доступных складов. Сначала создайте склад.",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    WarehouseDropdown(
                        warehouses = warehouses,
                        selected = warehouses.find { it.id == form.warehouseId },
                        onSelect = { onFormChange(form.copy(warehouseId = it.id)) },
                        label = "Склад *",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = form.code,
                    onValueChange = { onFormChange(form.copy(code = it)) },
                    label = { Text("Код *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { onFormChange(form.copy(name = it)) },
                    label = { Text("Название *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.parentId,
                    onValueChange = { onFormChange(form.copy(parentId = it)) },
                    label = { Text("Родительский ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                StorageLocationKindDropdown(
                    selected = form.kind,
                    onSelect = { onFormChange(form.copy(kind = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
                StorageLocationPurposeDropdown(
                    selected = form.purpose,
                    onSelect = { onFormChange(form.copy(purpose = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.capacity,
                    onValueChange = { onFormChange(form.copy(capacity = it)) },
                    label = { Text("Ёмкость") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = { Button(onClick = onCreate, enabled = warehouses.isNotEmpty()) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun CreateNomenclatureDialog(
    form: CreateNomenclatureForm,
    error: org.example.project.dto.ApiError?,
    onFormChange: (CreateNomenclatureForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая номенклатура") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                OutlinedTextField(
                    value = form.code,
                    onValueChange = { onFormChange(form.copy(code = it)) },
                    label = { Text("Код *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { onFormChange(form.copy(name = it)) },
                    label = { Text("Название *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.description,
                    onValueChange = { onFormChange(form.copy(description = it)) },
                    label = { Text("Описание") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                NomenclatureTypeDropdown(
                    selected = form.type,
                    onSelect = { onFormChange(form.copy(type = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.unit,
                    onValueChange = { onFormChange(form.copy(unit = it)) },
                    label = { Text("Единица измерения *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = form.lotTracked, onCheckedChange = { onFormChange(form.copy(lotTracked = it)) })
                    Text("Учёт по партиям")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = form.serialTracked, onCheckedChange = { onFormChange(form.copy(serialTracked = it)) })
                    Text("Учёт по сериям")
                }
                Text("Штрихкоды", style = MaterialTheme.typography.labelLarge)
                form.barcodes.forEachIndexed { index, barcode ->
                    BarcodeRow(
                        barcode = barcode,
                        onChange = { onFormChange(form.copy(barcodes = form.barcodes.toMutableList().apply { this[index] = it })) },
                        onRemove = { onFormChange(form.copy(barcodes = form.barcodes.toMutableList().apply { removeAt(index) })) }
                    )
                }
                TextButton(onClick = { onFormChange(form.copy(barcodes = form.barcodes + CreateNomenclatureBarcodeRequest(barcode = ""))) }) {
                    Text("Добавить штрихкод")
                }
            }
        },
        confirmButton = { Button(onClick = onCreate) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarcodeRow(
    barcode: CreateNomenclatureBarcodeRequest,
    onChange: (CreateNomenclatureBarcodeRequest) -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = barcode.barcode,
            onValueChange = { onChange(barcode.copy(barcode = it)) },
            label = { Text("Штрихкод") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            OutlinedTextField(
                value = barcode.packageType.localizedName(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Тип упаковки") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                BarcodePackageType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.localizedName()) },
                        onClick = {
                            onChange(barcode.copy(packageType = type))
                            expanded = false
                        }
                    )
                }
            }
        }
        OutlinedTextField(
            value = barcode.quantityInPackage,
            onValueChange = { onChange(barcode.copy(quantityInPackage = it)) },
            label = { Text("Количество в упаковке") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true
        )
        TextButton(onClick = onRemove) { Text("Удалить штрихкод") }
    }
}

@Composable
fun CreateReceiptDialog(
    form: CreateReceiptForm,
    warehouses: List<WarehouseDto>,
    locations: List<StorageLocationDto>,
    nomenclature: List<NomenclatureDto>,
    error: org.example.project.dto.ApiError?,
    onFormChange: (CreateReceiptForm) -> Unit,
    onWarehouseChange: (Long?) -> Unit,
    onAddLine: () -> Unit,
    onUpdateLine: (Int, CreateReceiptLineForm) -> Unit,
    onRemoveLine: (Int) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая приёмка") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                ReceiptSourceTypeDropdown(
                    selected = form.sourceType,
                    onSelect = { onFormChange(form.copy(sourceType = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
                // Основание проверяется сервером: для заказа, счёта, накладной,
                // возврата и перемещения номер документа обязателен.
                OutlinedTextField(
                    value = form.sourceDocumentNo,
                    onValueChange = { onFormChange(form.copy(sourceDocumentNo = it)) },
                    label = { Text("Номер документа-основания") },
                    placeholder = { Text("УПД-123 или СЧ-45") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = form.sourceDocumentNo.isBlank() && form.sourceType != ReceiptSourceType.INITIAL_STOCK &&
                        form.sourceType != ReceiptSourceType.OTHER
                )
                org.example.project.ui.theme.ErpDateField(
                    value = form.sourceDocumentDate,
                    onValueChange = { onFormChange(form.copy(sourceDocumentDate = it)) },
                    label = "Дата документа",
                    modifier = Modifier.fillMaxWidth()
                )
                if (form.sourceType == ReceiptSourceType.PURCHASE_ORDER ||
                    form.sourceType == ReceiptSourceType.DELIVERY_NOTE ||
                    form.sourceType == ReceiptSourceType.INVOICE
                ) {
                    PurchaseOrderPicker(
                        value = form.purchaseOrderId,
                        onChange = { orderId ->
                            onFormChange(
                                form.copy(
                                    purchaseOrderId = orderId,
                                    lines = form.lines.map { it.copy(purchaseOrderLineId = null) }
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Заказ уменьшает ожидаемый остаток; приёмка может быть частичной",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = form.sourceRef,
                    onValueChange = { onFormChange(form.copy(sourceRef = it)) },
                    label = { Text("Свободное описание основания") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (warehouses.isEmpty()) {
                    Text(
                        text = "Нет доступных складов.",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    WarehouseDropdown(
                        warehouses = warehouses,
                        selected = warehouses.find { it.id == form.warehouseId },
                        onSelect = { onWarehouseChange(it.id) },
                        label = "Склад *",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                org.example.project.ui.theme.ErpDateField(
                    value = form.expectedAt,
                    onValueChange = { onFormChange(form.copy(expectedAt = it)) },
                    label = "Ожидается",
                    format = org.example.project.ui.theme.DateValueFormat.START_OF_DAY_UTC,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.note,
                    onValueChange = { onFormChange(form.copy(note = it)) },
                    label = { Text("Примечание") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Text("Строки приёмки", style = MaterialTheme.typography.labelLarge)
                if (form.lines.isEmpty()) {
                    Text("Строки не добавлены.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val orderLines = rememberPurchaseOrderLines(form.purchaseOrderId)
                form.lines.forEachIndexed { index, line ->
                    ReceiptLineRow(
                        line = line,
                        locations = locations,
                        nomenclature = nomenclature.filterNot { it.archived },
                        showPurchaseOrderLine = form.purchaseOrderId != null,
                        orderLines = orderLines,
                        onChange = { onUpdateLine(index, it) },
                        onRemove = { onRemoveLine(index) }
                    )
                }
                Button(onClick = onAddLine, enabled = form.warehouseId != null) {
                    Text("Добавить строку")
                }
            }
        },
        confirmButton = { Button(onClick = onCreate, enabled = warehouses.isNotEmpty() && form.lines.isNotEmpty()) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ReceiptLineRow(
    line: CreateReceiptLineForm,
    locations: List<StorageLocationDto>,
    nomenclature: List<NomenclatureDto>,
    showPurchaseOrderLine: Boolean,
    orderLines: List<org.example.project.dto.PurchaseOrderLineDto> = emptyList(),
    onChange: (CreateReceiptLineForm) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Строка ${line.lineNo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NomenclatureDropdown(
            items = nomenclature,
            selected = nomenclature.find { it.id == line.nomenclatureId },
            onSelect = { onChange(line.copy(nomenclatureId = it.id)) },
            label = "Номенклатура *",
            modifier = Modifier.fillMaxWidth()
        )
        LocationDropdown(
            locations = locations,
            selected = locations.find { it.id == line.locationId },
            onSelect = { onChange(line.copy(locationId = it.id)) },
            label = "Ячейка *",
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = line.expectedQuantity,
            onValueChange = { onChange(line.copy(expectedQuantity = it)) },
            label = { Text("Ожидаемое количество *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        if (showPurchaseOrderLine) {
            org.example.project.ui.components.SearchableDropdown(
                items = orderLines,
                selected = orderLines.find { it.id == line.purchaseOrderLineId },
                onSelect = { orderLine ->
                    onChange(
                        line.copy(
                            purchaseOrderLineId = orderLine.id,
                            nomenclatureId = line.nomenclatureId ?: orderLine.nomenclatureId
                        )
                    )
                },
                label = "Позиция заказа",
                itemText = { orderLine ->
                    "№${orderLine.lineNo} · ${listOfNotNull(orderLine.nomenclatureCode, orderLine.nomenclatureName).joinToString(" · ")}" +
                        " · ${org.example.project.ui.theme.ErpFormat.qty(orderLine.quantity)} ${orderLine.unit}"
                },
                emptyText = "Позиции заказа не загружены",
                onClear = { onChange(line.copy(purchaseOrderLineId = null)) },
                clearText = "Не указана",
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Обязательна для заказа с несколькими позициями",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = line.receivedQuantity,
            onValueChange = { onChange(line.copy(receivedQuantity = it)) },
            label = { Text("Фактическое количество") },
            supportingText = { Text("Если пусто, принимается ожидаемое количество. Расхождение фиксируется отдельно") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        if (line.receivedQuantity.isNotBlank() &&
            line.receivedQuantity.trim() != line.expectedQuantity.trim()
        ) {
            OutlinedTextField(
                value = line.discrepancyReason,
                onValueChange = { onChange(line.copy(discrepancyReason = it)) },
                label = { Text("Обоснование расхождения *") },
                supportingText = { Text("Недопоставка или излишек без обоснования не принимается") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
        }
        OutlinedTextField(
            value = line.lotNumber,
            onValueChange = { onChange(line.copy(lotNumber = it)) },
            label = {
                Text(if (nomenclature.find { it.id == line.nomenclatureId }?.lotTracked == true) "Номер партии *" else "Номер партии")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = line.note,
            onValueChange = { onChange(line.copy(note = it)) },
            label = { Text("Примечание") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        TextButton(onClick = onRemove) { Text("Удалить строку") }
    }
}

@Composable
fun CreateReservationDialog(
    form: CreateReservationForm,
    warehouses: List<WarehouseDto>,
    locations: List<StorageLocationDto>,
    nomenclature: List<NomenclatureDto>,
    error: org.example.project.dto.ApiError?,
    onFormChange: (CreateReservationForm) -> Unit,
    onWarehouseChange: (Long?) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый резерв") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                ReservationReasonTypeDropdown(
                    selected = form.reasonType,
                    onSelect = { onFormChange(form.copy(reasonType = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
                ProjectPicker(
                    value = form.projectId,
                    onChange = { onFormChange(form.copy(projectId = it, taskId = "")) },
                    modifier = Modifier.fillMaxWidth()
                )
                TaskPicker(
                    value = form.taskId,
                    onChange = { onFormChange(form.copy(taskId = it)) },
                    projectId = form.projectId,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.reasonNote,
                    onValueChange = { onFormChange(form.copy(reasonNote = it)) },
                    label = { Text("Примечание к основанию") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                if (warehouses.isEmpty()) {
                    Text("Нет доступных складов.", color = MaterialTheme.colorScheme.error)
                } else {
                    WarehouseDropdown(
                        warehouses = warehouses,
                        selected = warehouses.find { it.id == form.warehouseId },
                        onSelect = { onWarehouseChange(it.id) },
                        label = "Склад *",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                NomenclatureDropdown(
                    items = nomenclature,
                    selected = nomenclature.find { it.id == form.nomenclatureId },
                    onSelect = { onFormChange(form.copy(nomenclatureId = it.id, lotId = "", serialId = "")) },
                    label = "Номенклатура *",
                    modifier = Modifier.fillMaxWidth()
                )
                if (form.warehouseId != null && locations.isEmpty()) {
                    Text("У выбранного склада нет ячеек.", color = MaterialTheme.colorScheme.error)
                }
                LocationDropdown(
                    locations = locations,
                    selected = locations.find { it.id == form.locationId },
                    onSelect = { onFormChange(form.copy(locationId = it.id)) },
                    label = "Ячейка *",
                    modifier = Modifier.fillMaxWidth()
                )
                LotPicker(
                    value = form.lotId,
                    onChange = { onFormChange(form.copy(lotId = it)) },
                    nomenclatureId = form.nomenclatureId,
                    modifier = Modifier.fillMaxWidth()
                )
                SerialPicker(
                    value = form.serialId,
                    onChange = { onFormChange(form.copy(serialId = it)) },
                    nomenclatureId = form.nomenclatureId,
                    lotId = form.lotId.trim().toLongOrNull(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.quantity,
                    onValueChange = { onFormChange(form.copy(quantity = it)) },
                    label = { Text("Количество *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                org.example.project.ui.theme.ErpDateField(
                    value = form.expiresAt,
                    onValueChange = { onFormChange(form.copy(expiresAt = it)) },
                    label = "Резерв действует до",
                    format = org.example.project.ui.theme.DateValueFormat.START_OF_DAY_UTC,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = onCreate, enabled = warehouses.isNotEmpty()) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
