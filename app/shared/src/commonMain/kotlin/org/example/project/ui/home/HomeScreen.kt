package org.example.project.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.NotificationDto
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskPriority
import org.example.project.dto.TaskStatus
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.tasks.localizedName
import org.example.project.ui.tasks.toShortDate
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.ErpIconButton
import org.example.project.ui.theme.erpInteractive
import org.example.project.ui.theme.readableAccent
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.IconBadge
import org.example.project.ui.theme.LocalBrandPalette
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SectionHeader
import org.example.project.ui.theme.SkeletonLine
import org.example.project.ui.theme.SoftButton
import org.example.project.ui.theme.TextButton

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTask: (Long) -> Unit,
    onOpenConversation: (Long) -> Unit,
    onOpenProjectChat: (Long) -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenDemand: ((Long) -> Unit)? = null,
    onCreateDemand: (() -> Unit)? = null,
    onNavigateDestination: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var browserNotificationEnabled by remember {
        mutableStateOf(BrowserNotifications.isGranted())
    }

    val today = state.currentDate
    var companyScope by remember { mutableStateOf(false) }
    var hideNotificationBanner by remember { mutableStateOf(false) }
    val scopedTasks = if (companyScope) state.allTasks else state.allTasks.filter { it.assigneeId == viewModel.currentUser.id }
    val openAssigned = scopedTasks.filter { it.isOpen() }
    val overdue = openAssigned.filter { it.isOverdue(today) }.sortedBy { it.dueDate }
    val upcoming = openAssigned.filter { it.isUpcoming(today) }.sortedBy { it.dueDate }
    val onReview = state.allTasks.filter {
        (companyScope || it.reviewerId == viewModel.currentUser.id) && it.status == TaskStatus.ON_REVIEW
    }
    val companyOverdueCount = state.allTasks.count { it.isOpen() && it.isOverdue(today) }
    val unreadNotifications = state.notifications.filter { !it.isRead }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val twoColumns = maxWidth >= 1060.dp
        val scroll = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeroCard(
                userName = viewModel.currentUser.fullName,
                companyScope = companyScope,
                onToggleScope = { companyScope = !companyScope },
                companyOverdueCount = companyOverdueCount,
                openCount = openAssigned.size,
                overdueCount = overdue.size,
                upcomingCount = upcoming.size,
                reviewCount = onReview.size,
                unreadCount = unreadNotifications.size,
                isLoading = state.isLoadingTasks,
                onRefresh = viewModel::refresh,
                onOpenNotifications = onOpenNotifications
            )

            if (!hideNotificationBanner) {
                BrowserNotificationRow(
                    enabled = browserNotificationEnabled,
                    supported = BrowserNotifications.isSupported(),
                    onEnable = {
                        viewModel.requestBrowserNotificationPermission { granted ->
                            browserNotificationEnabled = granted
                        }
                    },
                    onDismiss = { hideNotificationBanner = true }
                )
            }

            state.operationError?.let { ErrorMessageCard(error = it) }

            val assistantBlock: @Composable () -> Unit = {
                AssistantHomeCard(
                    available = state.assistantAvailable,
                    model = state.assistantModel,
                    statusReason = state.assistantStatusReason,
                    conversations = state.assistantConversations,
                    activeConversationId = state.assistantConversationId,
                    messages = state.assistantMessages,
                    pendingFiles = state.assistantPendingFiles,
                    input = state.assistantInput,
                    isLoading = state.assistantLoading,
                    error = state.assistantError,
                    onInputChange = viewModel::updateAssistantInput,
                    onPickFile = viewModel::pickAssistantFile,
                    onRemovePendingFile = viewModel::removeAssistantPendingFile,
                    onSelectConversation = viewModel::selectAssistantConversation,
                    onDeleteConversation = viewModel::deleteAssistantConversation,
                    onSend = viewModel::sendAssistantMessage,
                    onConfirmDraft = viewModel::confirmAssistantDraft,
                    onDismissDraft = viewModel::dismissAssistantDraft,
                    onNavigateDestination = onNavigateDestination,
                    onClear = viewModel::clearAssistantChat
                )
            }

            val operationsColumn: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (state.actionQueue.isNotEmpty()) {
                        ActionQueueSection(
                            items = state.actionQueue,
                            onNavigate = onNavigateDestination
                        )
                    }
                    if (state.activeDemands.isNotEmpty() || onCreateDemand != null) {
                        ActiveDemandsSection(
                            demands = state.activeDemands,
                            todayIso = today,
                            onOpenDemand = { id -> onOpenDemand?.invoke(id) },
                            onOpenAllDemands = { onNavigateDestination("DEMANDS") },
                            onCreateDemand = onCreateDemand
                        )
                    }
                    TasksAndEventsCard(
                        companyScope = companyScope,
                        openTasks = openAssigned,
                        overdueTasks = overdue,
                        upcomingTasks = upcoming,
                        reviewTasks = onReview,
                        notifications = unreadNotifications,
                        loadingTasks = state.isLoadingTasks,
                        loadingNotifications = state.isLoadingNotifications,
                        onTaskClick = { task -> viewModel.openTask(task.id, onOpenTask) },
                        onNotificationClick = { notification ->
                            viewModel.openNotificationEntity(
                                notification, onOpenTask, onOpenConversation, onOpenProjectChat
                            )
                        },
                        onOpenAllNotifications = onOpenNotifications
                    )
                }
            }

            if (twoColumns) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(modifier = Modifier.weight(1.25f)) {
                        assistantBlock()
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        operationsColumn()
                    }
                }
            } else {
                assistantBlock()
                operationsColumn()
            }
        }
    }
}

