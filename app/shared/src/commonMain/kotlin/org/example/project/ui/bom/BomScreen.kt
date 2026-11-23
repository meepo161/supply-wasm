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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import org.example.project.ui.theme.OutlinedButton
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.BomRevisionStatus
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.GradientSpinner
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SoftButton

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
        BomDialog.Diff -> BomRevisionDiffDialog(
            state = state,
            onSelectTarget = viewModel::loadDiff,
            onDismiss = viewModel::dismissDialog
        )
        BomDialog.WhereUsed -> BomWhereUsedDialog(
            state = state,
            onSelectNomenclature = viewModel::loadWhereUsed,
            onOpenRevision = { revisionId ->
                viewModel.dismissDialog()
                viewModel.selectRevision(revisionId)
            },
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Всего моделей: ${state.models.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SoftButton(text = "Обновить", onClick = viewModel::loadModels, leadingIcon = ErpIconGlyph.Refresh)
                if (viewModel.hasPermission("bom", "create")) {
                    GradientButton(
                        text = "Новая модель",
                        onClick = { viewModel.showDialog(BomDialog.CreateModel) },
                        gradient = Gradients.Berry,
                        leadingIcon = ErpIconGlyph.Plus
                    )
                }
            }
        }
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
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 16.dp
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "${model.code} · ${model.name}", style = MaterialTheme.typography.titleMedium)
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
                nomenclature = state.nomenclature,
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
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 16.dp
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ревизия ${revision.revisionNumber}",
                    style = MaterialTheme.typography.titleMedium
                )
                PillBadge(
                    text = revision.status.localizedName(),
                    accent = when (revision.status) {
                        BomRevisionStatus.ACTIVE -> Brand.Teal
                        BomRevisionStatus.APPROVED -> Brand.Blue
                        BomRevisionStatus.DRAFT -> Brand.Indigo
                        else -> Brand.BlueDeep
                    }
                )
            }
            if (!revision.changeReason.isNullOrBlank()) {
                Text(text = "Причина: ${revision.changeReason}", style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isDraft && viewModel.hasPermission("bom", "update")) {
                    GradientButton(
                        text = "Добавить строку",
                        onClick = { viewModel.showDialog(BomDialog.CreateLine) },
                        gradient = Gradients.Aurora,
                        compact = true
                    )
                }
                if (isDraft && viewModel.hasPermission("bom", "approve")) {
                    GradientButton(
                        text = "Утвердить",
                        onClick = viewModel::approveRevision,
                        gradient = Gradients.Ocean,
                        compact = true
                    )
                }
                var confirmRevisionAction by remember(revision.id) { mutableStateOf<String?>(null) }
                if (revision.status == BomRevisionStatus.APPROVED && viewModel.hasPermission("bom", "approve")) {
                    GradientButton(
                        text = "Активировать",
                        onClick = { confirmRevisionAction = "ACTIVATE" },
                        gradient = Gradients.Emerald,
                        compact = true
                    )
                }
                if (revision.status == BomRevisionStatus.ACTIVE && viewModel.hasPermission("bom", "approve")) {
                    SoftButton(
                        text = "Заменить",
                        onClick = { confirmRevisionAction = "REPLACE" },
                        tint = Brand.Amber
                    )
                }
                if (isDraft && viewModel.hasPermission("bom", "delete_draft")) {
                    SoftButton(text = "Отменить ревизию", onClick = { confirmRevisionAction = "CANCEL" }, tint = Brand.Rose)
                }
                if (confirmRevisionAction != null) {
                    val action = confirmRevisionAction!!
                    val (title, body) = when (action) {
                        "ACTIVATE" -> "Активировать ревизию?" to "Ревизия ${revision.revisionNumber} станет действующим составом изделия для производства и заменит предыдущую активную."
                        "REPLACE" -> "Заменить действующую ревизию?" to "Ревизия ${revision.revisionNumber} будет переведена в статус заменённой."
                        else -> "Отменить ревизию?" to "Черновик ревизии ${revision.revisionNumber} будет отменён без возможности редактирования."
                    }
                    org.example.project.ui.theme.ErpDialog(
                        title = title,
                        glyph = ErpIconGlyph.Layers,
                        gradient = Gradients.Ocean,
                        width = 500.dp,
                        confirmText = "Подтвердить",
                        onConfirm = {
                            confirmRevisionAction = null
                            when (action) {
                                "ACTIVATE" -> viewModel.activateRevision()
                                "REPLACE" -> viewModel.replaceRevision()
                                else -> viewModel.cancelRevision()
                            }
                        },
                        confirmGradient = Gradients.Ocean,
                        onDismiss = { confirmRevisionAction = null }
                    ) {
                        Text(body)
                    }
                }
                if (viewModel.hasPermission("bom", "explode")) {
                    SoftButton(text = "Разузлование", onClick = { viewModel.showDialog(BomDialog.Explode) }, tint = Brand.Indigo)
                }
                SoftButton(text = "Сравнить", onClick = viewModel::openDiffDialog, tint = Brand.Blue)
                SoftButton(text = "Применяемость", onClick = { viewModel.openWhereUsedDialog() }, tint = Brand.Teal)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canCreate) {
                GradientButton(text = createLabel, onClick = onCreate, gradient = Gradients.Aurora, compact = true)
            }
            SoftButton(text = "Обновить", onClick = onRefresh)
        }
    }
}

