package org.example.project.ui.bom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.ApiError
import org.example.project.dto.BomLineDto
import org.example.project.dto.BomRevisionSummaryDto
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ProductModelDto
import org.example.project.dto.WarehouseDto
import org.example.project.ui.projects.ErrorMessageCard

@Composable
fun CreateProductModelDialog(
    form: CreateProductModelForm,
    error: ApiError?,
    onFormChange: (CreateProductModelForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая модель изделия") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                ModelFormFields(form, onFormChange)
            }
        },
        confirmButton = { Button(onClick = onCreate) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun EditProductModelDialog(
    model: ProductModelDto,
    form: CreateProductModelForm,
    error: ApiError?,
    onFormChange: (CreateProductModelForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать ${model.code}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                OutlinedTextField(
                    value = form.code,
                    onValueChange = { onFormChange(form.copy(code = it)) },
                    label = { Text("Код") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = true
                )
                ModelFormFields(form, onFormChange, readOnlyCode = true)
            }
        },
        confirmButton = { Button(onClick = onSave) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ModelFormFields(
    form: CreateProductModelForm,
    onFormChange: (CreateProductModelForm) -> Unit,
    readOnlyCode: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = form.code,
            onValueChange = { onFormChange(form.copy(code = it)) },
            label = { Text("Код *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            readOnly = readOnlyCode
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
        ProductModelStatusDropdown(
            selected = form.status,
            onSelect = { onFormChange(form.copy(status = it)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = form.designerId,
            onValueChange = { onFormChange(form.copy(designerId = it)) },
            label = { Text("ID конструктора") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = form.technologistId,
            onValueChange = { onFormChange(form.copy(technologistId = it)) },
            label = { Text("ID технолога") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
fun CreateRevisionDialog(
    form: CreateRevisionForm,
    error: ApiError?,
    onFormChange: (CreateRevisionForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая ревизия") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                OutlinedTextField(
                    value = form.revisionNumber,
                    onValueChange = { onFormChange(form.copy(revisionNumber = it)) },
                    label = { Text("Номер ревизии *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.changeReason,
                    onValueChange = { onFormChange(form.copy(changeReason = it)) },
                    label = { Text("Причина изменения") },
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
fun CopyRevisionDialog(
    revision: BomRevisionSummaryDto,
    form: CopyRevisionForm,
    error: ApiError?,
    onFormChange: (CopyRevisionForm) -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Копировать ревизию ${revision.revisionNumber}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                OutlinedTextField(
                    value = form.revisionNumber,
                    onValueChange = { onFormChange(form.copy(revisionNumber = it)) },
                    label = { Text("Новый номер ревизии *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.reason,
                    onValueChange = { onFormChange(form.copy(reason = it)) },
                    label = { Text("Причина копирования *") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        },
        confirmButton = { Button(onClick = onCopy) { Text("Копировать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun CreateLineDialog(
    form: CreateLineForm,
    nomenclature: List<NomenclatureDto>,
    parentLines: List<BomLineDto>,
    error: ApiError?,
    onFormChange: (CreateLineForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая строка BOM") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                BomLineDropdown(
                    lines = parentLines,
                    selectedId = form.parentLineId.toLongOrNull(),
                    onSelect = { onFormChange(form.copy(parentLineId = it?.toString() ?: "")) },
                    label = "Родительская строка",
                    modifier = Modifier.fillMaxWidth()
                )
                BomNomenclatureDropdown(
                    items = nomenclature,
                    selected = nomenclature.find { it.id == form.parentNomenclatureId },
                    onSelect = { onFormChange(form.copy(parentNomenclatureId = it.id)) },
                    label = "Родительская номенклатура *",
                    modifier = Modifier.fillMaxWidth()
                )
                BomNomenclatureDropdown(
                    items = nomenclature,
                    selected = nomenclature.find { it.id == form.childNomenclatureId },
                    onSelect = { onFormChange(form.copy(childNomenclatureId = it.id)) },
                    label = "Дочерняя номенклатура *",
                    modifier = Modifier.fillMaxWidth()
                )
                LineQuantityFields(form, onFormChange)
                BomLineDropdown(
                    lines = parentLines,
                    selectedId = form.substituteOfLineId.toLongOrNull(),
                    onSelect = { onFormChange(form.copy(substituteOfLineId = it?.toString() ?: "")) },
                    label = "Замена для строки",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = onCreate) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun EditLineDialog(
    line: BomLineDto,
    form: EditLineForm,
    error: ApiError?,
    onFormChange: (EditLineForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать строку #${line.id}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                Text(
                    text = "Родитель: ${line.parentNomenclatureId}, дочерняя: ${line.childNomenclatureId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LineQuantityFields(form, onFormChange)
            }
        },
        confirmButton = { Button(onClick = onSave) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun LineQuantityFields(
    form: CreateLineForm,
    onFormChange: (CreateLineForm) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = form.quantity,
            onValueChange = { onFormChange(form.copy(quantity = it)) },
            label = { Text("Количество *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = form.unit,
            onValueChange = { onFormChange(form.copy(unit = it)) },
            label = { Text("Единица измерения *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = form.position,
            onValueChange = { onFormChange(form.copy(position = it)) },
            label = { Text("Позиция") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = form.level,
            onValueChange = { onFormChange(form.copy(level = it)) },
            label = { Text("Уровень") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = form.mandatory, onCheckedChange = { onFormChange(form.copy(mandatory = it)) })
            Text("Обязательная строка")
        }
        OutlinedTextField(
            value = form.consumptionRate,
            onValueChange = { onFormChange(form.copy(consumptionRate = it)) },
            label = { Text("Норма расхода") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = form.lossPct,
            onValueChange = { onFormChange(form.copy(lossPct = it)) },
            label = { Text("Потери, %") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
private fun LineQuantityFields(
    form: EditLineForm,
    onFormChange: (EditLineForm) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = form.quantity,
            onValueChange = { onFormChange(form.copy(quantity = it)) },
            label = { Text("Количество *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = form.unit,
            onValueChange = { onFormChange(form.copy(unit = it)) },
            label = { Text("Единица измерения *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = form.position,
            onValueChange = { onFormChange(form.copy(position = it)) },
            label = { Text("Позиция") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = form.level,
            onValueChange = { onFormChange(form.copy(level = it)) },
            label = { Text("Уровень") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = form.mandatory, onCheckedChange = { onFormChange(form.copy(mandatory = it)) })
            Text("Обязательная строка")
        }
        OutlinedTextField(
            value = form.consumptionRate,
            onValueChange = { onFormChange(form.copy(consumptionRate = it)) },
            label = { Text("Норма расхода") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = form.lossPct,
            onValueChange = { onFormChange(form.copy(lossPct = it)) },
            label = { Text("Потери, %") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
fun ExplodeDialog(
    form: ExplodeForm,
    warehouses: List<WarehouseDto>,
    error: ApiError?,
    onFormChange: (ExplodeForm) -> Unit,
    onRun: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Разузлование") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                OutlinedTextField(
                    value = form.releaseQuantity,
                    onValueChange = { onFormChange(form.copy(releaseQuantity = it)) },
                    label = { Text("Количество выпуска *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                BomWarehouseDropdown(
                    warehouses = warehouses,
                    selected = warehouses.find { it.id == form.warehouseId },
                    onSelect = { onFormChange(form.copy(warehouseId = it.id)) },
                    label = "Склад",
                    modifier = Modifier.fillMaxWidth(),
                    allowEmpty = true
                )
            }
        },
        confirmButton = { Button(onClick = onRun) { Text("Рассчитать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