/* ---------------------------------- Очередь действий ----------------------------------- */

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ActionQueueSection(
    items: List<HomeActionQueueItem>,
    onNavigate: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconBadge(glyph = ErpIconGlyph.Sparkle, gradient = Gradients.Primary, size = 28.dp)
                Text(
                    text = "Что мне нужно сделать сейчас",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface
                )
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val itemModifier = if (maxWidth < 600.dp) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.widthIn(min = 210.dp, max = 320.dp)
                }
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items.forEach { item ->
                        val interaction = remember { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        val shape = RoundedCornerShape(8.dp)
                        Row(
                            modifier = itemModifier
                                .clip(shape)
                                .background(
                                    if (hovered) scheme.primaryContainer.copy(alpha = 0.45f)
                                    else scheme.surfaceContainerLow
                                )
                                .border(
                                    1.dp,
                                    if (hovered) scheme.primary else scheme.outlineVariant,
                                    shape
                                )
                                .erpInteractive(interaction, shape)
                                .clickable(interactionSource = interaction, indication = null) {
                                    onNavigate(item.destination)
                                }
                                .semantics {
                                    role = Role.Button
                                    contentDescription = actionQueueContentDescription(item)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PillBadge(text = item.count.toString(), accent = scheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = scheme.onSurface
                                )
                                Text(
                                    text = item.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ---------------------------------- Hero ----------------------------------- */

/**
 * Шапка главной: кто и в каком охвате смотрит, одна строка итога и действия.
 * Это заголовок страницы, а не баннер: фокус экрана остаётся на просрочках ниже.
 * Иерархия действий: «Уведомления» и переключатель охвата второстепенные (контур),
 * «Обновить» служебное (иконка).
 */
@Composable
private fun HeroCard(
    userName: String,
    companyScope: Boolean,
    onToggleScope: () -> Unit,
    companyOverdueCount: Int,
    openCount: Int,
    overdueCount: Int,
    upcomingCount: Int,
    reviewCount: Int,
    unreadCount: Int,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onOpenNotifications: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 680.dp
        val summary: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (companyScope) "Пульт управления · Компания" else "Пульт управления · $userName",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PillBadge(
                        text = "В работе: $openCount",
                        accent = Brand.Blue
                    )
                    if (overdueCount > 0) {
                        PillBadge(text = "Просрочено: $overdueCount", accent = Brand.Rose, filled = true)
                    } else {
                        PillBadge(text = "Просрочек нет", accent = Brand.Mint)
                    }
                    if (upcomingCount > 0) {
                        PillBadge(text = "Ближайшие: $upcomingCount", accent = Brand.Amber)
                    }
                    if (reviewCount > 0) {
                        PillBadge(text = "На приёмке: $reviewCount", accent = Brand.Teal)
                    }
                }
            }
        }
        val actions: @Composable () -> Unit = {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SoftButton(
                    text = if (unreadCount > 0) "Уведомления: $unreadCount" else "Уведомления",
                    onClick = onOpenNotifications,
                    leadingIcon = ErpIconGlyph.Bell,
                    compact = true
                )
                SoftButton(
                    text = if (companyScope) "Мои задачи" else "По компании",
                    onClick = onToggleScope,
                    compact = true
                )
            }
        }
        if (narrow) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                summary()
                actions()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) { summary() }
                Spacer(Modifier.width(16.dp))
                actions()
            }
        }
    }
}

