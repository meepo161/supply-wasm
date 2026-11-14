@file:OptIn(ExperimentalMaterial3Api::class)

package org.example.project.ui.demand

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
import org.example.project.dto.DemandStatus
import org.example.project.dto.UserDto
import org.example.project.ui.approvals.ApprovalsSection
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
        PageHeader(
            title = "Потребности",
            onCreate = { viewModel.showDialog(DemandDialog.CreateDemand) },
            onRefresh = viewModel::loadDemands,
            createLabel = "Новая потребность",
            canCreate = viewModel.hasPermission("create")
        )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${header.number} — ${header.sourceType.localizedName()}",
                style = MaterialTheme.typography.titleMedium
            )
            Text(text = "Статус: ${header.status.localizedName()}", style = MaterialTheme.typography.bodyMedium)
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
                    Button(onClick = viewModel::submitDemand) { Text("Отправить") }
                }
                if (header.status.canApprove() && viewModel.hasPermission("approve")) {
                    Button(onClick = viewModel::approveDemand) { Text("Согласовать") }
                }
                if (header.status.canCancel() && viewModel.hasPermission("update")) {
                    OutlinedButton(onClick = { viewModel.showDialog(DemandDialog.CancelDemand) }) { Text("Отменить") }
                }
                OutlinedButton(onClick = viewModel::showCoverage) { Text("Покрытие") }
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
