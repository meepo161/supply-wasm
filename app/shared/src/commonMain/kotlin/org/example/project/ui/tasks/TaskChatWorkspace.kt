package org.example.project.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import org.example.project.ui.theme.ErpFilterChip as FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.ConversationListItemDto
import org.example.project.dto.TaskChatListFilter
import org.example.project.dto.TaskChatListItemDto
import org.example.project.dto.TaskDto
import org.example.project.dto.UserDto
import org.example.project.ui.conversations.AttachmentRow
import org.example.project.ui.conversations.ConversationPanel
import org.example.project.ui.conversations.ConversationViewModel
import org.example.project.ui.conversations.ConversationsUiState
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.PillBadge

/**
 * Трёхколоночный Task Chat (D-51): слева список чатов задач (280 dp),
 * по центру сообщения, справа сворачиваемая ERP-панель (320 dp).
 * <1440 dp правая панель свёрнута по умолчанию; <1100 dp снаружи действует
 * вкладочный режим M.5 (см. taskWorkspaceLayout).
 */
@Composable
fun TaskChatWorkspaceScreen(
    task: TaskDto,
    currentUser: UserDto,
    state: TasksUiState,
    viewModel: TaskViewModel,
    conversationState: ConversationsUiState,
    conversationViewModel: ConversationViewModel,
    showChatList: Boolean = true,
    unifiedForwardTargets: List<ConversationListItemDto> = emptyList(),
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        var contextOpen by rememberSaveable(task.id) {
            mutableStateOf(!taskChatContextCollapsed(maxWidth.value.toInt()))
        }
        Row(modifier = Modifier.fillMaxSize()) {
            if (showChatList) {
                TaskChatListColumn(
                    state = state,
                    selectedTaskId = task.id,
                    onSelect = viewModel::openTaskById,
                    onTogglePin = viewModel::toggleChatPin,
                    onQueryChange = viewModel::updateChatListQuery,
                    onFilterChange = viewModel::setChatListFilter,
                    modifier = Modifier.width(280.dp).fillMaxHeight()
                )
                VerticalDivider()
            }
            TaskChatCenterColumn(
                task = task,
                currentUser = currentUser,
                state = state,
                viewModel = viewModel,
                conversationState = conversationState,
                conversationViewModel = conversationViewModel,
                unifiedForwardTargets = unifiedForwardTargets,
                contextOpen = contextOpen,
                onToggleContext = { contextOpen = !contextOpen },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            if (contextOpen) {
                VerticalDivider()
                TaskChatContextPanel(
                    task = task,
                    currentUser = currentUser,
                    state = state,
                    viewModel = viewModel,
                    onClose = { contextOpen = false },
                    modifier = Modifier.width(320.dp).fillMaxHeight()
                )
            }
        }
    }
}

/* ------------------------------ Левая колонка ------------------------------ */

