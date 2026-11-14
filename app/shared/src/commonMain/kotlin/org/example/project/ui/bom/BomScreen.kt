package org.example.project.ui.bom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.BomRevisionStatus
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader

@Composable
fun BomScreen(
    viewModel: BomViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = state.currentPage.title,
            glyph = ErpIconGlyph.Layers,
            gradient = Gradients.Berry,
            subtitle = "Номенклатура и состав изделий",
            onBack = if (state.currentPage != BomPage.MODELS) viewModel::goBack else null
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp)
        ) {
            when (state.currentPage) {
                BomPage.MODELS -> ModelsPage(state, viewModel)
                BomPage.MODEL_DETAIL -> ModelDetailPage(state, viewModel)
                BomPage.REVISION_DETAIL -> RevisionDetailPage(state, viewModel)
                BomPage.EXPLODE -> ExplodePage(state, viewModel)
            }
        }
    }

    when (val dialog = state.activeDialog) {
        BomDialog.CreateModel -> CreateProductModelDialog(
            form = state.createModelForm,
            users = state.users,
            error = state.operationError,
            onFormChange = viewModel::updateCreateModelForm,
            onCreate = viewModel::createModel,
            onDismiss = viewModel::dismissDialog
        )
        is BomDialog.EditModel -> EditProductModelDialog(
            model = dialog.model,
            form = state.editModelForm,
            users = state.users,
            error = state.operationError,
            onFormChange = viewModel::updateEditModelForm,
            onSave = viewModel::updateModel,
            onDismiss = viewModel::dismissDialog
        )
        BomDialog.CreateRevision -> CreateRevisionDialog(
            form = state.createRevisionForm,
            error = state.operationError,
            onFormChange = viewModel::updateCreateRevisionForm,
            onCreate = viewModel::createRevision,
            onDismiss = viewModel::dismissDialog
        )
        is BomDialog.CopyRevision -> CopyRevisionDialog(
            revision = dialog.revision,
            form = state.copyRevisionForm,
            error = state.operationError,
            onFormChange = viewModel::updateCopyRevisionForm,
            onCopy = viewModel::copyRevision,
            onDismiss = viewModel::dismissDialog
        )
        BomDialog.CreateLine -> CreateLineDialog(
            form = state.createLineForm,
            nomenclature = state.nomenclature,
            parentLines = state.revisionLines,
            error = state.operationError,
            onFormChange = viewModel::updateCreateLineForm,
            onCreate = viewModel::createLine,
            onDismiss = viewModel::dismissDialog
        )
        is BomDialog.EditLine -> EditLineDialog(
            line = dialog.line,
            form = state.editLineForm,
            error = state.operationError,
            onFormChange = viewModel::updateEditLineForm,
            onSave = viewModel::updateLine,
            onDismiss = viewModel::dismissDialog
        )
        BomDialog.Explode -> ExplodeDialog(
            form = state.explodeForm,
            warehouses = state.warehouses,
            error = state.operationError,
            onFormChange = viewModel::updateExplodeForm,
            onRun = viewModel::explodeRevision,
            onDismiss = viewModel::dismissDialog
        )
        null -> { }
    }
}

