package org.example.project.ui.demand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding

import org.example.project.ui.theme.Button
import org.example.project.ui.theme.OutlinedButton

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.WmsRepository
import org.example.project.dto.ApiError
import org.example.project.dto.BomRevisionStatus
import org.example.project.dto.BomRevisionSummaryDto
import org.example.project.dto.CreateNomenclatureRequest
import org.example.project.dto.DemandSourceType
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.NomenclatureType
import org.example.project.dto.ProductModelDto
import org.example.project.dto.ProjectDto
import org.example.project.ui.bom.localizedName
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients
import org.example.project.ui.userText
import org.koin.compose.koinInject

@Composable
fun CreateDemandDialog(
    form: CreateDemandForm,
    projects: List<ProjectDto>,
    models: List<ProductModelDto>,
    revisions: List<BomRevisionSummaryDto>,
    nomenclature: List<NomenclatureDto>,
    error: ApiError?,
    onFormChange: (CreateDemandForm) -> Unit,
    onSourceTypeChange: (DemandSourceType) -> Unit,
    onProjectSelected: (Long?) -> Unit,
    onModelSelected: (Long?) -> Unit,
    onRevisionSelected: (Long?) -> Unit,
    onAddLine: () -> Unit,
    onUpdateLine: (Int, CreateDemandLineForm) -> Unit,
    onRemoveLine: (Int) -> Unit,
    onNomenclatureCreated: (NomenclatureDto) -> Unit = {},
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    val approvedActiveRevisions = revisions.filter {
        it.status == BomRevisionStatus.APPROVED || it.status == BomRevisionStatus.ACTIVE
    }
    ErpDialog(
        title = "Новая потребность",
        glyph = ErpIconGlyph.Cart,
        gradient = Gradients.Primary,
        width = 720.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DemandSourceTypeDropdown(
                selected = form.sourceType,
                onSelect = onSourceTypeChange,
                modifier = Modifier.weight(1f)
            )
            DemandPriorityDropdown(
                selected = form.priority,
                onSelect = { onFormChange(form.copy(priority = it)) },
                modifier = Modifier.weight(1f)
            )
        }
        org.example.project.ui.theme.ErpDateField(
            value = form.requiredDate,
            onValueChange = { onFormChange(form.copy(requiredDate = it)) },
            label = "Требуемая дата",
            modifier = Modifier.fillMaxWidth()
        )
        when (form.sourceType) {
            DemandSourceType.PROJECT -> ProjectDemandFields(
                form = form,
                projects = projects,
                models = models,
                revisions = approvedActiveRevisions,
                onProjectSelected = onProjectSelected,
                onModelSelected = onModelSelected,
                onRevisionSelected = onRevisionSelected,
                onReleaseQuantityChange = { onFormChange(form.copy(releaseQuantity = it)) }
            )
            DemandSourceType.STOCKPILE -> OutlinedTextField(
                value = form.stockpileNote,
                onValueChange = { onFormChange(form.copy(stockpileNote = it)) },
                label = { Text("Обоснование задела *") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            DemandSourceType.MANUAL -> OutlinedTextField(
                value = form.stockpileNote,
                onValueChange = { onFormChange(form.copy(stockpileNote = it)) },
                label = { Text("Примечание") },
                supportingText = { Text("Необязательно: зачем нужна потребность, для кого, особые условия") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
        }
        if (form.sourceType != DemandSourceType.PROJECT) {
            ManualLinesSection(
                lines = form.lines,
                nomenclature = nomenclature,
                onAddLine = onAddLine,
                onUpdateLine = onUpdateLine,
                onRemoveLine = onRemoveLine,
                onNomenclatureCreated = onNomenclatureCreated
            )
        }
    }
}

@Composable
private fun ProjectDemandFields(
    form: CreateDemandForm,
    projects: List<ProjectDto>,
    models: List<ProductModelDto>,
    revisions: List<BomRevisionSummaryDto>,
    onProjectSelected: (Long?) -> Unit,
    onModelSelected: (Long?) -> Unit,
    onRevisionSelected: (Long?) -> Unit,
    onReleaseQuantityChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProjectDropdown(
            projects = projects,
            selected = projects.find { it.id == form.projectId },
            onSelect = { onProjectSelected(it.id) },
            label = "Проект *",
            modifier = Modifier.fillMaxWidth()
        )
        ProductModelDropdown(
            models = models,
            selected = models.find { it.id == form.modelId },
            onSelect = { onModelSelected(it.id) },
            label = "Модель изделия *",
            modifier = Modifier.fillMaxWidth()
        )
        BomRevisionDropdown(
            revisions = revisions,
            selected = revisions.find { it.id == form.revisionId },
            onSelect = { onRevisionSelected(it.id) },
            label = "Ревизия BOM (APPROVED/ACTIVE) *",
            modifier = Modifier.fillMaxWidth()
        )
        if (models.isNotEmpty() && revisions.isEmpty() && form.modelId != null) {
            Text(
                text = "У выбранной модели нет утверждённых/активных ревизий.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        OutlinedTextField(
            value = form.releaseQuantity,
            onValueChange = onReleaseQuantityChange,
            label = { Text("Количество выпуска *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
private fun ManualLinesSection(
    lines: List<CreateDemandLineForm>,
    nomenclature: List<NomenclatureDto>,
    onAddLine: () -> Unit,
    onUpdateLine: (Int, CreateDemandLineForm) -> Unit,
    onRemoveLine: (Int) -> Unit,
    onNomenclatureCreated: (NomenclatureDto) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Позиции", style = MaterialTheme.typography.labelLarge)
        if (lines.isEmpty()) {
            Text("Позиции не добавлены.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        lines.forEachIndexed { index, line ->
            DemandLineRow(
                line = line,
                nomenclature = nomenclature,
                onChange = { onUpdateLine(index, it) },
                onRemove = { onRemoveLine(index) },
                onNomenclatureCreated = onNomenclatureCreated
            )
        }
        OutlinedButton(onClick = onAddLine) { Text("Добавить позицию") }
    }
}

@Composable
private fun DemandLineRow(
    line: CreateDemandLineForm,
    nomenclature: List<NomenclatureDto>,
    onChange: (CreateDemandLineForm) -> Unit,
    onRemove: () -> Unit,
    onNomenclatureCreated: (NomenclatureDto) -> Unit
) {
    val wmsRepository: WmsRepository = koinInject()
    val scope = rememberCoroutineScope()
    var showQuickCreate by remember { mutableStateOf(false) }
    var newCode by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newUnit by remember { mutableStateOf("шт") }
    var createError by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Строка ${line.lineNo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRemove) { Text("Удалить строку") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DemandNomenclatureDropdown(
                items = nomenclature,
                selected = nomenclature.find { it.id == line.nomenclatureId },
                onSelect = { onChange(line.copy(nomenclatureId = it.id, unit = it.unit)) },
                label = "Номенклатура *",
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = {
                newCode = ""
                newName = ""
                newUnit = "шт"
                createError = null
                showQuickCreate = true
            }) { Text("+ Новая") }
        }
        OutlinedTextField(
            value = line.quantity,
            onValueChange = { onChange(line.copy(quantity = it)) },
            label = { Text("Количество *") },
            suffix = if (line.unit.isNotBlank()) ({ Text(line.unit) }) else null,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }

    if (showQuickCreate) {
        ErpDialog(
            title = "Новая номенклатура",
            glyph = ErpIconGlyph.Warehouse,
            gradient = Gradients.Primary,
            width = 480.dp,
            confirmText = if (creating) "Создание…" else "Создать",
            confirmEnabled = newCode.isNotBlank() && newName.isNotBlank() && newUnit.isNotBlank() && !creating,
            onConfirm = {
                creating = true
                createError = null
                scope.launch {
                    when (val r = wmsRepository.createNomenclature(
                        CreateNomenclatureRequest(
                            code = newCode.trim(),
                            name = newName.trim(),
                            type = NomenclatureType.PURCHASED_COMPONENT,
                            unit = newUnit.trim()
                        )
                    )) {
                        is ApiResult.Success -> {
                            onNomenclatureCreated(r.data)
                            onChange(line.copy(nomenclatureId = r.data.id, unit = r.data.unit))
                            showQuickCreate = false
                        }
                        is ApiResult.Error -> createError = r.error.userText()
                    }
                    creating = false
                }
            },
            onDismiss = { if (!creating) showQuickCreate = false }
        ) {
            createError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = newCode,
                onValueChange = { newCode = it },
                label = { Text("Код / артикул *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Наименование *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = newUnit,
                onValueChange = { newUnit = it },
                label = { Text("Единица измерения *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun CancelDemandDialog(
    reason: String,
    error: ApiError?,
    onReasonChange: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Отменить потребность",
        glyph = ErpIconGlyph.Cart,
        gradient = Gradients.Sunset,
        width = 520.dp,
        confirmText = "Отменить потребность",

        confirmGradient = org.example.project.ui.theme.Gradients.Danger,

        onConfirm = onCancel,
        dismissText = "Закрыть",
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        OutlinedTextField(
            value = reason,
            onValueChange = onReasonChange,
            label = { Text("Причина отмены *") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
    }
}
