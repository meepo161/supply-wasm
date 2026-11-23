package org.example.project.ui.demand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import org.example.project.ui.theme.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.ApiError
import org.example.project.dto.BomRevisionStatus
import org.example.project.dto.BomRevisionSummaryDto
import org.example.project.dto.DemandSourceType
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ProductModelDto
import org.example.project.dto.ProjectDto
import org.example.project.ui.bom.localizedName
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients

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
            DemandSourceType.MANUAL -> { }
        }
        if (form.sourceType != DemandSourceType.PROJECT) {
            ManualLinesSection(
                lines = form.lines,
                nomenclature = nomenclature,
                onAddLine = onAddLine,
                onUpdateLine = onUpdateLine,
                onRemoveLine = onRemoveLine
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
    onRemoveLine: (Int) -> Unit
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
                onRemove = { onRemoveLine(index) }
            )
        }
        Button(onClick = onAddLine) { Text("Добавить позицию") }
    }
}

@Composable
private fun DemandLineRow(
    line: CreateDemandLineForm,
    nomenclature: List<NomenclatureDto>,
    onChange: (CreateDemandLineForm) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Строка ${line.lineNo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DemandNomenclatureDropdown(
            items = nomenclature,
            selected = nomenclature.find { it.id == line.nomenclatureId },
            onSelect = { onChange(line.copy(nomenclatureId = it.id, unit = it.unit)) },
            label = "Номенклатура *",
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = line.quantity,
            onValueChange = { onChange(line.copy(quantity = it)) },
            label = { Text("Количество *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = line.unit,
            onValueChange = {},
            label = { Text("Единица измерения") },
            supportingText = { Text("Подставляется из выбранной номенклатуры") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            readOnly = true
        )
        TextButton(onClick = onRemove) { Text("Удалить строку") }
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
