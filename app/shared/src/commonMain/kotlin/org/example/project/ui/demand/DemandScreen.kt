@file:OptIn(ExperimentalMaterial3Api::class)

package org.example.project.ui.demand

import org.example.project.ui.userText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.dto.DemandStatus
import org.example.project.dto.UserDto
import org.example.project.ui.approvals.ApprovalsSection
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.GradientSpinner
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SoftButton
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader

@Composable
fun DemandScreen(
    viewModel: DemandViewModel,
    currentUser: UserDto,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = state.currentPage.title,
            glyph = ErpIconGlyph.Cart,
            gradient = Gradients.Sunset,
            subtitle = "Заявки, закупки и покрытие потребностей",
            onBack = if (state.currentPage != DemandPage.LIST) viewModel::goBack else null
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp)
        ) {
            when (state.currentPage) {
                DemandPage.LIST -> DemandListPage(state, viewModel)
                DemandPage.DETAIL -> DemandDetailPage(state, viewModel, currentUser)
                DemandPage.COVERAGE -> DemandCoveragePage(state, viewModel)
            }
        }
    }

    when (val dialog = state.activeDialog) {
        DemandDialog.CreateDemand -> CreateDemandDialog(
            form = state.createDemandForm,
            projects = state.projects,
            models = state.models,
            revisions = state.revisions,
            nomenclature = state.nomenclature,
            error = state.operationError,
            onFormChange = viewModel::updateCreateDemandForm,
            onSourceTypeChange = viewModel::onSourceTypeChanged,
            onProjectSelected = viewModel::onProjectSelected,
            onModelSelected = viewModel::onModelSelected,
            onRevisionSelected = viewModel::onRevisionSelected,
            onAddLine = viewModel::addDemandLine,
            onUpdateLine = viewModel::updateDemandLine,
            onRemoveLine = viewModel::removeDemandLine,
            onCreate = viewModel::createDemand,
            onDismiss = viewModel::dismissDialog
        )
        DemandDialog.CancelDemand -> CancelDemandDialog(
            reason = state.cancelReason,
            error = state.operationError,
            onReasonChange = viewModel::updateCancelReason,
            onCancel = viewModel::cancelDemand,
            onDismiss = viewModel::dismissDialog
        )
        null -> { }
    }
}

