package org.example.project.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.data.conversationKey
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.UserDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.tasks.TaskChatWorkspaceScreen
import org.example.project.ui.tasks.TaskViewModel
import org.example.project.ui.tasks.defaultTaskChatId
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.IconBadge
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SkeletonLine
import org.example.project.ui.theme.SoftButton

@Composable
fun ConversationsScreen(
    viewModel: ConversationViewModel,
    taskViewModel: TaskViewModel,
    currentUser: UserDto,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val taskState by taskViewModel.uiState.collectAsState()
    val selectedTaskChatId = state.selectedConversation
        ?.takeIf { it.kind == ConversationKind.TASK }
        ?.taskId
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTaskChatId) {
        if (selectedTaskChatId != null && taskState.selectedTask?.id != selectedTaskChatId) {
            taskViewModel.openTaskById(selectedTaskChatId)
        }
    }

    when {
        selectedTaskChatId != null && taskState.selectedTask?.id == selectedTaskChatId -> TaskChatWorkspaceScreen(
            task = taskState.selectedTask!!,
            currentUser = currentUser,
            state = taskState,
            viewModel = taskViewModel,
            modifier = modifier.fillMaxSize()
        )
        state.selectedConversation != null -> ConversationDetail(
            conversation = state.selectedConversation!!,
            messages = state.messages,
            onBack = viewModel::backToList,
            onDraftChange = viewModel::updateDraft,
            onSend = viewModel::sendMessage,
            onRefresh = viewModel::refreshMessages,
            onLoadMore = viewModel::loadMoreNewer,
            onPickFile = viewModel::pickFile,
            onClearFile = viewModel::clearPendingFile,
            onDownloadAttachment = viewModel::downloadAttachment,
            modifier = modifier
        )
        state.isLoading && state.entries.isEmpty() -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> ConversationList(
            entries = state.entries,
            currentUserId = currentUser.id,
            isLoading = state.isLoading,
            error = state.error,
            onRefresh = viewModel::loadAll,
            onCreate = { showCreateDialog = true },
            onOpen = { entry ->
                if (entry.kind == ConversationKind.TASK) {
                    viewModel.openTaskChat(entry)
                } else viewModel.openEntry(entry)
            },
            modifier = modifier
        )
    }

    if (showCreateDialog) {
        ConversationCreateDialog(
            userOptions = state.userOptions,
            currentUserId = currentUser.id,
            error = state.createError,
            onCreateDirect = viewModel::createDirect,
            onCreateGroup = viewModel::createGroup,
            onDismiss = { showCreateDialog = false }
        )
    }
}

@Composable
private fun ConversationList(
    entries: List<ConversationDto>,
    currentUserId: Long,
    isLoading: Boolean,
    error: org.example.project.dto.ApiError?,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (ConversationDto) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        ConversationListHeader(entries.size, isLoading, onRefresh, onCreate)
        error?.let { ErrorMessageCard(it, Modifier.padding(bottom = 12.dp)) }
        if (entries.isEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    title = "Пока нет чатов",
                    text = "Создайте личный или групповой чат, либо откройте обсуждение задачи.",
                    glyph = ErpIconGlyph.Send,
                    accent = Brand.Blue
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { conversationKey(it.kind, it.id) }) { entry ->
                    ConversationRow(entry, currentUserId) { onOpen(entry) }
                }
            }
        }
    }
}

@Composable
private fun ConversationDetail(
    conversation: ConversationDto,
    messages: ConversationMessagesUiState,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPickFile: () -> Unit,
    onClearFile: () -> Unit,
    onDownloadAttachment: (org.example.project.dto.AttachmentDto) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("К списку") }
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(conversation.displayTitle(0), style = MaterialTheme.typography.titleLarge)
                Text(conversation.kind.localizedName(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ConversationPanel(
            messages = messages,
            canWrite = conversation.canWrite,
            readOnlyHint = "Обсуждение доступно только для чтения.",
            onDraftChange = onDraftChange,
            onSend = onSend,
            onRefresh = onRefresh,
            onLoadMore = onLoadMore,
            onPickFile = onPickFile,
            onClearFile = onClearFile,
            onDownloadAttachment = onDownloadAttachment,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ConversationListHeader(
    total: Int,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onCreateClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(ErpIconGlyph.Send, gradient = Gradients.Ocean, size = 40.dp, iconSize = 20.dp)
            Spacer(Modifier.width(13.dp))
            Column {
                Text(
                    text = "Чат",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Личные, групповые, проектные и задачные · $total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftButton(
                text = "Обновить",
                onClick = onRefresh,
                enabled = !isLoading,
                leadingIcon = ErpIconGlyph.Refresh
            )
            Spacer(Modifier.width(10.dp))
            GradientButton(
                text = "Новый чат",
                onClick = onCreateClick,
                leadingIcon = ErpIconGlyph.Plus
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationDto,
    currentUserId: Long,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(name = conversation.displayTitle(currentUserId), size = 36.dp)
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.displayTitle(currentUserId),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = conversation.kind.localizedName() + " · участников: " + conversation.members.size,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
