package org.example.project.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import org.example.project.dto.AttachmentEntityType
import org.example.project.dto.ProjectDto
import org.example.project.dto.ProjectStatus
import org.example.project.dto.TaskDto
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission
import org.example.project.ui.attachments.AttachmentsSection
import org.example.project.ui.conversations.ConversationPanel
import org.example.project.ui.conversations.ConversationViewModel
import org.example.project.ui.tasks.TaskCard
import org.example.project.ui.tasks.filterMine
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.SkeletonLine

@Composable
fun ProjectDetailScreen(
    project: ProjectDto,
    currentUser: org.example.project.dto.UserDto,
    users: List<UserDto>,
    detailLoading: Boolean,
    openError: org.example.project.dto.ApiError?,
    closeError: org.example.project.dto.ApiError?,
    isClosing: Boolean,
    memberError: org.example.project.dto.ApiError?,
    openForm: OpenProjectForm,
    activeTab: ProjectDetailTab,
    projectTasks: List<TaskDto>,
    projectTasksLoading: Boolean,
    projectTasksError: org.example.project.dto.ApiError?,
    onTabChange: (ProjectDetailTab) -> Unit,
    onRefreshTasks: () -> Unit,
    onOpenTask: (TaskDto) -> Unit,
    conversationViewModel: ConversationViewModel,
    onOpenReasonChange: (String) -> Unit,
    onOpenProject: () -> Unit,
    onCloseProject: (String) -> Unit,
    onAddMember: (Long) -> Unit,
    onRemoveMember: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOwner = project.ownerId == currentUser.id
    val canManageMembers = isOwner
    val canClose = project.status in setOf(
        ProjectStatus.PLANNED,
        ProjectStatus.IN_PROGRESS,
        ProjectStatus.ON_HOLD,
        ProjectStatus.COMPLETED
    ) && (isOwner || currentUser.hasPermission("projects", "close_any")) &&
        currentUser.hasPermission("projects", "close")

    var closeRequested by remember(project.id) { mutableStateOf(false) }
    var closeReason by remember(project.id) { mutableStateOf("") }

    LaunchedEffect(project.status, project.version) {
        if (project.status == ProjectStatus.CLOSED) closeRequested = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Назад к списку")
            }
            Text(
                text = project.number,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (detailLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        PrimaryTabRow(selectedTabIndex = activeTab.ordinal) {
            ProjectDetailTab.entries.forEach { tab ->
                Tab(
                    selected = activeTab == tab,
                    onClick = { onTabChange(tab) },
                    text = { Text(tab.label) }
                )
            }
        }

        when (activeTab) {
            ProjectDetailTab.OVERVIEW -> ProjectOverviewTab(
                project = project,
                currentUser = currentUser,
                users = users,
                canManageMembers = canManageMembers,
                canClose = canClose,
                isOwner = isOwner,
                isClosing = isClosing,
                openError = openError,
                memberError = memberError,
                openForm = openForm,
                onCloseRequest = { closeRequested = true },
                onOpenReasonChange = onOpenReasonChange,
                onOpenProject = onOpenProject,
                onAddMember = onAddMember,
                onRemoveMember = onRemoveMember,
                modifier = Modifier.weight(1f)
            )
            ProjectDetailTab.TASKS -> ProjectTasksTab(
                tasks = projectTasks,
                users = users,
                currentUserId = currentUser.id,
                isLoading = projectTasksLoading,
                error = projectTasksError,
                onRefresh = onRefreshTasks,
                onOpenTask = onOpenTask,
                modifier = Modifier.weight(1f)
            )
            ProjectDetailTab.DISCUSSION -> {
                val conversationState by conversationViewModel.uiState.collectAsState()
                val chat = conversationState.selectedConversation
                ConversationPanel(
                    messages = conversationState.messages,
                    canWrite = chat?.canWrite ?: true,
                    readOnlyHint = "Обсуждение доступно только для чтения.",
                    onDraftChange = conversationViewModel::updateDraft,
                    onSend = conversationViewModel::sendMessage,
                    onRefresh = { conversationViewModel.refreshMessages() },
                    onLoadMore = conversationViewModel::loadMoreNewer,
                    onPickFile = conversationViewModel::pickFile,
                    onClearFile = conversationViewModel::clearPendingFile,
                    onDownloadAttachment = conversationViewModel::downloadAttachment,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (closeRequested) {
        AlertDialog(
            onDismissRequest = { if (!isClosing) closeRequested = false },
            title = { Text("Завершение проекта") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Связанные незавершённые процессы будут проверены сервером.")
                    OutlinedTextField(
                        value = closeReason,
                        onValueChange = { closeReason = it },
                        label = { Text("Комментарий") },
                        enabled = !isClosing,
                        modifier = Modifier.fillMaxWidth()
                    )
                    closeError?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onCloseProject(closeReason) },
                    enabled = !isClosing
                ) {
                    Text("Подтвердить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { closeRequested = false },
                    enabled = !isClosing
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun ProjectOverviewTab(
    project: ProjectDto,
    currentUser: UserDto,
    users: List<UserDto>,
    canManageMembers: Boolean,
    canClose: Boolean,
    isOwner: Boolean,
    isClosing: Boolean,
    openError: org.example.project.dto.ApiError?,
    memberError: org.example.project.dto.ApiError?,
    openForm: OpenProjectForm,
    onCloseRequest: () -> Unit,
    onOpenReasonChange: (String) -> Unit,
    onOpenProject: () -> Unit,
    onAddMember: (Long) -> Unit,
    onRemoveMember: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ProjectHeader(project = project)

        ProjectInfoSection(project = project)

        if (canClose) {
            OutlinedButton(
                onClick = onCloseRequest,
                enabled = !isClosing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isClosing) "Завершение…" else "Завершить проект")
            }
        }

        if (project.status == ProjectStatus.DRAFT && isOwner) {
            OpenProjectSection(
                openForm = openForm,
                error = openError,
                onReasonChange = onOpenReasonChange,
                onOpen = onOpenProject
            )
        }

        MembersSection(
            project = project,
            users = users,
            canManageMembers = canManageMembers,
            error = memberError,
            onAddMember = onAddMember,
            onRemoveMember = onRemoveMember
        )

        AttachmentsSection(
            entityType = AttachmentEntityType.PROJECT,
            entityId = project.id,
            canWrite = isOwner || currentUser.hasPermission("projects", "manage_members"),
            readOnly = false
        )
    }
}

@Composable
private fun ProjectTasksTab(
    tasks: List<TaskDto>,
    users: List<UserDto>,
    currentUserId: Long,
    isLoading: Boolean,
    error: org.example.project.dto.ApiError?,
    onRefresh: () -> Unit,
    onOpenTask: (TaskDto) -> Unit,
    modifier: Modifier = Modifier
) {
    var mineOnly by remember(currentUserId) { mutableStateOf(false) }
    val visibleTasks = if (mineOnly) tasks.filterMine(currentUserId) else tasks

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Задачи проекта · ${visibleTasks.size}",
                style = MaterialTheme.typography.titleMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = mineOnly,
                    onClick = { mineOnly = !mineOnly },
                    label = { Text("Мои") }
                )
                TextButton(onClick = onRefresh, enabled = !isLoading) {
                    Text("Обновить")
                }
            }
        }

        error?.let {
            ErrorMessageCard(error = it)
        }

        when {
            isLoading && tasks.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(4) { SkeletonLine(height = 74.dp) }
            }
            visibleTasks.isEmpty() -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    title = if (tasks.isEmpty()) "В проекте пока нет задач" else "Среди задач проекта ваших нет",
                    text = if (tasks.isEmpty()) "Задачи, привязанные к проекту, появятся здесь"
                    else "Отключите фильтр «Мои», чтобы увидеть все задачи проекта",
                    glyph = ErpIconGlyph.Tasks,
                    accent = Brand.Blue
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(visibleTasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        users = users,
                        currentUserId = currentUserId,
                        onClick = { onOpenTask(task) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectHeader(project: ProjectDto, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
                overflow = TextOverflow.Ellipsis,
                maxLines = 2
            )
            StatusChip(status = project.status)
        }
        project.fullName?.takeIf { it.isNotBlank() && it != project.name }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ProjectInfoSection(project: ProjectDto, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DetailRow(label = "Статус", value = project.status.localizedName())
            DetailRow(label = "Тип", value = project.projectType?.localizedName() ?: "—")
            DetailRow(label = "Приоритет", value = project.priority.localizedName())
            DetailRow(label = "Руководитель", value = ownerName(project))
            DetailRow(label = "Заказчик", value = project.customer?.takeIf { it.isNotBlank() } ?: "—")
            DetailRow(label = "Версия", value = project.version.toString())
            DetailRow(
                label = "Плановое начало",
                value = project.plannedStartAt.toShortDate()
            )
            DetailRow(
                label = "Плановое окончание",
                value = project.plannedEndAt.toShortDate()
            )
            project.openingReason?.takeIf { it.isNotBlank() }?.let {
                DetailRow(label = "Причина открытия", value = it)
            }
            project.openedAt?.let {
                DetailRow(label = "Открыт", value = it.toShortDate())
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun OpenProjectSection(
    openForm: OpenProjectForm,
    error: org.example.project.dto.ApiError?,
    onReasonChange: (String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Открытие проекта",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "Проект в статусе «Черновик». Укажите причину открытия.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            error?.let {
                ErrorMessageCard(error = it)
            }

            OutlinedTextField(
                value = openForm.openingReason,
                onValueChange = onReasonChange,
                label = { Text("Причина открытия *") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть проект")
            }
        }
    }
}

@Composable
private fun MembersSection(
    project: ProjectDto,
    users: List<UserDto>,
    canManageMembers: Boolean,
    error: org.example.project.dto.ApiError?,
    onAddMember: (Long) -> Unit,
    onRemoveMember: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
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
                text = "Участники",
                style = MaterialTheme.typography.titleMedium
            )

            error?.let {
                ErrorMessageCard(error = it)
            }

            if (project.members.isEmpty()) {
                Text(
                    text = "Участников пока нет.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                project.members.forEach { member ->
                    MemberRow(
                        fullName = member.fullName,
                        isOwner = member.userId == project.ownerId,
                        canRemove = canManageMembers && member.userId != project.ownerId,
                        onRemove = { onRemoveMember(member.userId) }
                    )
                }
            }

            if (canManageMembers) {
                val candidates = users.filter { user ->
                    project.members.none { it.userId == user.id }
                }
                if (candidates.isNotEmpty()) {
                    HorizontalDivider()
                    AddMemberSelector(
                        candidates = candidates,
                        onAdd = onAddMember
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberRow(
    fullName: String,
    isOwner: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = fullName,
                style = MaterialTheme.typography.bodyLarge
            )
            if (isOwner) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "Руководитель",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        if (canRemove) {
            TextButton(onClick = onRemove) {
                Text("Удалить")
            }
        }
    }
}

@Composable
private fun AddMemberSelector(
    candidates: List<UserDto>,
    onAdd: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf<UserDto?>(candidates.firstOrNull()) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Добавить участника",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        UserDropdown(
            users = candidates,
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { selected?.let { onAdd(it.id) } },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Добавить")
        }
    }
}

@Composable
internal fun ownerName(project: ProjectDto): String {
    val owner = project.members.find { it.userId == project.ownerId }
    return owner?.fullName ?: "ID ${project.ownerId}"
}