@Composable
private fun DemandListPage(
    state: DemandUiState,
    viewModel: DemandViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val extrasRepo: org.example.project.data.ErpExtrasRepository = org.koin.compose.koinInject()
        val mergeScope = androidx.compose.runtime.rememberCoroutineScope()
        var showMergeDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var selectedToMerge by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Set<Long>>(emptySet()) }
        var mergeTitle by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        var mergeError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Всего заявок: ${state.demands.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (viewModel.hasPermission("create") && state.demands.size >= 2) {
                    SoftButton(text = "Объединить потребности", onClick = {
                        selectedToMerge = emptySet()
                        mergeTitle = ""
                        mergeError = null
                        showMergeDialog = true
                    })
                }
                SoftButton(text = "Обновить", onClick = viewModel::loadDemands, leadingIcon = ErpIconGlyph.Refresh)
                if (viewModel.hasPermission("create")) {
                    org.example.project.ui.theme.GradientButton(
                        text = "Новая потребность",
                        onClick = { viewModel.showDialog(DemandDialog.CreateDemand) },
                        gradient = Gradients.Sunset,
                        leadingIcon = ErpIconGlyph.Plus
                    )
                }
            }
        }
        if (showMergeDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showMergeDialog = false },
                title = { Text("Объединение потребностей") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        mergeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        org.example.project.ui.theme.ErpTextField(
                            value = mergeTitle,
                            onValueChange = { mergeTitle = it },
                            label = "Название объединённой потребности"
                        )
                        state.demands.filter {
                            it.status == org.example.project.dto.DemandStatus.DRAFT ||
                                it.status == org.example.project.dto.DemandStatus.PENDING_APPROVAL ||
                                it.status == org.example.project.dto.DemandStatus.APPROVED
                        }.take(15).forEach { d ->
                            val checked = d.id in selectedToMerge
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        selectedToMerge = if (isChecked) selectedToMerge + d.id else selectedToMerge - d.id
                                    }
                                )
                                Text("${d.number} (${d.status.name})", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
                confirmButton = {
                    SoftButton(text = "Объединить", onClick = {
                        if (selectedToMerge.size < 2) {
                            mergeError = "Выберите минимум 2 потребности"
                        } else {
                            mergeScope.launch {
                                when (val res = extrasRepo.mergeDemands(
                                    org.example.project.dto.MergeDemandsRequest(
                                        sourceDemandIds = selectedToMerge.toList(),
                                        targetTitle = mergeTitle.ifBlank { null }
                                    )
                                )) {
                                    is org.example.project.data.ApiResult.Success -> {
                                        showMergeDialog = false
                                        viewModel.loadDemands()
                                    }
                                    is org.example.project.data.ApiResult.Error -> mergeError = res.error.userText()
                                }
                            }
                        }
                    })
                },
                dismissButton = {
                    TextButton(onClick = { showMergeDialog = false }) { Text("Отмена") }
                }
            )
        }
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.demands.isEmpty()) {
            LoadingOverlay()
        } else if (state.demands.isEmpty()) {
            EmptyState("Нет потребностей. Создайте первую.")
        } else {
            DemandsGrid(
                demands = state.demands,
                onClick = { viewModel.selectDemand(it.id) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun DemandDetailPage(
    state: DemandUiState,
    viewModel: DemandViewModel,
    currentUser: UserDto
) {
    val demand = state.selectedDemand
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (demand == null) {
            LoadingOverlay()
            return@Column
        }
        DemandHeaderCard(demand, viewModel)
        state.operationError?.let { ErrorMessageCard(error = it) }
        ApprovalsSection(
            targetType = "DEMAND",
            targetId = demand.header.id,
            currentUser = currentUser,
            startAllowed = demand.header.status == DemandStatus.PENDING_APPROVAL,
            onTargetChanged = viewModel::refreshSelectedDemand
        )
        Text(text = "Позиции", style = MaterialTheme.typography.titleMedium)
        if (demand.lines.isEmpty()) {
            EmptyState("Нет позиций.")
        } else {
            DemandLinesGrid(
                lines = demand.lines,
                modifier = Modifier.fillMaxWidth().height(360.dp)
            )
        }
    }
}

@Composable
private fun DemandHeaderCard(
    demand: org.example.project.dto.DemandDetailDto,
    viewModel: DemandViewModel
) {
    val header = demand.header
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
                    text = "${header.number} — ${header.sourceType.localizedName()}",
                    style = MaterialTheme.typography.titleMedium
                )
                PillBadge(
                    text = header.status.localizedName(),
                    accent = when (header.status) {
                        DemandStatus.APPROVED -> Brand.Teal
                        DemandStatus.CANCELLED -> Brand.Rose
                        else -> Brand.Indigo
                    }
                )
            }
            Text(text = "Приоритет: ${header.priority.localizedName()}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Версия: ${header.version}", style = MaterialTheme.typography.bodyMedium)
            header.requiredDate?.let {
                Text(text = "Требуемая дата: ${it.substringBefore("T")}", style = MaterialTheme.typography.bodyMedium)
            }
            header.projectId?.let {
                Text(text = "Проект: $it", style = MaterialTheme.typography.bodyMedium)
            }
            if (!header.stockpileNote.isNullOrBlank()) {
                Text(text = "Обоснование задела: ${header.stockpileNote}", style = MaterialTheme.typography.bodyMedium)
            }
            header.sourceBomRevisionId?.let {
                Text(text = "Ревизия BOM: $it, выпуск: ${header.bomReleaseQuantity ?: "—"}", style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (header.status.canSubmit() && viewModel.hasPermission("update")) {
                    GradientButton(text = "Отправить", onClick = viewModel::submitDemand, gradient = Gradients.Ocean, compact = true)
                }
                if (header.status.canApprove() && viewModel.hasPermission("approve")) {
                    GradientButton(text = "Согласовать", onClick = viewModel::approveDemand, gradient = Gradients.Emerald, compact = true)
                }
                if (header.status.canCancel() && viewModel.hasPermission("update")) {
                    SoftButton(text = "Отменить", onClick = { viewModel.showDialog(DemandDialog.CancelDemand) }, tint = Brand.Rose)
                }
                SoftButton(text = "Покрытие", onClick = viewModel::showCoverage, tint = Brand.Indigo)
            }
        }
    }
}

@Composable
private fun DemandCoveragePage(
    state: DemandUiState,
    viewModel: DemandViewModel
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
            Text(text = "Покрытие потребности", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = viewModel::loadCoverage) { Text("Обновить") }
        }
        state.operationError?.let { ErrorMessageCard(error = it) }
        val coverage = state.coverage
        if (state.isLoading && coverage == null) {
            LoadingOverlay()
        } else if (coverage == null) {
            EmptyState("Нет данных о покрытии.")
        } else if (coverage.lines.isEmpty()) {
            EmptyState("Нет строк для расчёта покрытия.")
        } else {
            DemandCoverageGrid(
                coverage = coverage.lines,
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
