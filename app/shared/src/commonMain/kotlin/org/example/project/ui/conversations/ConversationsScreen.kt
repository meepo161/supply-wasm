package org.example.project.ui.conversations

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import org.example.project.ui.theme.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
import org.example.project.ui.tasks.TaskCreateDialog
import org.example.project.ui.tasks.TaskCreateForm
import org.example.project.ui.tasks.TaskViewModel
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpIcon
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
    onOpenTask: (Long) -> Unit,
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
    var createTaskFromChatOpen by remember { mutableStateOf(false) }
    val openTaskFromMessage: (String) -> Unit = { messageText ->
        val clean = messageText.trim()
        taskViewModel.updateCreateForm(
            TaskCreateForm(
                title = clean.lineSequence().firstOrNull()?.take(100)?.trim().orEmpty(),
                description = clean
            )
        )
        createTaskFromChatOpen = true
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
            val prefs by org.example.project.ui.main.UserPreferencesStore.state.collectAsState()
            LaunchedEffect(state.items, state.selectedConversation, state.isLoading, prefs.autoOpenFirstChat) {
                if (prefs.autoOpenFirstChat && !state.isLoading && state.selectedConversation == null && state.items.isNotEmpty()) {
                    val preferred = state.items.firstOrNull { !it.lastMessageAt.isNullOrBlank() || it.unreadCount > 0 }
                        ?: state.items.first()
                    openConversation(preferred)
                }
            }
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
                    onOpenSaved = viewModel::openSavedMessages,
                    onChangePresence = viewModel::setMyPresence,
                    compact = true,
                    modifier = Modifier.width(400.dp).fillMaxHeight()
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
                            conversationState = state,
                            conversationViewModel = viewModel,
                            showChatList = false,
                            unifiedForwardTargets = state.items,
                            onOpenFullCard = { onOpenTask(taskState.selectedTask!!.id) },
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
                        typingUsers = state.typingUsers,
                        userOptions = state.userOptions,
                        currentUserId = currentUser.id,
                        forwardTargets = state.items,
                        viewModel = viewModel,
                        showBackButton = false,
                        onBack = viewModel::backToList,
                        onDraftChange = viewModel::updateDraft,
                        onSend = viewModel::sendMessage,
                        onRefresh = viewModel::refreshMessages,
                        onLoadMore = viewModel::loadMoreNewer,
                        onPickFile = viewModel::pickFile,
                        onClearFile = viewModel::clearPendingFile,
                        onDownloadAttachment = viewModel::downloadAttachment,
                        onOpenTask = onOpenTask,
                        onCreateTaskFromMessage = openTaskFromMessage,
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
                    conversationState = state,
                    conversationViewModel = viewModel,
                    showChatList = false,
                    unifiedForwardTargets = state.items,
                    onOpenFullCard = { onOpenTask(taskState.selectedTask!!.id) },
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
                typingUsers = state.typingUsers,
                userOptions = state.userOptions,
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
                onOpenTask = onOpenTask,
                onCreateTaskFromMessage = openTaskFromMessage,
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
                onOpenSaved = viewModel::openSavedMessages,
                onChangePresence = viewModel::setMyPresence,
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
            onCreateChannel = viewModel::createChannel,
            onRetryDirectory = viewModel::loadUserOptions,
            onDismiss = { showCreateDialog = false }
        )
    }

    state.activeThreadRoot?.let { root ->
        ThreadDrawerDialog(
            rootMessage = root,
            threadMessages = state.threadMessages,
            isLoading = state.threadLoading,
            canReply = state.selectedConversation != null,
            onSendReply = viewModel::sendThreadReply,
            onDismiss = viewModel::closeThread
        )
    }

    if (state.aiSummaryLoading || state.aiSummary != null) {
        ConversationAiSummaryDialog(
            summary = state.aiSummary,
            isLoading = state.aiSummaryLoading,
            onCreateTaskFromAction = openTaskFromMessage,
            onDismiss = viewModel::clearAiSummary
        )
    }

    if (createTaskFromChatOpen) {
        TaskCreateDialog(
            form = taskState.createForm,
            users = taskState.users,
            projects = taskState.projects,
            currentUser = currentUser,
            error = taskState.createError,
            onFormChange = taskViewModel::updateCreateForm,
            onCreate = {
                taskViewModel.createTask(currentUserId = currentUser.id)
                createTaskFromChatOpen = false
            },
            onDismiss = { createTaskFromChatOpen = false }
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
    onOpenSaved: () -> Unit = {},
    onChangePresence: (org.example.project.dto.UserPresenceStatus) -> Unit = {},
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
            .padding(horizontal = if (compact) 12.dp else 24.dp, vertical = if (compact) 12.dp else 0.dp)
    ) {
        ConversationListHeader(state.items.size, state.isLoading, onRefresh, onCreate, compact)
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SoftButton(
                text = "Избранное",
                onClick = onOpenSaved,
                leadingIcon = ErpIconGlyph.Star
            )
            var presenceMenu by remember { mutableStateOf(false) }
            Box {
                SoftButton(
                    text = state.myPresence.localizedLabel(),
                    onClick = { presenceMenu = true },
                    tint = state.myPresence.dotColor()
                )
                DropdownMenu(expanded = presenceMenu, onDismissRequest = { presenceMenu = false }) {
                    listOf(
                        org.example.project.dto.UserPresenceStatus.ONLINE,
                        org.example.project.dto.UserPresenceStatus.AWAY,
                        org.example.project.dto.UserPresenceStatus.DND
                    ).forEach { st ->
                        DropdownMenuItem(
                            text = { Text(st.localizedLabel()) },
                            onClick = {
                                presenceMenu = false
                                onChangePresence(st)
                            }
                        )
                    }
                }
            }
        }
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
                verticalArrangement = Arrangement.spacedBy(6.dp)
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
                    val itemKey = conversationKey(item.kind, item.id)
                    val isSelected = item.kind == state.selectedConversation?.kind &&
                        item.toConversationDto().id == state.selectedConversation.id
                    ConversationRow(
                        item = item,
                        currentUserId = currentUserId,
                        selected = isSelected,
                        draftPreview = if (!isSelected) state.drafts[itemKey]?.takeIf { it.isNotBlank() } else null,
                        presenceStatus = if (
                            org.example.project.ui.main.UserPreferencesStore.state.collectAsState().value.showOnlinePresence &&
                            item.kind == ConversationKind.DIRECT &&
                            !item.isSavedMessages
                        ) {
                            val otherId = item.members.firstOrNull { it.userId != currentUserId }?.userId
                            otherId?.let { state.presenceByUser[it]?.status }
                        } else null,
                        actionInProgress = itemKey in state.pendingDialogActions,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConversationFilters(
    selectedFilter: ConversationListFilter,
    selectedKind: ConversationKind?,
    onFilterChange: (ConversationListFilter) -> Unit,
    onKindChange: (ConversationKind?) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ConversationListFilter.entries.forEach { filter ->
            ConversationFilterChip(
                text = filter.localizedName(),
                selected = selectedFilter == filter,
                onClick = { onFilterChange(filter) }
            )
        }
    }
    ConversationFilterRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        ConversationKind.entries.forEach { kind ->
            ConversationFilterChip(
                text = kind.localizedName(),
                selected = selectedKind == kind,
                onClick = { onKindChange(if (selectedKind == kind) null else kind) }
            )
        }
    }
}