private enum class HomeTasksTab(val label: String) {
    OPEN("В работе"),
    OVERDUE("Просрочено"),
    REVIEW("На приёмке"),
    NOTIFICATIONS("Уведомления")
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TasksAndEventsCard(
    companyScope: Boolean,
    openTasks: List<TaskDto>,
    overdueTasks: List<TaskDto>,
    upcomingTasks: List<TaskDto>,
    reviewTasks: List<TaskDto>,
    notifications: List<NotificationDto>,
    loadingTasks: Boolean,
    loadingNotifications: Boolean,
    onTaskClick: (TaskDto) -> Unit,
    onNotificationClick: (NotificationDto) -> Unit,
    onOpenAllNotifications: () -> Unit
) {
    var selectedTab by remember {
        mutableStateOf(
            when {
                overdueTasks.isNotEmpty() -> HomeTasksTab.OVERDUE
                openTasks.isNotEmpty() -> HomeTasksTab.OPEN
                notifications.isNotEmpty() -> HomeTasksTab.NOTIFICATIONS
                else -> HomeTasksTab.OPEN
            }
        )
    }
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        SectionHeader(
            title = if (companyScope) "Задачи компании и события" else "Мои задачи и события",
            accent = Brand.Blue,
            action = {
                SoftButton(
                    text = "Все уведомления",
                    onClick = onOpenAllNotifications,
                    leadingIcon = ErpIconGlyph.Bell,
                    compact = true
                )
            }
        )
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                HomeTasksTab.OPEN to openTasks.size,
                HomeTasksTab.OVERDUE to overdueTasks.size,
                HomeTasksTab.REVIEW to reviewTasks.size,
                HomeTasksTab.NOTIFICATIONS to notifications.size
            ).forEach { (tab, count) ->
                org.example.project.ui.theme.ErpFilterChip(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    label = { Text("${tab.label} ($count)") }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        when (selectedTab) {
            HomeTasksTab.OPEN, HomeTasksTab.OVERDUE, HomeTasksTab.REVIEW -> {
                val list = when (selectedTab) {
                    HomeTasksTab.OVERDUE -> overdueTasks
                    HomeTasksTab.REVIEW -> reviewTasks
                    else -> openTasks
                }
                val accent = when (selectedTab) {
                    HomeTasksTab.OVERDUE -> Brand.Rose
                    HomeTasksTab.REVIEW -> Brand.Teal
                    else -> Brand.Blue
                }
                when {
                    loadingTasks && list.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(2) { SkeletonLine(height = 42.dp) }
                    }
                    list.isEmpty() -> Text(
                        text = when (selectedTab) {
                            HomeTasksTab.OVERDUE -> "Просроченных задач нет."
                            HomeTasksTab.REVIEW -> "Задач на приёмке нет."
                            else -> "Открытых задач нет."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.take(6).forEach { task ->
                            TaskRow(task = task, accent = accent, onClick = { onTaskClick(task) })
                        }
                    }
                }
            }
            HomeTasksTab.NOTIFICATIONS -> {
                when {
                    loadingNotifications && notifications.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(2) { SkeletonLine(height = 42.dp) }
                    }
                    notifications.isEmpty() -> Text(
                        text = "Непрочитанных уведомлений нет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        notifications.take(6).forEach { n ->
                            NotificationRow(notification = n, onClick = { onNotificationClick(n) })
                        }
                    }
                }
            }
        }
    }
}

