package org.example.project.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.data.conversationKey
import org.example.project.dto.ApiError
import org.example.project.dto.AttachmentDto
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.ConversationListFilter
import org.example.project.dto.ConversationListItemDto
import org.example.project.dto.UserDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.tasks.TaskChatWorkspaceScreen
import org.example.project.ui.tasks.TaskViewModel
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.IconBadge
import org.example.project.ui.theme.PillBadge
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
    LaunchedEffect(state.createdConversationKey) {
        if (state.createdConversationKey != null) {
            showCreateDialog = false
            viewModel.consumeCreateSuccess()
        }
    }

    val openCreateDialog = {
        viewModel.openCreateDialog()
        showCreateDialog = true
    }
    val backToList = {
        if (selectedTaskChatId != null) taskViewModel.backToList()
        viewModel.backToList()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val workspaceMode = conversationWorkspaceMode(maxWidth.value.toInt())
        val openConversation: (ConversationListItemDto) -> Unit = { item ->
            val entry = item.toConversationDto()
            if (entry.kind == ConversationKind.TASK) viewModel.openTaskChat(entry)
            else viewModel.openEntry(entry)
        }

        if (workspaceMode == ConversationWorkspaceMode.SPLIT) {
            Row(modifier = Modifier.fillMaxSize()) {
                ConversationList(
                    state = state,
                    currentUserId = currentUser.id,
                    onRefresh = viewModel::loadAll,
                    onCreate = openCreateDialog,
                    onOpen = openConversation,
                    onQueryChange = viewModel::updateQuery,
                    onFilterChange = viewModel::selectFilter,
                    onKindChange = viewModel::selectKind,
                    onLoadMore = viewModel::loadMore,
                    onDialogState = viewModel::changeDialogState,
                    compact = true,
                    modifier = Modifier.width(360.dp).fillMaxHeight()
                )
                VerticalDivider()
                when {
                    state.selectedConversation == null -> ConversationWorkspacePlaceholder(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    selectedTaskChatId != null && taskState.selectedTask?.id == selectedTaskChatId ->
                        TaskChatWorkspaceScreen(
                            task = taskState.selectedTask!!,
                            currentUser = currentUser,
                            state = taskState,
                            viewModel = taskViewModel,
                            showChatList = false,
                            unifiedForwardTargets = state.items,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    selectedTaskChatId != null -> TaskConversationLoading(
                        error = taskState.actionError,
                        onBack = backToList,
                        onRetry = { taskViewModel.openTaskById(selectedTaskChatId) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    else -> ConversationDetail(
                        conversation = state.selectedConversation!!,
                        messages = state.messages,
                        currentUserId = currentUser.id,
                        forwardTargets = state.items,
                        viewModel = viewModel,
                        onBack = viewModel::backToList,
                        onDraftChange = viewModel::updateDraft,
                        onSend = viewModel::sendMessage,
                        onRefresh = viewModel::refreshMessages,
                        onLoadMore = viewModel::loadMoreNewer,
                        onPickFile = viewModel::pickFile,
                        onClearFile = viewModel::clearPendingFile,
                        onDownloadAttachment = viewModel::downloadAttachment,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        } else when {
            selectedTaskChatId != null && taskState.selectedTask?.id == selectedTaskChatId -> Column(
                modifier = Modifier.fillMaxSize()
            ) {
                TextButton(onClick = backToList, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("← К единому списку чатов")
                }
                TaskChatWorkspaceScreen(
                    task = taskState.selectedTask!!,
                    currentUser = currentUser,
                    state = taskState,
                    viewModel = taskViewModel,
                    showChatList = false,
                    unifiedForwardTargets = state.items,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
            selectedTaskChatId != null -> TaskConversationLoading(
                error = taskState.actionError,
                onBack = backToList,
                onRetry = { taskViewModel.openTaskById(selectedTaskChatId) },
                modifier = Modifier.fillMaxSize()
            )
            state.selectedConversation != null -> ConversationDetail(
                conversation = state.selectedConversation!!,
                messages = state.messages,
                currentUserId = currentUser.id,
                forwardTargets = state.items,
                viewModel = viewModel,
                onBack = viewModel::backToList,
                onDraftChange = viewModel::updateDraft,
                onSend = viewModel::sendMessage,
                onRefresh = viewModel::refreshMessages,
                onLoadMore = viewModel::loadMoreNewer,
                onPickFile = viewModel::pickFile,
                onClearFile = viewModel::clearPendingFile,
                onDownloadAttachment = viewModel::downloadAttachment,
                modifier = Modifier.fillMaxSize()
            )
            else -> ConversationList(
                state = state,
                currentUserId = currentUser.id,
                onRefresh = viewModel::loadAll,
                onCreate = openCreateDialog,
                onOpen = openConversation,
                onQueryChange = viewModel::updateQuery,
                onFilterChange = viewModel::selectFilter,
                onKindChange = viewModel::selectKind,
                onLoadMore = viewModel::loadMore,
                onDialogState = viewModel::changeDialogState,
                compact = false,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showCreateDialog) {
        ConversationCreateDialog(
            userOptions = state.userOptions,
            currentUserId = currentUser.id,
            isLoadingDirectory = state.isLoadingUserOptions,
            directoryError = state.userOptionsError,
            isCreating = state.isCreating,
            error = state.createError,
            onCreateDirect = viewModel::createDirect,
            onCreateGroup = viewModel::createGroup,
            onRetryDirectory = viewModel::loadUserOptions,
            onDismiss = { showCreateDialog = false }
        )
    }
}

@Composable
private fun ConversationList(
    state: ConversationsUiState,
    currentUserId: Long,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (ConversationListItemDto) -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (ConversationListFilter) -> Unit,
    onKindChange: (ConversationKind?) -> Unit,
    onLoadMore: () -> Unit,
    onDialogState: (ConversationListItemDto, Boolean?, Boolean?, Boolean?) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (compact) 12.dp else 24.dp, vertical = if (compact) 12.dp else 0.dp)
    ) {
        ConversationListHeader(state.items.size, state.isLoading, onRefresh, onCreate, compact)
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Поиск по чатам") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        ConversationFilters(
            selectedFilter = state.listFilter,
            selectedKind = state.kindFilter,
            onFilterChange = onFilterChange,
            onKindChange = onKindChange
        )
        state.error?.let { ErrorMessageCard(it, Modifier.padding(bottom = 12.dp)) }
        when {
            state.isLoading && state.items.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Загрузка чатов…", modifier = Modifier.padding(top = 12.dp))
                }
            }
            state.items.isEmpty() -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                when (conversationEmptyState(state.query, state.kindFilter, state.listFilter)) {
                    ConversationEmptyState.NO_CHATS -> EmptyState(
                        title = "Пока нет чатов",
                        text = "Создайте личный или групповой чат, либо откройте обсуждение задачи.",
                        glyph = ErpIconGlyph.Send,
                        accent = Brand.Blue
                    )
                    ConversationEmptyState.NO_MATCHES -> EmptyState(
                        title = "Ничего не найдено",
                        text = "Измените поисковый запрос или выберите другой фильтр.",
                        glyph = ErpIconGlyph.Search,
                        accent = Brand.Blue
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.isLoading) {
                    item(key = "refreshing") {
                        Text(
                            "Обновление списка…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(state.items, key = { conversationKey(it.kind, it.id) }) { item ->
                    ConversationRow(
                        item = item,
                        currentUserId = currentUserId,
                        selected = item.kind == state.selectedConversation?.kind &&
                            item.toConversationDto().id == state.selectedConversation.id,
                        actionInProgress = conversationKey(item.kind, item.id) in state.pendingDialogActions,
                        onClick = { onOpen(item) },
                        onDialogState = onDialogState
                    )
                }
                if (state.hasMore) {
                    item(key = "load-more") {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = onLoadMore, enabled = !state.isLoadingMore) {
                                Text(if (state.isLoadingMore) "Загрузка…" else "Загрузить ещё")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationFilters(
    selectedFilter: ConversationListFilter,
    selectedKind: ConversationKind?,
    onFilterChange: (ConversationListFilter) -> Unit,
    onKindChange: (ConversationKind?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ConversationListFilter.entries.forEach { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterChange(filter) },
                label = { Text(filter.localizedName()) }
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ConversationKind.entries.forEach { kind ->
            FilterChip(
                selected = selectedKind == kind,
                onClick = { onKindChange(if (selectedKind == kind) null else kind) },
                label = { Text(kind.localizedName()) }
            )
        }
    }
}

@Composable
private fun ConversationRow(
    item: ConversationListItemDto,
    currentUserId: Long,
    selected: Boolean,
    actionInProgress: Boolean,
    onClick: () -> Unit,
    onDialogState: (ConversationListItemDto, Boolean?, Boolean?, Boolean?) -> Unit
) {
    val conversation = item.toConversationDto()
    val title = conversation.displayTitle(currentUserId)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Открыть чат $title, ${item.kind.localizedName()}" },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name = title, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            item.kind.localizedName(),
                            if (item.isPinned) "Закреплено" else null,
                            if (item.isMuted) "Без звука" else null,
                            if (item.isArchived) "Архив" else null,
                            if (!item.canWrite) "Только чтение" else null
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = conversationTimeLabel(item.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = item.previewText(currentUserId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.mentionCount > 0) {
                    PillBadge(text = mentionBadgeLabel(item.mentionCount), accent = Brand.Crimson, filled = true)
                    Spacer(Modifier.width(6.dp))
                }
                if (item.unreadCount > 0) {
                    PillBadge(text = unreadBadgeLabel(item.unreadCount), accent = Brand.Blue, filled = true)
                }
                Spacer(Modifier.weight(1f))
                if (actionInProgress) Text("Сохранение…", style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(
                    onClick = { onDialogState(item, !item.isPinned, null, null) },
                    enabled = !actionInProgress
                ) { Text(if (item.isPinned) "Открепить" else "Закрепить") }
                TextButton(
                    onClick = { onDialogState(item, null, !item.isMuted, null) },
                    enabled = !actionInProgress
                ) { Text(if (item.isMuted) "Включить звук" else "Без звука") }
                TextButton(
                    onClick = { onDialogState(item, null, null, !item.isArchived) },
                    enabled = !actionInProgress
                ) { Text(if (item.isArchived) "Из архива" else "Архив") }
            }
        }
    }
}

@Composable
private fun TaskConversationLoading(
    error: ApiError?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(20.dp)) {
        TextButton(onClick = onBack) { Text("← К единому списку чатов") }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ErrorMessageCard(error)
                    TextButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Повторить открытие задачи")
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Открытие обсуждения задачи…", modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun ConversationDetail(
    conversation: ConversationDto,
    messages: ConversationMessagesUiState,
    currentUserId: Long,
    forwardTargets: List<ConversationListItemDto>,
    viewModel: ConversationViewModel,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPickFile: () -> Unit,
    onClearFile: () -> Unit,
    onDownloadAttachment: (AttachmentDto) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("К списку") }
            Avatar(name = conversation.displayTitle(currentUserId), size = 36.dp)
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Text(conversation.displayTitle(currentUserId), style = MaterialTheme.typography.titleLarge)
                Text(conversation.kind.localizedName(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PillBadge(
                text = if (conversation.canWrite) "Можно писать" else "Только чтение",
                accent = if (conversation.canWrite) Brand.Blue else MaterialTheme.colorScheme.outline
            )
        }
        ConversationPanel(
            messages = messages,
            canWrite = conversation.canWrite,
            currentUserId = currentUserId,
            readOnlyHint = "Обсуждение доступно только для чтения.",
            onDraftChange = onDraftChange,
            onSend = onSend,
            onRefresh = onRefresh,
            onLoadMore = onLoadMore,
            onPickFile = onPickFile,
            onClearFile = onClearFile,
            onDownloadAttachment = onDownloadAttachment,
            modifier = Modifier.weight(1f),
            conversationKey = "${conversation.kind}:${conversation.id}",
            participants = conversation.members,
            forwardTargets = forwardTargets,
            viewModel = viewModel
        )
    }
}

@Composable
private fun ConversationListHeader(
    total: Int,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onCreateClick: () -> Unit,
    compact: Boolean
) {
    if (compact) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            ConversationListTitle(total)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SoftButton(text = "Обновить", onClick = onRefresh, enabled = !isLoading, leadingIcon = ErpIconGlyph.Refresh)
                Spacer(Modifier.width(8.dp))
                GradientButton(text = "Создать чат", onClick = onCreateClick, leadingIcon = ErpIconGlyph.Plus)
            }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConversationListTitle(total)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftButton(text = "Обновить", onClick = onRefresh, enabled = !isLoading, leadingIcon = ErpIconGlyph.Refresh)
            Spacer(Modifier.width(10.dp))
            GradientButton(text = "Новый чат", onClick = onCreateClick, leadingIcon = ErpIconGlyph.Plus)
        }
    }
}

@Composable
private fun ConversationListTitle(total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(ErpIconGlyph.Send, gradient = Gradients.Ocean, size = 40.dp, iconSize = 20.dp)
        Spacer(Modifier.width(13.dp))
        Column {
            Text("Чат", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(
                text = "Личные, групповые, проектные и задачные · $total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConversationWorkspacePlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        GlassCard(modifier = Modifier.width(420.dp)) {
            EmptyState(
                title = "Выберите обсуждение",
                text = "Список чатов остаётся слева: можно быстро переключаться, не теряя контекст.",
                glyph = ErpIconGlyph.Send,
                accent = Brand.Blue
            )
        }
    }
}