@Composable
private fun ConversationFilterRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .horizontalMouseWheel(scrollState),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
        if (scrollState.maxValue > 0) {
            ConversationHorizontalScrollbar(scrollState)
        }
    }
}

@Composable
private fun Modifier.horizontalMouseWheel(scrollState: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    return pointerInput(scrollState) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val delta = event.changes.firstOrNull()?.scrollDelta ?: continue
                val distance = if (delta.x != 0f) delta.x else delta.y
                if (distance == 0f) continue
                val canScroll = if (distance > 0) scrollState.canScrollForward else scrollState.canScrollBackward
                if (canScroll) {
                    scope.launch { scrollState.scrollBy(distance * 48f) }
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}

@Composable
private fun ConversationHorizontalScrollbar(scrollState: ScrollState) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp)
    ) {
        val viewportWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val viewportFraction = (viewportWidthPx / (viewportWidthPx + scrollState.maxValue.toFloat())).coerceIn(0.1f, 1f)
        val thumbWidth = maxWidth * viewportFraction
        val scrollFraction = (scrollState.value.toFloat() / scrollState.maxValue.toFloat()).coerceIn(0f, 1f)

        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), CircleShape)
        )
        Box(
            Modifier
                .offset(x = (maxWidth - thumbWidth) * scrollFraction)
                .width(thumbWidth)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
        )
    }
}

