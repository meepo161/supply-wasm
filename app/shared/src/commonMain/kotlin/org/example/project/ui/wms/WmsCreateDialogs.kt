package org.example.project.ui.wms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import org.example.project.ui.theme.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import org.example.project.ui.theme.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
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
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients

@Composable
fun CreateWarehouseDialog(
    form: CreateWarehouseForm,
    error: org.example.project.dto.ApiError?,
    onFormChange: (CreateWarehouseForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Новый склад",
        glyph = ErpIconGlyph.Warehouse,
        gradient = Gradients.Emerald,
        width = 580.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.code,
                onValueChange = { onFormChange(form.copy(code = it)) },
                label = { Text("Код *") },
                modifier = Modifier.weight(0.4f),
                singleLine = true
            )
            OutlinedTextField(
                value = form.name,
                onValueChange = { onFormChange(form.copy(name = it)) },
                label = { Text("Название *") },
                modifier = Modifier.weight(0.6f),
                singleLine = true
            )
        }
        OutlinedTextField(
            value = form.address,
            onValueChange = { onFormChange(form.copy(address = it)) },
            label = { Text("Адрес") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
    }
}

@Composable
fun CreateLocationDialog(
    form: CreateLocationForm,
    warehouses: List<WarehouseDto>,
    error: org.example.project.dto.ApiError?,
    onFormChange: (CreateLocationForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
    /** Ячейки, из которых выбирают родителя; в списке остаются только ячейки выбранного склада. */
    parentCandidates: List<StorageLocationDto> = emptyList(),
    /** Смена склада в диалоге: сбрасывает родителя и подгружает ячейки нового склада. */
    onWarehouseChange: ((Long) -> Unit)? = null
) {
    ErpDialog(
        title = "Новая ячейка",
        glyph = ErpIconGlyph.Warehouse,
        gradient = Gradients.Emerald,
        width = 640.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        confirmEnabled = warehouses.isNotEmpty(),
        onDismiss = onDismiss
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
                onSelect = {
                    if (onWarehouseChange != null) onWarehouseChange(it.id)
                    else onFormChange(form.copy(warehouseId = it.id, parentId = ""))
                },
                label = "Склад *",
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.code,
                onValueChange = { onFormChange(form.copy(code = it)) },
                label = { Text("Код *") },
                modifier = Modifier.weight(0.4f),
                singleLine = true
            )
            OutlinedTextField(
                value = form.name,
                onValueChange = { onFormChange(form.copy(name = it)) },
                label = { Text("Название *") },
                modifier = Modifier.weight(0.6f),
                singleLine = true
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StorageLocationKindDropdown(
                selected = form.kind,
                onSelect = { onFormChange(form.copy(kind = it)) },
                modifier = Modifier.weight(1f)
            )
            StorageLocationPurposeDropdown(
                selected = form.purpose,
                onSelect = { onFormChange(form.copy(purpose = it)) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Родитель — зона или ячейка того же склада (N-09): выбор из списка вместо ручного ID.
            val parentOptions = parentCandidates.filter { it.warehouseId == form.warehouseId }
            LocationDropdown(
                locations = parentOptions,
                selected = parentOptions.find { it.id.toString() == form.parentId.trim() },
                onSelect = { onFormChange(form.copy(parentId = it.id.toString())) },
                label = "Родительская зона или ячейка",
                emptyText = if (form.warehouseId == null) "Сначала выберите склад" else "На складе пока нет ячеек",
                showWarehouse = false,
                onClear = { onFormChange(form.copy(parentId = "")) },
                clearText = "Без родителя",
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = form.capacity,
                onValueChange = { onFormChange(form.copy(capacity = it)) },
                label = { Text("Ёмкость") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
    }
}

@Composable
fun CreateNomenclatureDialog(
    form: CreateNomenclatureForm,
    error: org.example.project.dto.ApiError?,
    onFormChange: (CreateNomenclatureForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Новая номенклатура",
        glyph = ErpIconGlyph.Layers,
        gradient = Gradients.Emerald,
        width = 660.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.code,
                onValueChange = { onFormChange(form.copy(code = it)) },
                label = { Text("Код *") },
                modifier = Modifier.weight(0.45f),
                singleLine = true
            )
            OutlinedTextField(
                value = form.unit,
                onValueChange = { onFormChange(form.copy(unit = it)) },
                label = { Text("Единица измерения номенклатуры *") },
                modifier = Modifier.weight(0.55f),
                singleLine = true
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { onFormChange(form.copy(name = it)) },
                label = { Text("Название *") },
                modifier = Modifier.weight(0.6f),
                singleLine = true
            )
            NomenclatureTypeDropdown(
                selected = form.type,
                onSelect = { onFormChange(form.copy(type = it)) },
                modifier = Modifier.weight(0.4f)
            )
        }
        OutlinedTextField(
            value = form.description,
            onValueChange = { onFormChange(form.copy(description = it)) },
            label = { Text("Описание") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = form.lotTracked, onCheckedChange = { onFormChange(form.copy(lotTracked = it)) })
                Text("Учёт по партиям")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = form.serialTracked, onCheckedChange = { onFormChange(form.copy(serialTracked = it)) })
                Text("Учёт по сериям")
            }
        }
        Text(
            "Признаки учёта по партиям и сериям нельзя изменить после создания номенклатуры.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Штрихкоды", style = MaterialTheme.typography.labelLarge)
        form.barcodes.forEachIndexed { index, barcode ->
            BarcodeRow(
                barcode = barcode,
                onChange = { onFormChange(form.copy(barcodes = form.barcodes.toMutableList().apply { this[index] = it })) },
                onRemove = { onFormChange(form.copy(barcodes = form.barcodes.toMutableList().apply { removeAt(index) })) },
                // Единственное пустое поле удалять незачем.
                canRemove = !(form.barcodes.size == 1 && barcode.barcode.isBlank())
            )
        }
        TextButton(onClick = { onFormChange(form.copy(barcodes = form.barcodes + CreateNomenclatureBarcodeRequest(barcode = ""))) }) {
            Text("Добавить штрихкод")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarcodeRow(
    barcode: CreateNomenclatureBarcodeRequest,
    onChange: (CreateNomenclatureBarcodeRequest) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean = true
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
                label = { Text("Упаковка штрихкода") },
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
        if (canRemove) TextButton(onClick = onRemove) { Text("Удалить штрихкод") }
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
    ErpDialog(
        title = "Новая приёмка",
        subtitle = "Приёмка товара на склад с привязкой к основанию и ячейкам",
        glyph = ErpIconGlyph.Warehouse,
        gradient = Gradients.Emerald,
        width = 760.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        confirmEnabled = warehouses.isNotEmpty() && form.lines.isNotEmpty(),
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReceiptSourceTypeDropdown(
                selected = form.sourceType,
                onSelect = { onFormChange(form.copy(sourceType = it)) },
                modifier = Modifier.weight(1f)
            )
            org.example.project.ui.theme.ErpDateField(
                value = form.sourceDocumentDate,
                onValueChange = { onFormChange(form.copy(sourceDocumentDate = it)) },
                label = "Дата документа",
                modifier = Modifier.weight(1f)
            )
        }
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
                onSelectOrder = { order ->
                    if (form.sourceType == ReceiptSourceType.PURCHASE_ORDER) {
                        onFormChange(
                            form.copy(
                                purchaseOrderId = order.id,
                                sourceDocumentNo = order.number,
                                lines = form.lines.map { it.copy(purchaseOrderLineId = null) }
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Заказ уменьшает ожидаемый остаток; приёмка может быть частичной",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (form.sourceType == ReceiptSourceType.INVOICE) {
            SupplierInvoicePicker(
                selectedNumber = form.sourceDocumentNo,
                onSelect = { invoice ->
                    onFormChange(
                        form.copy(
                            sourceDocumentNo = invoice.number,
                            sourceDocumentDate = invoice.invoiceDate.ifBlank { form.sourceDocumentDate },
                            purchaseOrderId = invoice.purchaseOrderId ?: form.purchaseOrderId,
                            lines = if (invoice.purchaseOrderId != null && invoice.purchaseOrderId != form.purchaseOrderId) {
                                form.lines.map { it.copy(purchaseOrderLineId = null) }
                            } else {
                                form.lines
                            }
                        )
                    )
                },
                onClear = { onFormChange(form.copy(sourceDocumentNo = "")) },
                modifier = Modifier.fillMaxWidth()
            )
        } else if (form.sourceType != ReceiptSourceType.INITIAL_STOCK) {
            BasisDocumentPicker(
                selectedNumber = form.sourceDocumentNo,
                onSelect = { doc ->
                    onFormChange(form.copy(sourceDocumentNo = doc.number))
                },
                onClear = { onFormChange(form.copy(sourceDocumentNo = "")) },
                label = "Выбрать документ-основание",
                modifier = Modifier.fillMaxWidth()
            )
        }
        // Основание проверяется сервером: для заказа, счёта, накладной,
        // возврата и перемещения номер документа обязателен.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.sourceDocumentNo,
                onValueChange = { onFormChange(form.copy(sourceDocumentNo = it)) },
                label = {
                    Text(
                        when {
                            form.sourceType == ReceiptSourceType.INVOICE -> "Номер счёта-основания *"
                            form.sourceType.requiresDocument() -> "Номер документа-основания *"
                            else -> "Номер документа-основания"
                        }
                    )
                },
                placeholder = {
                    Text(
                        when (form.sourceType) {
                            ReceiptSourceType.PURCHASE_ORDER -> "Подставится из выбранного заказа"
                            ReceiptSourceType.DELIVERY_NOTE -> "УПД-123"
                            else -> "Номер документа"
                        }
                    )
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                // Для заказа номер подставляется при выборе заказа, поэтому пустое поле не «ошибка»;
                // для остальных красим поле только после ответа об ошибке.
                isError = error.hasFieldError("sourceDocumentNo") ||
                    (error != null && form.sourceDocumentNo.isBlank() && form.sourceType.requiresDocument() &&
                        form.sourceType != ReceiptSourceType.PURCHASE_ORDER)
            )
            OutlinedTextField(
                value = form.sourceRef,
                onValueChange = { onFormChange(form.copy(sourceRef = it)) },
                label = { Text("Свободное описание основания") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (warehouses.isEmpty()) {
                Text(
                    text = "Нет доступных складов.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
            } else {
                WarehouseDropdown(
                    warehouses = warehouses,
                    selected = warehouses.find { it.id == form.warehouseId },
                    onSelect = { onWarehouseChange(it.id) },
                    label = "Склад *",
                    isError = error.hasFieldError("warehouseId") || (error != null && form.warehouseId == null),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        var showExtraFields by remember { mutableStateOf(form.expectedAt.isNotBlank() || form.note.isNotBlank()) }
        TextButton(onClick = { showExtraFields = !showExtraFields }) {
            Text(if (showExtraFields) "Скрыть дополнительные поля" else "Дополнительно (дата ожидания, примечание)")
        }
        if (showExtraFields) {
            org.example.project.ui.theme.ErpDateField(
                value = form.expectedAt,
                onValueChange = { onFormChange(form.copy(expectedAt = it)) },
                label = "Ожидается (необязательно)",
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
        }

        val orderLines = rememberPurchaseOrderLines(form.purchaseOrderId)
        val notAccepted = rememberOrderLinesNotAccepted(form.purchaseOrderId)
        var fillHint by remember(form.purchaseOrderId) { mutableStateOf<String?>(null) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Строки приёмки", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (form.purchaseOrderId != null && orderLines.isNotEmpty() && form.warehouseId != null) {
                    OutlinedButton(
                        onClick = {
                            val defaultLocationId = locations.firstOrNull()?.id
                            val existingOrderLineIds = form.lines.mapNotNull { it.purchaseOrderLineId }.toSet()
                            val startNo = (form.lines.maxOfOrNull { it.lineNo } ?: 0) + 1
                            // Подставляем то, что склад ещё не принял по заказу;
                            // полностью принятые позиции пропускаем.
                            val appended = orderLines
                                .filter { it.id !in existingOrderLineIds }
                                .map { it to (notAccepted[it.id] ?: it.quantity) }
                                .filter { (_, qty) -> (qty.toDoubleOrNull() ?: 0.0) > 0.0 }
                                .mapIndexed { idx, (orderLine, qty) ->
                                    CreateReceiptLineForm(
                                        lineNo = startNo + idx,
                                        nomenclatureId = orderLine.nomenclatureId,
                                        expectedQuantity = org.example.project.ui.theme.ErpFormat.qty(qty),
                                        locationId = defaultLocationId,
                                        purchaseOrderLineId = orderLine.id
                                    )
                                }
                            fillHint = if (appended.isEmpty() && existingOrderLineIds.isEmpty()) {
                                "Все позиции заказа уже приняты складом"
                            } else {
                                null
                            }
                            if (appended.isNotEmpty()) {
                                onFormChange(form.copy(lines = form.lines + appended))
                            }
                        }
                    ) {
                        Text("Заполнить из заказа")
                    }
                }
                OutlinedButton(onClick = onAddLine, enabled = form.warehouseId != null) {
                    Text("Добавить строку")
                }
            }
        }
        fillHint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (form.lines.isEmpty()) {
            Text("Строки не добавлены.",style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        form.lines.forEachIndexed { index, line ->
            ReceiptLineRow(
                line = line,
                index = index,
                error = error,
                locations = locations,
                nomenclature = nomenclature.filterNot { it.archived },
                showPurchaseOrderLine = form.purchaseOrderId != null,
                orderLines = orderLines,
                onChange = { onUpdateLine(index, it) },
                onRemove = { onRemoveLine(index) }
            )
        }
    }
}

@Composable
private fun ReceiptLineRow(
    line: CreateReceiptLineForm,
    index: Int,
    error: org.example.project.dto.ApiError?,
    locations: List<StorageLocationDto>,
    nomenclature: List<NomenclatureDto>,
    showPurchaseOrderLine: Boolean,
    orderLines: List<org.example.project.dto.PurchaseOrderLineDto> = emptyList(),
    onChange: (CreateReceiptLineForm) -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Строка ${line.lineNo}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRemove) { Text("Удалить") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NomenclatureDropdown(
                    items = nomenclature,
                    selected = nomenclature.find { it.id == line.nomenclatureId },
                    onSelect = { onChange(line.copy(nomenclatureId = it.id)) },
                    label = "Номенклатура *",
                    isError = error.hasFieldError("lines[$index].nomenclatureId") ||
                        (error != null && line.nomenclatureId == null),
                    modifier = Modifier.weight(1.2f)
                )
                LocationDropdown(
                    locations = locations,
                    selected = locations.find { it.id == line.locationId },
                    onSelect = { onChange(line.copy(locationId = it.id)) },
                    label = "Ячейка *",
                    isError = error.hasFieldError("lines[$index].locationId") ||
                        (error != null && line.locationId == null),
                    modifier = Modifier.weight(0.8f)
                )
            }
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
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = line.expectedQuantity,
                    onValueChange = { onChange(line.copy(expectedQuantity = it)) },
                    label = { Text("Ожидаемое количество *") },
                    isError = error.hasFieldError("lines[$index].expectedQuantity") ||
                        (error != null && line.expectedQuantity.isBlank()),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = line.receivedQuantity,
                    onValueChange = { onChange(line.copy(receivedQuantity = it)) },
                    label = { Text("Фактическое количество") },
                    supportingText = { Text("Пусто = ожидаемому") },
                    isError = error.hasFieldError("lines[$index].receivedQuantity"),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            if (line.receivedQuantity.isNotBlank() &&
                line.receivedQuantity.trim() != line.expectedQuantity.trim()
            ) {
                OutlinedTextField(
                    value = line.discrepancyReason,
                    onValueChange = { onChange(line.copy(discrepancyReason = it)) },
                    label = { Text("Обоснование расхождения *") },
                    supportingText = { Text("Недопоставка или излишек без обоснования не принимается") },
                    isError = error.hasFieldError("lines[$index].discrepancyReason") ||
                        (error != null && line.discrepancyReason.isBlank()),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = line.lotNumber,
                    onValueChange = { onChange(line.copy(lotNumber = it)) },
                    label = {
                        Text(if (nomenclature.find { it.id == line.nomenclatureId }?.lotTracked == true) "Номер партии *" else "Номер партии")
                    },
                    isError = error.hasFieldError("lines[$index].lotNumber"),
                    modifier = Modifier.weight(0.8f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = line.note,
                    onValueChange = { onChange(line.copy(note = it)) },
                    label = { Text("Примечание") },
                    modifier = Modifier.weight(1.2f),
                    singleLine = true
                )
            }
        }
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
    ErpDialog(
        title = "Новый резерв",
        glyph = ErpIconGlyph.Warehouse,
        gradient = Gradients.Emerald,
        width = 680.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        confirmEnabled = warehouses.isNotEmpty(),
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReservationReasonTypeDropdown(
                selected = form.reasonType,
                onSelect = { onFormChange(form.copy(reasonType = it)) },
                modifier = Modifier.weight(1f)
            )
            org.example.project.ui.theme.ErpDateField(
                value = form.expiresAt,
                onValueChange = { onFormChange(form.copy(expiresAt = it)) },
                label = "Резерв действует до",
                format = org.example.project.ui.theme.DateValueFormat.START_OF_DAY_UTC,
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProjectPicker(
                value = form.projectId,
                onChange = { onFormChange(form.copy(projectId = it, taskId = "")) },
                modifier = Modifier.weight(1f)
            )
            TaskPicker(
                value = form.taskId,
                onChange = { onFormChange(form.copy(taskId = it)) },
                projectId = form.projectId,
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = form.reasonNote,
            onValueChange = { onFormChange(form.copy(reasonNote = it)) },
            label = { Text("Примечание к основанию") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (warehouses.isEmpty()) {
                Text("Нет доступных складов.", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            } else {
                WarehouseDropdown(
                    warehouses = warehouses,
                    selected = warehouses.find { it.id == form.warehouseId },
                    onSelect = { onWarehouseChange(it.id) },
                    label = "Склад *",
                    modifier = Modifier.weight(1f)
                )
            }
            LocationDropdown(
                locations = locations,
                selected = locations.find { it.id == form.locationId },
                onSelect = { onFormChange(form.copy(locationId = it.id)) },
                label = "Ячейка *",
                modifier = Modifier.weight(1f)
            )
        }
        if (form.warehouseId != null && locations.isEmpty()) {
            Text("У выбранного склада нет ячеек.", color = MaterialTheme.colorScheme.error)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NomenclatureDropdown(
                items = nomenclature,
                selected = nomenclature.find { it.id == form.nomenclatureId },
                onSelect = { onFormChange(form.copy(nomenclatureId = it.id, lotId = "", serialId = "")) },
                label = "Номенклатура *",
                modifier = Modifier.weight(1.3f)
            )
            OutlinedTextField(
                value = form.quantity,
                onValueChange = { onFormChange(form.copy(quantity = it)) },
                label = { Text("Количество *") },
                modifier = Modifier.weight(0.7f),
                singleLine = true
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LotPicker(
                value = form.lotId,
                onChange = { onFormChange(form.copy(lotId = it)) },
                nomenclatureId = form.nomenclatureId,
                modifier = Modifier.weight(1f)
            )
            SerialPicker(
                value = form.serialId,
                onChange = { onFormChange(form.copy(serialId = it)) },
                nomenclatureId = form.nomenclatureId,
                lotId = form.lotId.trim().toLongOrNull(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