@Composable
private fun LoadingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        GradientSpinner()
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

@Composable
private fun BomRevisionDiffDialog(
    state: BomUiState,
    onSelectTarget: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val current = state.selectedRevision
    val others = state.revisions.filter { it.id != current?.id }
    var expanded by remember { mutableStateOf(false) }

    org.example.project.ui.theme.ErpDialog(
        title = "Сравнение ревизии ${current?.revisionNumber ?: ""}",
        glyph = ErpIconGlyph.Layers,
        gradient = Gradients.Primary,
        width = 680.dp,
        dismissText = "Закрыть",
        onDismiss = onDismiss
    ) {
        if (others.isEmpty()) {
            Text("Нет других ревизий для сравнения.")
        } else {
            Box {
                val selectedTarget = others.firstOrNull { it.id == state.diffTargetRevisionId } ?: others.first()
                OutlinedButton(onClick = { expanded = true }) {
                    Text("Сравнить с: ${selectedTarget.revisionNumber} (${selectedTarget.status.localizedName()})")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    others.forEach { rev ->
                        DropdownMenuItem(
                            text = { Text("${rev.revisionNumber} (${rev.status.localizedName()})") },
                            onClick = {
                                expanded = false
                                onSelectTarget(rev.id)
                            }
                        )
                    }
                }
            }
            state.operationError?.let { ErrorMessageCard(error = it) }
            val diff = state.revisionDiff
            if (diff != null) {
                if (diff.items.isEmpty()) {
                    Text("Различий между ревизиями не найдено.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(diff.items) { item ->
                            Text(

                                text = "[${item.kind.name}] ${item.code} · ${item.name} (${item.unit}): было ${item.leftQuantity ?: "—"}, стало ${item.rightQuantity ?: "—"}",

                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BomWhereUsedDialog(
    state: BomUiState,
    onSelectNomenclature: (Long) -> Unit,
    onOpenRevision: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedNom = state.nomenclature.firstOrNull { it.id == state.whereUsedNomenclatureId }

    org.example.project.ui.theme.ErpDialog(
        title = "Применяемость компонента (Where-Used)",
        glyph = ErpIconGlyph.Layers,
        gradient = Gradients.Primary,
        width = 680.dp,
        dismissText = "Закрыть",
        onDismiss = onDismiss
    ) {
        if (state.nomenclature.isEmpty()) {
            Text("Справочник номенклатуры пуст.")
        } else {
            Box {
                OutlinedButton(onClick = { expanded = true }) {

                    Text(selectedNom?.let { "${it.code} · ${it.name}" } ?: "Выберите компонент")

                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.nomenclature.take(40).forEach { nom ->
                        DropdownMenuItem(

                            text = { Text("${nom.code} · ${nom.name}") },

                            onClick = {
                                expanded = false
                                onSelectNomenclature(nom.id)
                            }
                        )
                    }
                }
            }
            state.operationError?.let { ErrorMessageCard(error = it) }
            if (state.whereUsedItems.isEmpty()) {
                Text("Компонент не используется ни в одной ревизии BOM.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.whereUsedItems) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${item.modelCode} (${item.revisionNumber}) • кол-во ${item.quantity} ${item.unit}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { onOpenRevision(item.revisionId) }) {
                                Text("Открыть")
                            }
                        }
                    }
                }
            }
        }
    }
}