@Composable
private fun ConversationFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val containerColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = if (hovered) 0.18f else 0.12f)
            hovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
        },
        animationSpec = tween(160),
        label = "conversationFilterContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
            hovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        },
        animationSpec = tween(160),
        label = "conversationFilterBorder"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected || hovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(160),
        label = "conversationFilterContent"
    )

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .border(1.dp, borderColor, CircleShape)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

@Composable
private fun ConversationRow(
    item: ConversationListItemDto,
    currentUserId: Long,
    selected: Boolean,
    draftPreview: String? = null,
    presenceStatus: org.example.project.dto.UserPresenceStatus? = null,
    actionInProgress: Boolean,
    onClick: () -> Unit,
    onDialogState: (ConversationListItemDto, Boolean?, Boolean?, Boolean?) -> Unit
) {
    val conversation = item.toConversationDto()
    val title = conversation.displayTitle(currentUserId)
    var actionsOpen by remember(item.kind, item.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Открыть чат $title, ${item.kind.localizedName()}" },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Avatar(name = if (item.isSavedMessages) "★" else title, size = 32.dp)
                    if (presenceStatus != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(10.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(presenceStatus.dotColor())
                                .border(1.5.dp, MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.CircleShape)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = conversationTimeLabel(item.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Box {
                    IconButton(
                        onClick = { actionsOpen = true },
                        enabled = !actionInProgress,
                        modifier = Modifier.size(28.dp)
                    ) {
                        ErpIcon(
                            glyph = ErpIconGlyph.MoreHoriz,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (item.isPinned) "Открепить" else "Закрепить") },
                            onClick = {
                                actionsOpen = false
                                onDialogState(item, !item.isPinned, null, null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (item.isMuted) "Включить звук" else "Без звука") },
                            onClick = {
                                actionsOpen = false
                                onDialogState(item, null, !item.isMuted, null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (item.isArchived) "Из архива" else "Архив") },
                            onClick = {
                                actionsOpen = false
                                onDialogState(item, null, null, !item.isArchived)
                            }
                        )
                    }
                }
            }
            Text(
                text = draftPreview?.let { "Черновик: ${it.replace('\n', ' ')}" } ?: item.previewText(currentUserId),
                style = MaterialTheme.typography.bodySmall,
                color = if (draftPreview != null) Brand.Crimson else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 40.dp, top = 2.dp)
            )
            if (item.mentionCount > 0 || item.unreadCount > 0 || actionInProgress) {
                Row(
                    modifier = Modifier.padding(start = 40.dp, top = 3.dp),
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
    typingUsers: List<String> = emptyList(),
    userOptions: List<org.example.project.dto.UserOptionDto> = emptyList(),
    currentUserId: Long,
    forwardTargets: List<ConversationListItemDto>,
    viewModel: ConversationViewModel,
    showBackButton: Boolean = true,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPickFile: () -> Unit,
    onClearFile: () -> Unit,
    onDownloadAttachment: (AttachmentDto) -> Unit,
    onOpenTask: (Long) -> Unit,
    onCreateTaskFromMessage: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var infoOpen by remember(conversation.kind, conversation.id) { mutableStateOf(false) }
    val currentItem = forwardTargets.firstOrNull {
        it.kind == conversation.kind && it.toConversationDto().id == conversation.id
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        ConversationPanel(
            messages = messages,
            canWrite = conversation.canWrite,
            currentUserId = currentUserId,
            readOnlyHint = if (conversation.kind == ConversationKind.CHANNEL) {
                "Публиковать в канал могут только администраторы. Вы можете комментировать посты в ветках."
            } else {
                "Обсуждение доступно только для чтения."
            },
            onDraftChange = onDraftChange,
            onSend = onSend,
            onRefresh = onRefresh,
            onLoadMore = onLoadMore,
            onPickFile = onPickFile,
            onClearFile = onClearFile,
            onDownloadAttachment = onDownloadAttachment,
            onOpenTask = onOpenTask,
            modifier = Modifier.weight(1f),
            conversationKey = "${conversation.kind}:${conversation.id}",
            participants = conversation.members,
            forwardTargets = forwardTargets,
            typingUsers = typingUsers,
            viewModel = viewModel,
            pinnedMessages = conversation.pinnedMessages,
            onCreateTaskFromMessage = onCreateTaskFromMessage,
            headerContent = {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showBackButton) TextButton(onClick = onBack) { Text("К списку") }
                    Avatar(name = if (conversation.isSavedMessages) "★" else conversation.displayTitle(currentUserId), size = 34.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (conversation.isSavedMessages) "Избранное" else conversation.displayTitle(currentUserId),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${conversation.kind.localizedName()} · Участников: ${conversation.members.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!conversation.canWrite) {
                        PillBadge(text = "Только чтение", accent = MaterialTheme.colorScheme.outline)
                    }
                    conversation.taskId?.let { taskId ->
                        SoftButton(
                            text = "Открыть задачу",
                            onClick = { onOpenTask(taskId) }
                        )
                    }
                    if (conversation.id > 0) {
                        SoftButton(
                            text = "ИИ-итог",
                            onClick = viewModel::summarizeActiveConversation,
                            leadingIcon = ErpIconGlyph.Sparkle
                        )
                    }
                    SoftButton(
                        text = "Сведения",
                        onClick = {
                            viewModel.loadUserOptions()
                            infoOpen = true
                        }
                    )
                }
            }
        )
    }
    if (infoOpen) {
        ConversationInfoDialog(
            conversation = conversation,
            item = currentItem,
            messages = messages,
            userOptions = userOptions,
            currentUserId = currentUserId,
            onDownloadAttachment = onDownloadAttachment,
            onTogglePinned = { pinned -> currentItem?.let { viewModel.changeDialogState(it, pinned, null, null) } },
            onToggleMuted = { muted -> currentItem?.let { viewModel.changeDialogState(it, null, muted, null) } },
            onToggleArchived = { archived -> currentItem?.let { viewModel.changeDialogState(it, null, null, archived) } },
            onUpdateTitle = { newTitle -> viewModel.updateGroupInfo(newTitle, null) },
            onAddMember = { uid -> viewModel.updateGroupMembers(listOf(uid), emptyList()) },
            onRemoveMember = { uid -> viewModel.updateGroupMembers(emptyList(), listOf(uid)) },
            onLeaveGroup = {
                infoOpen = false
                viewModel.leaveActiveGroup()
            },
            onDismiss = { infoOpen = false }
        )
    }
}

@Composable
private fun ConversationInfoDialog(
    conversation: ConversationDto,
    item: ConversationListItemDto?,
    messages: ConversationMessagesUiState,
    userOptions: List<org.example.project.dto.UserOptionDto>,
    currentUserId: Long,
    onDownloadAttachment: (AttachmentDto) -> Unit,
    onTogglePinned: (Boolean) -> Unit,
    onToggleMuted: (Boolean) -> Unit,
    onToggleArchived: (Boolean) -> Unit,
    onUpdateTitle: (String) -> Unit,
    onAddMember: (Long) -> Unit,
    onRemoveMember: (Long) -> Unit,
    onLeaveGroup: () -> Unit,
    onDismiss: () -> Unit
) {
    val sharedAttachments = remember(messages.messages) {
        messages.messages.flatMap { it.attachments }.distinctBy { it.id }
    }
    val canManageGroup = conversation.kind == ConversationKind.GROUP || conversation.kind == ConversationKind.CHANNEL
    var editedTitle by remember(conversation.id) { mutableStateOf(conversation.title.orEmpty()) }
    var addMenuOpen by remember { mutableStateOf(false) }
    val memberIds = conversation.members.map { it.userId }.toSet()
    val addableUsers = userOptions.filter { it.id !in memberIds }
    org.example.project.ui.theme.ErpDialog(
        title = conversation.displayTitle(currentUserId),
        subtitle = "${conversation.kind.localizedName()} · Участников: ${conversation.members.size}",
        glyph = ErpIconGlyph.User,
        gradient = Gradients.Ocean,
        width = 580.dp,
        onDismiss = onDismiss
    ) {
        if (item != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SoftButton(
                    text = if (item.isPinned) "Открепить" else "Закрепить",
                    onClick = { onTogglePinned(!item.isPinned) },
                    modifier = Modifier.weight(1f)
                )
                SoftButton(
                    text = if (item.isMuted) "Со звуком" else "Без звука",
                    onClick = { onToggleMuted(!item.isMuted) },
                    modifier = Modifier.weight(1f)
                )
                SoftButton(
                    text = if (item.isArchived) "Из архива" else "В архив",
                    onClick = { onToggleArchived(!item.isArchived) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (canManageGroup) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = editedTitle,
                    onValueChange = { editedTitle = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                SoftButton(
                    text = "Сохранить",
                    onClick = { if (editedTitle.isNotBlank()) onUpdateTitle(editedTitle.trim()) },
                    enabled = editedTitle.isNotBlank() && editedTitle.trim() != conversation.title
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Участники (${conversation.members.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (canManageGroup && addableUsers.isNotEmpty()) {
                Box {
                    SoftButton(text = "+ Участник", onClick = { addMenuOpen = true })
                    DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                        addableUsers.take(15).forEach { u ->
                            DropdownMenuItem(
                                text = { Text(u.fullName) },
                                onClick = {
                                    addMenuOpen = false
                                    onAddMember(u.id)
                                }
                            )
                        }
                    }
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            conversation.members.forEach { member ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Avatar(name = member.fullName, size = 28.dp)
                    Text(
                        text = member.fullName + if (member.userId == currentUserId) " (Вы)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (canManageGroup && member.userId != currentUserId) {
                        TextButton(onClick = { onRemoveMember(member.userId) }) {
                            Text("Удалить", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        if (canManageGroup) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                SoftButton(
                    text = "Покинуть группу",
                    onClick = onLeaveGroup,
                    tint = Brand.Rose
                )
            }
        }
        Text(
            text = "Файлы в загруженной переписке (${sharedAttachments.size})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (sharedAttachments.isEmpty()) {
            Text(
                text = "В загруженных сообщениях пока нет файлов.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                sharedAttachments.forEach { attachment ->
                    AttachmentRow(
                        attachment = attachment,
                        isDownloading = messages.downloadingAttachmentId == attachment.id,
                        onDownload = { onDownloadAttachment(attachment) }
                    )
                }
            }
        }
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConversationListTitle(total, compact = compact, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        GradientButton(
            text = if (compact) "Создать" else "Новый чат",
            onClick = onCreateClick,
            leadingIcon = ErpIconGlyph.Plus
        )
    }
}

@Composable
private fun ConversationListTitle(
    total: Int,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconBadge(
            ErpIconGlyph.Send,
            gradient = Gradients.Ocean,
            size = if (compact) 34.dp else 40.dp,
            iconSize = if (compact) 18.dp else 20.dp
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Чат", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(
                text = if (compact) "Всего чатов: $total" else "Личные, групповые, проектные и задачные · $total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
