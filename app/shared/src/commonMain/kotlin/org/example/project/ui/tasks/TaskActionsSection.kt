package org.example.project.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus
import org.example.project.dto.TaskWorkType
import org.example.project.dto.UserDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.projects.UserDropdown

/**
 * Действия над задачей. Видимость каждого действия повторяет серверные
 * проверки статуса и роли; итоговое решение о правах принимает сервер,
 * его ошибка показывается из `state.actionError`.
 */
@Composable
fun TaskActionsSection(
    task: TaskDto,
    currentUser: UserDto,
    users: List<UserDto>,
    state: TasksUiState,
    viewModel: TaskViewModel,
    modifier: Modifier = Modifier
) {
    val isInitiator = task.initiatorId == currentUser.id
    val isAssignee = task.assigneeId == currentUser.id
    val isReviewer = task.reviewerId == currentUser.id
    val closedLike = task.isClosedLike()

    val canAssign = task.status == TaskStatus.NEW && isInitiator
    val canAccept = task.status == TaskStatus.ASSIGNED && isAssignee
    val canSubmitResult = isAssignee &&
        (task.status == TaskStatus.IN_PROGRESS || task.status == TaskStatus.REWORK)
    val canSendToReview = canSubmitResult && task.type == TaskWorkType.REVIEWED
    val canReview = task.status == TaskStatus.ON_REVIEW && isReviewer
    val canClose = task.status == TaskStatus.IN_PROGRESS &&
        task.type == TaskWorkType.GENERAL && (isAssignee || isInitiator)
    val canBlock = !closedLike && task.status != TaskStatus.BLOCKED &&
        (isInitiator || isAssignee || isReviewer)
    val canUnblock = task.status == TaskStatus.BLOCKED &&
        (isInitiator || isAssignee ||
            task.blockedBy == currentUser.id || task.unblockResponsibleId == currentUser.id)
    val canReopen = closedLike && isInitiator
    val canCancel = !closedLike && isInitiator

    val hasAnyAction = canAssign || canAccept || canSubmitResult || canSendToReview ||
        canReview || canClose || canBlock || canUnblock || canReopen || canCancel
    if (!hasAnyAction && state.actionError == null) return

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
            Text(
                text = "Действия",
                style = MaterialTheme.typography.titleMedium
            )

            state.actionError?.let {
                ErrorMessageCard(error = it)
            }

            if (canAssign) {
                if (users.isNotEmpty()) {
                    UserDropdown(
                        users = users,
                        selected = users.find { it.id == state.assigneePickerId },
                        onSelect = { viewModel.updateAssigneePicker(it.id) },
                        label = "Исполнитель",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button(
                    onClick = { viewModel.assign(task) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Назначить исполнителя")
                }
            }

            if (canAccept) {
                Button(
                    onClick = { viewModel.accept(task) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Принять в работу")
                }
            }

            if (canSubmitResult) {
                OutlinedTextField(
                    value = state.resultDraft,
                    onValueChange = viewModel::updateResultDraft,
                    label = { Text("Результат выполнения *") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.submitResult(task) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Сохранить результат")
                }
            }

            if (canSendToReview) {
                Button(
                    onClick = { viewModel.sendToReview(task) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Отправить на проверку")
                }
            }

            if (canReview) {
                Button(
                    onClick = { viewModel.reviewAccept(task) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Принять результат")
                }
                OutlinedTextField(
                    value = state.returnReason,
                    onValueChange = viewModel::updateReturnReason,
                    label = { Text("Причина возврата *") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { viewModel.reviewReturn(task) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Вернуть на доработку")
                }
            }

            if (canClose) {
                Button(
                    onClick = { viewModel.close(task) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Закрыть задачу")
                }
            }

            if (canUnblock) {
                Button(
                    onClick = { viewModel.unblock(task) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Снять блокировку")
                }
            }

            if (canBlock) {
                OutlinedTextField(
                    value = state.blockReason,
                    onValueChange = viewModel::updateBlockReason,
                    label = { Text("Причина блокировки *") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                if (users.isNotEmpty()) {
                    UserDropdown(
                        users = users,
                        selected = users.find { it.id == state.unblockResponsibleId },
                        onSelect = { viewModel.updateUnblockResponsible(it.id) },
                        label = "Ответственный за снятие блокировки",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.block(task) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Заблокировать")
                }
            }

            if (canReopen) {
                OutlinedTextField(
                    value = state.reopenReason,
                    onValueChange = viewModel::updateReopenReason,
                    label = { Text("Причина повторного открытия *") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.reopen(task) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Открыть повторно")
                }
            }

            if (canCancel) {
                OutlinedTextField(
                    value = state.cancelReason,
                    onValueChange = viewModel::updateCancelReason,
                    label = { Text("Причина отмены *") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { viewModel.cancel(task) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Отменить задачу")
                }
            }
        }
    }
}