@Composable
private fun ModelsPage(
    state: BomUiState,
    viewModel: BomViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PageHeader(
            title = "Модели изделий",
            onCreate = { viewModel.showDialog(BomDialog.CreateModel) },
            onRefresh = viewModel::loadModels,
            createLabel = "Новая модель",
            canCreate = viewModel.hasPermission("bom", "create")
        )
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.models.isEmpty()) {
            LoadingOverlay()
        } else if (state.models.isEmpty()) {
            EmptyState("Нет моделей изделий. Создайте первую.")
        } else {
            ProductModelsGrid(
                models = state.models,
                onClick = { viewModel.selectModel(it) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun ModelDetailPage(
    state: BomUiState,
    viewModel: BomViewModel
) {
    val model = state.selectedModel
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (model == null) {
            EmptyState("Модель не выбрана.")
            return@Column
        }
        ModelInfoCard(model, viewModel)
        PageHeader(
            title = "Ревизии",
            onCreate = { viewModel.showDialog(BomDialog.CreateRevision) },
            onRefresh = viewModel::refreshCurrentModel,
            createLabel = "Новая ревизия",
            canCreate = viewModel.hasPermission("bom", "create")
        )
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.revisions.isEmpty()) {
            LoadingOverlay()
        } else if (state.revisions.isEmpty()) {
            EmptyState("Нет ревизий. Создайте первую.")
        } else {
            BomRevisionsGrid(
                revisions = state.revisions,
                onClick = { viewModel.selectRevision(it.id) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun ModelInfoCard(
    model: org.example.project.dto.ProductModelDto,
    viewModel: BomViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "${model.code} — ${model.name}", style = MaterialTheme.typography.titleMedium)
            Text(text = "Статус: ${model.status.localizedName()}", style = MaterialTheme.typography.bodyMedium)
            if (!model.description.isNullOrBlank()) {
                Text(text = "Описание: ${model.description}", style = MaterialTheme.typography.bodyMedium)
            }
            if (viewModel.hasPermission("bom", "update")) {
                TextButton(onClick = { viewModel.showDialog(BomDialog.EditModel(model)) }) {
                    Text("Редактировать")
                }
            }
        }
    }
}

@Composable
private fun RevisionDetailPage(
    state: BomUiState,
    viewModel: BomViewModel
) {
    val revision = state.selectedRevision
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (revision == null) {
            LoadingOverlay()
            return@Column
        }
        RevisionInfoCard(revision, state.revisionLines, viewModel)
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.revisionLines.isEmpty()) {
            LoadingOverlay()
        } else if (state.revisionLines.isEmpty()) {
            EmptyState("Нет строк BOM. Создайте первую.")
        } else {
            BomLinesGrid(
                lines = state.revisionLines,
                onClick = { line ->
                    if (!revision.status.isReadOnly() && viewModel.hasPermission("bom", "update")) {
                        viewModel.showDialog(BomDialog.EditLine(line))
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun RevisionInfoCard(
    revision: org.example.project.dto.BomRevisionDto,
    lines: List<org.example.project.dto.BomLineDto>,
    viewModel: BomViewModel
) {
    val isDraft = revision.status == BomRevisionStatus.DRAFT
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Ревизия ${revision.revisionNumber}",
                style = MaterialTheme.typography.titleMedium
            )
            Text(text = "Статус: ${revision.status.localizedName()}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Версия: ${revision.version}", style = MaterialTheme.typography.bodyMedium)
            if (!revision.changeReason.isNullOrBlank()) {
                Text(text = "Причина: ${revision.changeReason}", style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isDraft && viewModel.hasPermission("bom", "update")) {
                    Button(onClick = { viewModel.showDialog(BomDialog.CreateLine) }) {
                        Text("Добавить строку")
                    }
                }
                if (isDraft && viewModel.hasPermission("bom", "approve")) {
                    Button(onClick = viewModel::approveRevision) { Text("Утвердить") }
                }
                if (revision.status == BomRevisionStatus.APPROVED && viewModel.hasPermission("bom", "approve")) {
                    Button(onClick = viewModel::activateRevision) { Text("Активировать") }
                }
                if (revision.status == BomRevisionStatus.ACTIVE && viewModel.hasPermission("bom", "approve")) {
                    Button(onClick = viewModel::replaceRevision) { Text("Заменить") }
                }
                if (isDraft && viewModel.hasPermission("bom", "delete_draft")) {
                    OutlinedButton(onClick = viewModel::cancelRevision) { Text("Отменить") }
                }
                if (viewModel.hasPermission("bom", "explode")) {
                    OutlinedButton(onClick = { viewModel.showDialog(BomDialog.Explode) }) { Text("Разузлование") }
                }
            }
            if (!isDraft) {
                Text(
                    text = "Редактирование строк недоступно: ревизия не в статусе черновика.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ExplodePage(
    state: BomUiState,
    viewModel: BomViewModel
) {
    val report = state.explodeReport
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Отчёт разузлования", style = MaterialTheme.typography.headlineSmall)
        if (report == null) {
            EmptyState("Отчёт не сформирован.", Modifier.weight(1f))
            return@Column
        }
        Text(
            text = "Ревизия: ${report.revisionNumber}, выпуск: ${report.releaseQuantity}",
            style = MaterialTheme.typography.bodyMedium
        )
        state.operationError?.let { ErrorMessageCard(error = it) }
        Text(text = "Дерево", style = MaterialTheme.typography.titleMedium)
        if (report.lines.isEmpty()) {
            Text("Нет строк.", style = MaterialTheme.typography.bodyMedium)
        } else {
            BomExplodeLinesGrid(
                lines = report.lines,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
        Text(text = "Сводка материалов", style = MaterialTheme.typography.titleMedium)
        if (report.summary.isEmpty()) {
            Text("Нет данных.", style = MaterialTheme.typography.bodyMedium)
        } else {
            BomMaterialSummaryGrid(
                summary = report.summary,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun PageHeader(
    title: String,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    createLabel: String,
    canCreate: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Row {
            if (canCreate) {
                Button(onClick = onCreate) { Text(createLabel) }
                Spacer(modifier = Modifier.width(8.dp))
            }
            TextButton(onClick = onRefresh) { Text("Обновить") }
        }
    }
}

@Composable
private fun LoadingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

