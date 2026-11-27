package org.example.project.ui.tasks

import org.example.project.ui.userText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import org.example.project.ui.theme.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import org.example.project.ui.theme.OutlinedButton
import org.example.project.ui.theme.OutlinedTextField
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus
import org.example.project.dto.TaskWorkType
import org.example.project.dto.UserDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.projects.UserDropdown
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.SoftButton

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

            var showBlockDialog by remember(task.id) { mutableStateOf(false) }
            var showCancelDialog by remember(task.id) { mutableStateOf(false) }

            LaunchedEffect(task.status) {
                if (task.status == TaskStatus.BLOCKED) showBlockDialog = false
                if (task.status == TaskStatus.CANCELLED) showCancelDialog = false
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

            if (canBlock || canCancel) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (canBlock) {
                        SoftButton(
                            text = "Заблокировать",
                            onClick = { showBlockDialog = true },
                            tint = Brand.Amber,
                            leadingIcon = ErpIconGlyph.Alert,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (canCancel) {
                        SoftButton(
                            text = "Отменить задачу",
                            onClick = { showCancelDialog = true },
                            tint = Brand.Rose,
                            leadingIcon = ErpIconGlyph.Close,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (showBlockDialog) {
                ErpDialog(
                    title = "Заблокировать задачу",
                    onDismiss = { showBlockDialog = false },
                    confirmText = "Заблокировать",
                    onConfirm = { viewModel.block(task) },
                    confirmEnabled = state.blockReason.isNotBlank(),
                    dismissText = "Отмена"
                ) {
                    state.actionError?.let { ErrorMessageCard(error = it) }
                    OutlinedTextField(
                        value = state.blockReason,
                        onValueChange = viewModel::updateBlockReason,
                        label = { Text("Причина блокировки *") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (users.isNotEmpty()) {
                        UserDropdown(
                            users = users,
                            selected = users.find { it.id == state.unblockResponsibleId },
                            onSelect = { viewModel.updateUnblockResponsible(it.id) },
                            label = "Ответственный за снятие блокировки *",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            if (showCancelDialog) {
                ErpDialog(
                    title = "Отменить задачу",
                    onDismiss = { showCancelDialog = false },
                    confirmText = "Отменить задачу",
                    onConfirm = { viewModel.cancel(task) },
                    confirmEnabled = state.cancelReason.isNotBlank(),
                    dismissText = "Назад"
                ) {
                    state.actionError?.let { ErrorMessageCard(error = it) }
                    OutlinedTextField(
                        value = state.cancelReason,
                        onValueChange = viewModel::updateCancelReason,
                        label = { Text("Причина отмены *") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // PRJ-12: Зависимости задачи
            val extrasRepo: org.example.project.data.ErpExtrasRepository = koinInject()
            val depScope = rememberCoroutineScope()
            var deps by remember(task.id) {
                mutableStateOf<List<org.example.project.dto.TaskDependencyDto>>(emptyList())
            }
            var predIdInput by remember(task.id) { mutableStateOf("") }
            var depError by remember(task.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(task.id) {
                val res = extrasRepo.listTaskDependencies(task.id)
                if (res is org.example.project.data.ApiResult.Success) deps = res.data
            }
            HorizontalDivider()
            Text("Зависимости задачи (${deps.size})", style = MaterialTheme.typography.titleSmall)
            depError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            deps.forEach { d ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "#${d.predecessorTaskId} ${d.predecessorTitle} → #${d.successorTaskId} ${d.successorTitle}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        depScope.launch {
                            val del = extrasRepo.deleteTaskDependency(d.id)
                            if (del is org.example.project.data.ApiResult.Success) {
                                deps = deps.filterNot { it.id == d.id }
                            }
                        }
                    }) { Text("Удалить") }
                }
            }
            var selectedPredTask by remember(task.id) { mutableStateOf<TaskDto?>(null) }
            var predMenuOpen by remember(task.id) { mutableStateOf(false) }
            val availablePredTasks = remember(state.tasks, task.id, deps) {
                val linkedIds = deps.map { it.predecessorTaskId }.toSet()
                state.tasks.filter { it.id != task.id && it.id !in linkedIds }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                    SoftButton(
                        text = selectedPredTask?.let { "${it.number} · ${it.title}" } ?: "Выбрать задачу-предшественника…",
                        onClick = { predMenuOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = predMenuOpen,
                        onDismissRequest = { predMenuOpen = false }
                    ) {
                        if (availablePredTasks.isEmpty()) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Нет доступных задач") },
                                onClick = { predMenuOpen = false }
                            )
                        } else {
                            availablePredTasks.take(20).forEach { candidate ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("${candidate.number} · ${candidate.title}") },
                                    onClick = {
                                        selectedPredTask = candidate
                                        predMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
                Button(
                    enabled = selectedPredTask != null,
                    onClick = {
                        val predId = selectedPredTask?.id ?: return@Button
                        depScope.launch {
                            when (val res = extrasRepo.addTaskDependency(
                                org.example.project.dto.CreateTaskDependencyRequest(
                                    predecessorTaskId = predId,
                                    successorTaskId = task.id
                                )
                            )) {
                                is org.example.project.data.ApiResult.Success -> {
                                    deps = deps + res.data
                                    selectedPredTask = null
                                    depError = null
                                }
                                is org.example.project.data.ApiResult.Error -> depError = res.error.userText()
                            }
                        }
                    }
                ) { Text("Связать") }
            }

            // TASK-33: Связи задачи с объектами ERP
            var objLinks by remember(task.id) {
                mutableStateOf<List<org.example.project.dto.TaskObjectLinkDto>>(emptyList())
            }
            var linkObjType by remember(task.id) { mutableStateOf("DEMAND") }
            var linkObjIdInput by remember(task.id) { mutableStateOf("") }
            LaunchedEffect(task.id) {
                val lRes = extrasRepo.listTaskObjectLinks(task.id)
                if (lRes is org.example.project.data.ApiResult.Success) objLinks = lRes.data
            }
            HorizontalDivider()
            Text("Связи с объектами ERP (${objLinks.size})", style = MaterialTheme.typography.titleSmall)
            objLinks.forEach { link ->
                Text(
                    "${link.objectType} #${link.objectId}${link.note?.let { ": $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = linkObjType,
                    onValueChange = { linkObjType = it },
                    label = { Text("Тип (DEMAND/DOCUMENT/...)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = linkObjIdInput,
                    onValueChange = { linkObjIdInput = it },
                    label = { Text("ID объекта") },
                    singleLine = true,
                    modifier = Modifier.weight(0.8f)
                )
                Button(onClick = {
                    val objId = linkObjIdInput.trim().toLongOrNull()
                    if (objId != null && linkObjType.isNotBlank()) {
                        depScope.launch {
                            val res = extrasRepo.addTaskObjectLink(
                                task.id,
                                org.example.project.dto.CreateTaskObjectLinkRequest(
                                    objectType = linkObjType.trim().uppercase(),
                                    objectId = objId
                                )
                            )
                            if (res is org.example.project.data.ApiResult.Success) {
                                objLinks = listOf(res.data) + objLinks
                                linkObjIdInput = ""
                            }
                        }
                    }
                }) { Text("Привязать") }
            }
        }
    }
}