@Composable
private fun TaskChatListColumn(
    state: TasksUiState,
    selectedTaskId: Long,
    onSelect: (Long) -> Unit,
    onTogglePin: (Long) -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (TaskChatListFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.chatListQuery,
            onValueChange = onQueryChange,
            label = { Text("Поиск по чатам") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TaskChatListFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.chatListFilter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(filter.label()) }
                )
            }
        }
        state.chatListError?.let { ErrorMessageCard(error = it) }
        val items = filterChatList(state.chatList, state.chatListFilter, state.chatListQuery)
        when {
            state.chatListLoading && items.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            items.isEmpty() -> Text(
                text = "Чатов по этому фильтру нет.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items, key = { it.taskId }) { item ->
                    TaskChatListRow(
                        item = item,
                        selected = item.taskId == selectedTaskId,
                        onClick = { onSelect(item.taskId) },
                        onTogglePin = { onTogglePin(item.taskId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskChatListRow(
    item: TaskChatListItemDto,
    selected: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLowest
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.number,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.status !in setOf("CLOSED", "CANCELLED")) {
                    TextButton(onClick = onTogglePin) {
                        Text(
                            text = if (item.isPinned) "Открепить" else "Закрепить",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            if (!item.lastMessageSnippet.isNullOrBlank()) {
                Text(
                    text = listOfNotNull(item.lastMessageAuthor, item.lastMessageSnippet)
                        .joinToString(": "),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.lastMessageAt?.let {
                    Text(
                        text = it.toShortDateTime(),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                if (item.mentionCount > 0) {
                    PillBadge(text = "@${item.mentionCount}", accent = Brand.Crimson, filled = true)
                    Spacer(Modifier.width(6.dp))
                }
                if (item.unreadCount > 0) {
                    PillBadge(text = item.unreadCount.toString(), accent = Brand.Blue, filled = true)
                }
            }
        }
    }
}

/* ------------------------------ Центральная колонка ------------------------------ */

@Composable
private fun TaskChatCenterColumn(
    task: TaskDto,
    currentUser: UserDto,
    state: TasksUiState,
    viewModel: TaskViewModel,
    conversationState: ConversationsUiState,
    conversationViewModel: ConversationViewModel,
    unifiedForwardTargets: List<ConversationListItemDto>,
    contextOpen: Boolean,
    onToggleContext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canWrite = conversationState.selectedConversation?.canWrite == true && !task.isClosedLike()
    // Та же подложка, рамка, отступы и шапка, что у личного, группового и проектного чата
    // (ConversationDetail): задачный чат отличается только своими действиями справа.
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(name = task.title, size = 36.dp)
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Задачи · ${task.number}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PillBadge(text = task.status.localizedName(), accent = task.status.accentColor())
                    if (!canWrite) {
                        PillBadge(text = "Только чтение", accent = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        // Действия задачи отдельной строкой: в узкой колонке они не вытесняют название.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TaskShareAction(task, state, viewModel)
            TextButton(onClick = onToggleContext) {
                Text(if (contextOpen) "Скрыть сведения" else "Сведения")
            }
            TextButton(onClick = viewModel::openFullCard) {
                Text("Карточка")
            }
        }

        conversationState.selectedConversation?.let { conversation ->
            ConversationPanel(
                messages = conversationState.messages,
                canWrite = canWrite,
                currentUserId = currentUser.id,
                readOnlyHint = if (task.isClosedLike()) {
                    "Задача закрыта. Обсуждение доступно только для чтения."
                } else {
                    "Писать в обсуждение могут исполнитель, соисполнители, инициатор и проверяющий задачи. Вам оно доступно для чтения."
                },
                onDraftChange = {
                    conversationViewModel.updateDraft(it)
                    if (it.isNotBlank()) viewModel.notifyTyping()
                },
                onSend = conversationViewModel::sendMessage,
                onRefresh = { conversationViewModel.refreshMessages() },
                onLoadMore = conversationViewModel::loadMoreNewer,
                onPickFile = conversationViewModel::pickFile,
                onClearFile = conversationViewModel::clearPendingFile,
                onDownloadAttachment = conversationViewModel::downloadAttachment,
                onOpenTask = viewModel::openTaskById,
                conversationKey = "${conversation.kind}:${conversation.id}",
                participants = conversation.members,
                forwardTargets = unifiedForwardTargets,
                typingUsers = state.chat.typingUsers,
                viewModel = conversationViewModel,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/* ------------------------------ Правая колонка ------------------------------ */

@Composable
private fun TaskChatContextPanel(
    task: TaskDto,
    currentUser: UserDto,
    state: TasksUiState,
    viewModel: TaskViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Сведения о задаче", style = MaterialTheme.typography.titleSmall)
            ErpIcon(
                ErpIconGlyph.Close,
                MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClose)
            )
        }

        Text("Описание", style = MaterialTheme.typography.titleSmall)
        if (task.description.isNullOrBlank()) {
            Text(
                text = "Описание не заполнено.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LinkifiedText(task.description!!, style = MaterialTheme.typography.bodyMedium)
        }
        task.acceptanceCriteria?.takeIf { it.isNotBlank() }?.let {
            Text("Критерии приёмки", style = MaterialTheme.typography.titleSmall)
            LinkifiedText(it, style = MaterialTheme.typography.bodyMedium)
        }

        ContextRow("Название", task.title)
        ContextRow("Статус", task.status.localizedName())
        ContextRow("Приоритет", task.priority.localizedName())
        ContextRow("Исполнитель", task.assigneeId?.let { userName(state.users, it) } ?: "не назначен")
        ContextRow("Инициатор", userName(state.users, task.initiatorId))
        if (task.coexecutorIds.isNotEmpty()) {
            ContextRow("Соисполнители", task.coexecutorIds.joinToString(", ") { userName(state.users, it) })
        }
        if (task.observerIds.isNotEmpty()) {
            ContextRow("Наблюдатели", task.observerIds.joinToString(", ") { userName(state.users, it) })
        }
        ContextRow("Срок", task.dueDate.toShortDate())
        task.projectId?.let { projectId ->
            val projectName = state.projects.find { it.id == projectId }
                ?.let { "${it.number} ${it.name}" } ?: "ID $projectId"
            ContextRow("Проект", projectName)
        }

        Text("Вложения", style = MaterialTheme.typography.titleSmall)
        val chatAttachments = collectChatAttachments(state.chat.messages)
        val allAttachments = (state.taskAttachments + chatAttachments).distinctBy { it.id }
        if (allAttachments.isEmpty()) {
            Text(
                text = "Вложений нет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            allAttachments.forEach { attachment ->
                AttachmentRow(
                    attachment = attachment,
                    isDownloading = state.chat.downloadingAttachmentId == attachment.id,
                    onDownload = { viewModel.downloadChatAttachment(attachment) }
                )
            }
        }

        Text("Документы", style = MaterialTheme.typography.titleSmall)
        if (state.linkedDocuments.isEmpty()) {
            Text(
                text = "Связанных документов нет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.linkedDocuments.forEach { document ->
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(text = document.number, style = MaterialTheme.typography.labelSmall, color = Brand.Blue)
                    Text(
                        text = document.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Button(onClick = viewModel::openFullCard, modifier = Modifier.fillMaxWidth()) {
            Text("Открыть карточку задачи")
        }
    }
}

@Composable
private fun ContextRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
