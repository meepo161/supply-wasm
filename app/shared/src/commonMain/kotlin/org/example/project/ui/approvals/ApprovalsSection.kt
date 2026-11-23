package org.example.project.ui.approvals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.data.ApprovalRepository
import org.example.project.dto.ApprovalDecision
import org.example.project.dto.ApprovalRouteDto
import org.example.project.dto.ApprovalRouteStatus
import org.example.project.dto.ApprovalStageDto
import org.example.project.dto.UserDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.tasks.toShortDateTime
import org.koin.compose.koinInject

/**
 * Переиспользуемая панель маршрутов согласования объекта. Глобальной очереди
 * согласований нет: маршруты запрашиваются по (targetType, targetId).
 * Действия (запуск, решения, отмена) показываются только при соответствующих
 * правах и состоянии объекта; сервер остаётся источником истины.
 */
@Composable
fun ApprovalsSection(
    targetType: String,
    targetId: Long,
    currentUser: UserDto,
    startAllowed: Boolean,
    onTargetChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val repository: ApprovalRepository = koinInject()
    val viewModel = remember(targetType, targetId, currentUser.id) {
        ApprovalViewModel(repository, currentUser, onTargetChanged)
    }
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(targetType, targetId) {
        viewModel.load(targetType, targetId)
    }

    if (state.readDenied) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Согласование", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = viewModel::reload) { Text("Обновить") }
            }

            state.error?.let { ErrorMessageCard(error = it) }

            if (state.isLoading && state.routes.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.routes.isEmpty()) {
                Text(
                    text = "Маршрутов согласования нет.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.routes.forEach { route ->
                        RouteListItem(
                            route = route,
                            selected = state.selectedRoute?.id == route.id,
                            onClick = { viewModel.selectRoute(route) }
                        )
                    }
                }
            }

            state.selectedRoute?.let { route ->
                HorizontalDivider()
                RouteDetail(
                    route = route,
                    state = state,
                    viewModel = viewModel,
                    currentUser = currentUser
                )
            }

            if (startAllowed && viewModel.canStartRoute() && state.routes.activeRoute() == null) {
                StartRouteBlock(state = state, viewModel = viewModel)
            }
        }
    }

    if (state.cancelDialogOpen) {
        CancelRouteDialog(
            reason = state.cancelReason,
            busy = state.isCancelling,
            onReasonChange = viewModel::updateCancelReason,
            onConfirm = viewModel::cancelRoute,
            onDismiss = viewModel::dismissCancelDialog
        )
    }
}

@Composable
private fun RouteListItem(
    route: ApprovalRouteDto,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Маршрут #${route.id} · этап ${route.currentStageNo}/${route.stages.size} · начат ${route.startedAt.toShortDateTime()}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = route.status.localizedName(),
            style = MaterialTheme.typography.labelMedium,
            color = if (route.status == ApprovalRouteStatus.ACTIVE) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun RouteDetail(
    route: ApprovalRouteDto,
    state: ApprovalUiState,
    viewModel: ApprovalViewModel,
    currentUser: UserDto
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Статус: ${route.status.localizedName()}" +
                (route.completedAt?.let { " · завершён ${it.toShortDateTime()}" } ?: ""),
            style = MaterialTheme.typography.bodyMedium
        )

        route.stages.sortedBy { it.stageNo }.forEach { stage ->
            StageRow(stage = stage, isCurrent = stage.stageNo == route.currentStageNo && !route.status.isTerminal())
        }

        if (route.decisions.isNotEmpty()) {
            Text(text = "История решений", style = MaterialTheme.typography.titleSmall)
            route.decisions.forEach { decision ->
                Column {
                    Text(
                        text = "${decision.createdAt.toShortDateTime()} · " +
                            (decision.decidedByFullName ?: "ID ${decision.decidedBy}") +
                            " · ${decision.decision.localizedName()} (этап ${decision.stageNo})",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    decision.comment?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (canUserDecideCurrentStage(route, currentUser.id)) {
            OutlinedTextField(
                value = state.decisionComment,
                onValueChange = viewModel::updateDecisionComment,
                label = { Text("Комментарий (обязателен при отклонении/возврате)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                enabled = !state.isSubmitting
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.submitDecision(ApprovalDecision.APPROVE) },
                    enabled = !state.isSubmitting
                ) { Text("Согласовать") }
                OutlinedButton(
                    onClick = { viewModel.submitDecision(ApprovalDecision.REJECT) },
                    enabled = !state.isSubmitting
                ) { Text("Отклонить") }
                OutlinedButton(
                    onClick = { viewModel.submitDecision(ApprovalDecision.RETURN) },
                    enabled = !state.isSubmitting
                ) { Text("Вернуть") }
            }
        }

        if (route.status == ApprovalRouteStatus.ACTIVE && viewModel.canCancelRoute()) {
            OutlinedButton(
                onClick = viewModel::showCancelDialog,
                enabled = !state.isCancelling
            ) { Text("Отменить маршрут", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun StageRow(stage: ApprovalStageDto, isCurrent: Boolean) {
    Column {
        Text(
            text = "${stage.stageNo}. ${stage.name} · ${stage.status.localizedName()}" +
                if (isCurrent) " (текущий)" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "${stage.decisionPolicy.localizedName()}: " +
                stage.assignees.joinToString(", ") { it.userFullName ?: it.roleName ?: "ID ${it.id}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartRouteBlock(state: ApprovalUiState, viewModel: ApprovalViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        if (!state.templatesLoaded) {
            Text(
                text = "Загрузка шаблонов…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (state.templates.isEmpty()) {
            Text(
                text = "Нет активных шаблонов маршрутов для этого объекта.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            var expanded by remember { mutableStateOf(false) }
            val selectedTemplate = state.templates.firstOrNull { it.id == state.selectedTemplateId }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedTemplate?.let { "${it.code} · ${it.name}" } ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Шаблон маршрута") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    state.templates.forEach { template ->
                        DropdownMenuItem(
                            text = {
                                Text("${template.code} · ${template.name} (${template.stages.size} этап.)")
                            },
                            onClick = {
                                viewModel.selectTemplate(template.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
            Button(
                onClick = viewModel::startRoute,
                enabled = !state.isStarting && state.selectedTemplateId != null
            ) {
                Text(if (state.isStarting) "Запуск…" else "Запустить согласование")
            }
        }
    }
}

@Composable
private fun CancelRouteDialog(
    reason: String,
    busy: Boolean,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    org.example.project.ui.theme.ErpDialog(
        title = "Отменить маршрут согласования",
        glyph = org.example.project.ui.theme.ErpIconGlyph.Documents,
        gradient = org.example.project.ui.theme.Gradients.Sunset,
        width = 520.dp,
        confirmText = "Отменить маршрут",
        onConfirm = onConfirm,
        confirmEnabled = !busy,
        confirmGradient = org.example.project.ui.theme.Gradients.Sunset,
        dismissText = "Закрыть",
        onDismiss = onDismiss
    ) {
        OutlinedTextField(
            value = reason,
            onValueChange = onReasonChange,
            label = { Text("Причина отмены *") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = !busy
        )
    }
}
