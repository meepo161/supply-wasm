package org.example.project.ui.bom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import org.example.project.ui.theme.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.ApiError
import org.example.project.dto.BomLineDto
import org.example.project.dto.BomRevisionSummaryDto
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ProductModelDto
import org.example.project.dto.UserOptionDto
import org.example.project.dto.WarehouseDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients

@Composable
fun CreateProductModelDialog(
    form: CreateProductModelForm,
    users: List<UserOptionDto>,
    error: ApiError?,
    onFormChange: (CreateProductModelForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Новая модель изделия",
        glyph = ErpIconGlyph.Layers,
        gradient = Gradients.Primary,
        width = 640.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        ModelFormFields(form, users, onFormChange)
    }
}

@Composable
fun EditProductModelDialog(
    model: ProductModelDto,
    form: CreateProductModelForm,
    users: List<UserOptionDto>,
    error: ApiError?,
    onFormChange: (CreateProductModelForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Редактировать ${model.code}",
        glyph = ErpIconGlyph.Layers,
        gradient = Gradients.Primary,
        width = 640.dp,
        confirmText = "Сохранить",
        onConfirm = onSave,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        ModelFormFields(form, users, onFormChange, readOnlyCode = true)
    }
}

@Composable
private fun ModelFormFields(
    form: CreateProductModelForm,
    users: List<UserOptionDto>,
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
        UserOptionDropdown(
            users = users,
            selected = selectedUser(users, form.designerId),
            onSelect = { onFormChange(form.copy(designerId = it.id)) },
            onClear = { onFormChange(form.copy(designerId = null)) },
            label = "Конструктор",
            modifier = Modifier.fillMaxWidth()
        )
        UserOptionDropdown(
            users = users,
            selected = selectedUser(users, form.technologistId),
            onSelect = { onFormChange(form.copy(technologistId = it.id)) },
            onClear = { onFormChange(form.copy(technologistId = null)) },
            label = "Технолог",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun selectedUser(users: List<UserOptionDto>, id: Long?): UserOptionDto? =
    users.find { it.id == id } ?: id?.let {
        UserOptionDto(id = it, fullName = "Ранее назначенный сотрудник")
    }

@Composable
fun CreateRevisionDialog(
    form: CreateRevisionForm,
    error: ApiError?,
    onFormChange: (CreateRevisionForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Новая ревизия",
        glyph = ErpIconGlyph.Layers,
        gradient = Gradients.Primary,
        width = 560.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        onDismiss = onDismiss
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
    ErpDialog(
        title = "Копировать ревизию ${revision.revisionNumber}",
        glyph = ErpIconGlyph.Layers,
        gradient = Gradients.Primary,
        width = 560.dp,
        confirmText = "Копировать",
        onConfirm = onCopy,
        onDismiss = onDismiss
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
    ErpDialog(
        title = "Новая строка BOM",
        glyph = ErpIconGlyph.Layers,
        gradient = Gradients.Primary,
        width = 700.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BomLineDropdown(
                lines = parentLines,
                selectedId = form.parentLineId.toLongOrNull(),
                onSelect = { onFormChange(form.copy(parentLineId = it?.toString() ?: "")) },
                label = "Родительская строка",
                modifier = Modifier.weight(1f)
            )
            BomLineDropdown(
                lines = parentLines,
                selectedId = form.substituteOfLineId.toLongOrNull(),
                onSelect = { onFormChange(form.copy(substituteOfLineId = it?.toString() ?: "")) },
                label = "Замена для строки",
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BomNomenclatureDropdown(
                items = nomenclature,
                selected = nomenclature.find { it.id == form.parentNomenclatureId },
                onSelect = { onFormChange(form.copy(parentNomenclatureId = it.id)) },
                label = "Родительская номенклатура *",
                modifier = Modifier.weight(1f)
            )
            BomNomenclatureDropdown(
                items = nomenclature,
                selected = nomenclature.find { it.id == form.childNomenclatureId },
                onSelect = { onFormChange(form.copy(childNomenclatureId = it.id)) },
                label = "Дочерняя номенклатура *",
                modifier = Modifier.weight(1f)
            )
        }
        LineQuantityFields(form, onFormChange)
    }
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
    ErpDialog(
        title = "Редактировать строку #${line.id}",
        glyph = ErpIconGlyph.Layers,
        gradient = Gradients.Primary,
        width = 640.dp,
        confirmText = "Сохранить",
        onConfirm = onSave,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        Text(
            text = "Родитель: ${line.parentNomenclatureId}, дочерняя: ${line.childNomenclatureId}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LineQuantityFields(form, onFormChange)
    }
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
    ErpDialog(
        title = "Разузлование",
        glyph = ErpIconGlyph.Layers,
        gradient = Gradients.Primary,
        width = 560.dp,
        confirmText = "Рассчитать",
        onConfirm = onRun,
        onDismiss = onDismiss
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
}