/* -------------------------------- Метрики ---------------------------------- */

private data class Metric(
    val label: String,
    val value: Int,
    val glyph: ErpIconGlyph,
    val gradient: List<Color>,
    val accent: Color
)

@Composable
private fun MetricRow(metrics: List<Metric>, loading: Boolean) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 720.dp
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                metrics.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { metric ->
                            MetricCard(metric, loading, Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                metrics.forEach { metric ->
                    MetricCard(metric, loading, Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Показатель: число крупно, подпись рядом с иконкой. Карточка не кликабельна
 * (своего перехода у показателя нет). Цвет включается, только когда показатель
 * требует внимания: ненулевая просрочка красная, остальные нейтральные.
 */
@Composable
private fun MetricCard(metric: Metric, loading: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val alarming = !loading && metric.value > 0 && metric.glyph == ErpIconGlyph.Alert
    val shape = RoundedCornerShape(8.dp)
    val alarmText = readableAccent(Brand.Rose)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (alarming) Brand.Rose.copy(alpha = 0.08f) else scheme.surface)
            .border(1.dp, if (alarming) Brand.Rose.copy(alpha = 0.45f) else scheme.outlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ErpIcon(
                metric.glyph,
                if (alarming) alarmText else scheme.onSurfaceVariant,
                Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (alarming) alarmText else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        if (loading) {
            SkeletonLine(height = 22.dp, widthFraction = 0.4f)
        } else {
            Text(
                text = metric.value.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = if (alarming) alarmText else scheme.onSurface
            )
        }
    }
}

/* ------------------------------ Браузерные события -------------------------- */

@Composable
private fun BrowserNotificationRow(
    enabled: Boolean,
    supported: Boolean,
    onEnable: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!supported || enabled) return
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 12.dp,
        gradient = null
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 560.dp
            val message: @Composable (Modifier) -> Unit = { mod ->
                Row(modifier = mod, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconBadge(ErpIconGlyph.Bell, gradient = Gradients.Sunset, size = 32.dp, iconSize = 16.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Браузерные уведомления отключены",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Включите, чтобы не пропустить новые события и задачи",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            val buttons: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton(
                        text = "Включить",
                        onClick = onEnable,
                        leadingIcon = ErpIconGlyph.Bell
                    )
                    TextButton(onClick = onDismiss) { Text("Скрыть") }
                }
            }
            // На узком экране кнопки уходят под текст, чтобы текст не сжимался в столбик.
            if (narrow) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    message(Modifier.fillMaxWidth())
                    buttons()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    message(Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    buttons()
                }
            }
        }
    }
}

/* ------------------------------- Секции задач ------------------------------- */

@Composable
private fun TaskSection(
    title: String,
    subtitle: String,
    tasks: List<TaskDto>,
    accent: Color,
    glyph: ErpIconGlyph,
    gradient: List<Color>,
    loading: Boolean,
    onTaskClick: (TaskDto) -> Unit,
    emptyText: String,
    limit: Int = 6
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 18.dp) {
        SectionHeader(
            title = title,
            count = tasks.size,
            accent = accent,
            subtitle = subtitle
        )
        Spacer(Modifier.height(14.dp))
        when {
            loading && tasks.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { SkeletonLine(height = 44.dp) }
            }
            tasks.isEmpty() -> EmptyState(
                title = emptyText,
                glyph = glyph,
                accent = accent
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tasks.take(limit).forEach { task ->
                    TaskRow(task = task, accent = accent, onClick = { onTaskClick(task) })
                }
                if (tasks.size > limit) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "и ещё ${tasks.size - limit}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskDto, accent: Color, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val palette = LocalBrandPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = tween(160),
        label = "taskRow"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) scheme.surfaceContainerLow else scheme.surface)
            .border(
                1.dp,
                if (hovered) scheme.primary.copy(alpha = 0.5f) else scheme.outlineVariant,
                RoundedCornerShape(8.dp)
            )
            .erpInteractive(interaction, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.number,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = task.status.localizedName(),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
                PriorityDot(task.priority)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (!task.dueDate.isNullOrBlank()) {
                Text(
                    text = task.dueDate.toShortDate(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
            }
            if (palette.isDark) {
                ErpIcon(
                    ErpIconGlyph.ChevronRight,
                    scheme.onSurfaceVariant,
                    Modifier.size(14.dp)
                )
            }
        }
    }
}

/** Приоритет показывается словом и только когда он выше обычного: цвет лишь дублирует текст. */
@Composable
private fun PriorityDot(priority: TaskPriority) {
    val (label, color) = when (priority) {
        TaskPriority.CRITICAL -> "критический" to Brand.Crimson
        TaskPriority.HIGH -> "высокий" to Brand.Orange
        else -> return
    }
    Spacer(Modifier.width(8.dp))
    PillBadge(text = label, accent = color)
}

/* ----------------------------- Уведомления --------------------------------- */

@Composable
private fun NotificationSection(
    notifications: List<NotificationDto>,
    loading: Boolean,
    onClick: (NotificationDto) -> Unit,
    onOpenAll: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 18.dp) {
        SectionHeader(
            title = "Непрочитанные уведомления",
            count = notifications.size,
            accent = Brand.Violet,
            subtitle = "События, требующие просмотра",
            action = {
                SoftButton(
                    text = "Все",
                    onClick = onOpenAll,
                    leadingIcon = ErpIconGlyph.Bell
                )
            }
        )
        Spacer(Modifier.height(14.dp))
        when {
            loading && notifications.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { SkeletonLine(height = 48.dp) }
            }
            notifications.isEmpty() -> EmptyState(
                title = "Новых уведомлений нет",
                text = "Мы сообщим, когда появится что-то важное",
                glyph = ErpIconGlyph.Bell,
                accent = Brand.Violet
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                notifications.take(5).forEach { notification ->
                    NotificationRow(notification, onClick = { onClick(notification) })
                }
                if (notifications.size > 5) {
                    Text(
                        text = "и ещё ${notifications.size - 5}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: NotificationDto, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = Brand.Violet
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.06f))
            .border(1.dp, accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(ErpIconGlyph.Bell, gradient = Gradients.Berry, size = 30.dp, iconSize = 15.dp)
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = notification.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${notification.entityType} · ${notification.createdAt.toShortDate()}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(accent)
        )
    }
}

private enum class HomeDemandQuickFilter(val label: String) {
    ALL("Все в работе"),
    URGENT("Срочные / просрочены"),
    NEED_ACTION("Ждут выбора / заказа")
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ActiveDemandsSection(
    demands: List<org.example.project.dto.DemandHeaderDto>,
    todayIso: String,
    onOpenDemand: (Long) -> Unit,
    onOpenAllDemands: () -> Unit,
    onCreateDemand: (() -> Unit)?
) {
    var quickFilter by remember { mutableStateOf(HomeDemandQuickFilter.ALL) }
    var showAll by remember { mutableStateOf(false) }

    val filtered = remember(demands, quickFilter, todayIso) {
        demands.filter { d ->
            when (quickFilter) {
                HomeDemandQuickFilter.ALL -> true
                HomeDemandQuickFilter.URGENT -> {
                    val overdue = d.requiredDate?.take(10)?.let { it < todayIso } == true
                    overdue || d.priority.name == "CRITICAL" || d.priority.name == "HIGH"
                }
                HomeDemandQuickFilter.NEED_ACTION -> {
                    !d.isPaid && (d.selectedSupplierName != null ||
                        d.status == org.example.project.dto.DemandStatus.PENDING_APPROVAL ||
                        d.status == org.example.project.dto.DemandStatus.APPROVED)
                }
            }
        }
    }
    val visible = if (showAll) filtered else filtered.take(6)

    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        SectionHeader(
            title = "Потребности в работе",
            count = demands.size,
            accent = Brand.Orange,
            subtitle = "Нажмите на заявку, чтобы сразу перейти к её обработке",
            action = {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (onCreateDemand != null) {
                        SoftButton(
                            text = "Новая потребность",
                            onClick = onCreateDemand,
                            tint = Brand.Orange,
                            leadingIcon = ErpIconGlyph.Plus
                        )
                    }
                    SoftButton(
                        text = "Все заявки →",
                        onClick = onOpenAllDemands,
                        tint = Brand.Blue
                    )
                }
            }
        )
        Spacer(Modifier.height(10.dp))

        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HomeDemandQuickFilter.entries.forEach { f ->
                org.example.project.ui.theme.ErpFilterChip(
                    selected = quickFilter == f,
                    onClick = { quickFilter = f },
                    label = { Text(f.label) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (filtered.isEmpty()) {
            Text(
                text = "По выбранному фильтру активных потребностей нет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visible.forEach { d ->
                    val isOverdue = d.requiredDate?.take(10)?.let { it < todayIso } == true
                    val accent = when {
                        isOverdue || d.priority.name == "CRITICAL" -> Brand.Rose
                        d.isPaid -> Brand.Mint
                        d.selectedSupplierName != null -> Brand.Amber
                        else -> Brand.Blue
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                            .clickable(onClick = { onOpenDemand(d.id) })
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = d.number,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Brand.Blue
                                )
                                Text(
                                    text = d.itemsSummary ?: "Состав не указан",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                d.quantitySummary?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                d.requiredDate?.let { req ->
                                    Text(
                                        text = "Срок: ${org.example.project.ui.theme.ErpFormat.dateRu(req)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isOverdue) Brand.Rose else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                d.selectedSupplierName?.let { sup ->
                                    val sum = d.selectedGrossTotal?.let { " · ${org.example.project.ui.theme.ErpFormat.money(it, "RUB")}" } ?: ""
                                    Text(
                                        text = "$sup$sum",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Brand.Teal
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isOverdue) {
                                PillBadge(text = "Просрочена", accent = Brand.Rose)
                            } else if (d.priority.name == "CRITICAL" || d.priority.name == "HIGH") {
                                PillBadge(
                                    text = if (d.priority.name == "CRITICAL") "Крит." else "Высокий",
                                    accent = if (d.priority.name == "CRITICAL") Brand.Rose else Brand.Amber
                                )
                            }
                            PillBadge(
                                text = when {
                                    d.isPaid -> "Оплачено ✓"
                                    d.selectedSupplierName != null -> "К оплате"
                                    d.status == org.example.project.dto.DemandStatus.PENDING_APPROVAL -> "На утверждении"
                                    else -> "В работе"
                                },
                                accent = accent,
                                filled = d.isPaid
                            )
                        }
                    }
                }
                if (filtered.size > 6) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "Свернуть список" else "Показать все (${filtered.size})")
                    }
                }
            }
        }
    }
}
