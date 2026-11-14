package org.example.project.ui.production

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.ErpTextField
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.SoftButton

@Composable
fun ProductionDialogs(
    state: ProductionUiState,
    viewModel: ProductionViewModel
) {
    val dialog = state.activeDialog ?: return
    when (dialog) {
        ProductionDialog.CreateOrder -> CreateOrderDialog(state, viewModel)
        ProductionDialog.CancelOrder -> CancelOrderDialog(state, viewModel)
        ProductionDialog.CompleteOrder -> CompleteOrderDialog(state, viewModel)
        ProductionDialog.HoldOrder -> HoldOrderDialog(state, viewModel)
        is ProductionDialog.SkipStage -> SkipStageDialog(dialog, state, viewModel)
    }
}

@Composable
private fun CreateOrderDialog(state: ProductionUiState, viewModel: ProductionViewModel) {
    val form = state.createForm
    ErpDialog(
        title = "Новый производственный заказ",
        subtitle = "Ревизия BOM должна быть утверждена или действующей",
        glyph = ErpIconGlyph.Plus,
        gradient = Gradients.Aurora,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Создать",
        onConfirm = viewModel::createOrder,
        confirmEnabled = !state.isActionRunning && state.models.isNotEmpty(),
        width = 620.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            if (state.modelsAccessDenied || state.models.isEmpty()) {
                Text(
                    text = "Справочник изделий недоступен (нужно право bom:read_any) — " +
                        "создать заказ нельзя. Обратитесь к администратору.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Rose
                )
            }
            ProjectDropdown(
                projects = state.projects,
                selectedId = form.projectId,
                onSelect = { viewModel.updateCreateForm(form.copy(projectId = it)) },
                allowEmpty = false,
                modifier = Modifier.fillMaxWidth()
            )
            ProductModelDropdown(
                models = state.models,
                selectedId = form.modelId,
                onSelect = viewModel::onModelChanged,
                modifier = Modifier.fillMaxWidth()
            )
            BomRevisionDropdown(
                revisions = state.revisions,
                selectedId = form.revisionId,
                onSelect = { viewModel.updateCreateForm(form.copy(revisionId = it)) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.plannedQuantity,
                    onValueChange = { viewModel.updateCreateForm(form.copy(plannedQuantity = it)) },
                    label = "Количество к выпуску",
                    modifier = Modifier.weight(1f)
                )
                PriorityDropdown(
                    selected = form.priority,
                    onSelect = { viewModel.updateCreateForm(form.copy(priority = it)) },
                    modifier = Modifier.weight(1f)
                )
            }
            ResponsibleDropdown(
                users = state.users,
                selectedId = form.responsibleId,
                onSelect = { viewModel.updateCreateForm(form.copy(responsibleId = it)) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.plannedStartAt,
                    onValueChange = { viewModel.updateCreateForm(form.copy(plannedStartAt = it)) },
                    label = "План: начало",
                    placeholder = "2026-11-20T09:00+03:00",
                    modifier = Modifier.weight(1f)
                )
                ErpTextField(
                    value = form.plannedEndAt,
                    onValueChange = { viewModel.updateCreateForm(form.copy(plannedEndAt = it)) },
                    label = "План: окончание",
                    placeholder = "2026-11-25T18:00+03:00",
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = "Этапы производства (по порядку)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            form.stageTypes.forEachIndexed { index, type ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    StageTypeDropdown(
                        selected = type,
                        onSelect = { viewModel.updateStageType(index, it) },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.removeStage(index) },
                        enabled = form.stageTypes.size > 1
                    ) {
                        Text("Удалить")
                    }
                }
            }
            SoftButton(text = "Добавить этап", onClick = viewModel::addStage)
        }
    }
}

@Composable
private fun CancelOrderDialog(state: ProductionUiState, viewModel: ProductionViewModel) {
    ErpDialog(
        title = "Отмена заказа",
        subtitle = "Отмена возможна из черновика, плана и приостановки",
        glyph = ErpIconGlyph.Close,
        gradient = Gradients.Berry,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Отменить заказ",
        onConfirm = viewModel::submitCancel,
        confirmGradient = Gradients.Berry,
        confirmEnabled = !state.isActionRunning,
        dismissText = "Назад"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = state.cancelReason,
                onValueChange = viewModel::updateCancelReason,
                label = "Причина отмены",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun HoldOrderDialog(state: ProductionUiState, viewModel: ProductionViewModel) {
    ErpDialog(
        title = "Приостановка заказа",
        subtitle = "Укажите причину — она попадёт в журнал",
        glyph = ErpIconGlyph.Clock,
        gradient = Gradients.AmberGlow,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Приостановить",
        onConfirm = viewModel::submitHold,
        confirmGradient = Gradients.AmberGlow,
        confirmEnabled = !state.isActionRunning
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = state.noteReason,
                onValueChange = viewModel::updateNoteReason,
                label = "Причина",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CompleteOrderDialog(state: ProductionUiState, viewModel: ProductionViewModel) {
    val order = state.selectedOrder
    val form = state.completeForm
    val expected = form.actualQuantity.trim().toIntOrNull() ?: 0
    val serialCount = form.serialNumbers.split('\n', ',', ';').count { it.isNotBlank() }
    ErpDialog(
        title = "Завершение выпуска",
        subtitle = order?.let { "Заказ ${it.number}: план ${it.plannedQuantity}" } ?: null,
        glyph = ErpIconGlyph.CheckCircle,
        gradient = Gradients.Emerald,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Завершить выпуск",
        onConfirm = viewModel::submitComplete,
        confirmGradient = Gradients.Emerald,
        confirmEnabled = !state.isActionRunning,
        width = 560.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = form.actualQuantity,
                onValueChange = {
                    viewModel.updateCompleteForm(form.copy(actualQuantity = it))
                },
                label = "Фактически выпущено",
                modifier = Modifier.fillMaxWidth()
            )
            ErpTextField(
                value = form.serialNumbers,
                onValueChange = {
                    viewModel.updateCompleteForm(form.copy(serialNumbers = it))
                },
                label = "Серийные номера — по одному в строке",
                singleLine = false,
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Серийных номеров: $serialCount из $expected",
                style = MaterialTheme.typography.bodySmall,
                color = if (serialCount == expected && expected > 0) Brand.Mint else Brand.Amber
            )
            Text(
                text = "Экземпляры изделий создаются только после завершения всех этапов",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SkipStageDialog(
    dialog: ProductionDialog.SkipStage,
    state: ProductionUiState,
    viewModel: ProductionViewModel
) {
    ErpDialog(
        title = "Пропуск этапа",
        subtitle = dialog.stageName,
        glyph = ErpIconGlyph.Alert,
        gradient = Gradients.AmberGlow,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Пропустить этап",
        onConfirm = viewModel::submitSkip,
        confirmGradient = Gradients.AmberGlow,
        confirmEnabled = !state.isActionRunning
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = state.skipReason,
                onValueChange = viewModel::updateSkipReason,
                label = "Основание пропуска",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
