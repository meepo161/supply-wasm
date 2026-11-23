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
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.IconBadge
import org.example.project.ui.theme.LocalBrandPalette
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SectionHeader
import org.example.project.ui.theme.SkeletonLine
import org.example.project.ui.theme.SoftButton

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTask: (Long) -> Unit,
    onOpenConversation: (Long) -> Unit,
    onOpenProjectChat: (Long) -> Unit,
    onOpenNotifications: () -> Unit,
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
    var reverseBlockOrder by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val twoColumns = maxWidth >= 1180.dp
        val scroll = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            HeroCard(
                userName = viewModel.currentUser.fullName,
                companyScope = companyScope,
                onToggleScope = { companyScope = !companyScope },
                companyOverdueCount = companyOverdueCount,
                openCount = openAssigned.size,
                overdueCount = overdue.size,
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

            MetricRow(
                metrics = listOf(
                    Metric("Просрочено", overdue.size, ErpIconGlyph.Alert, Gradients.Sunset, Brand.Rose),
                    Metric("Ближайшие сроки", upcoming.size, ErpIconGlyph.Clock, Gradients.AmberGlow, Brand.Amber),
                    Metric("На приёмке", onReview.size, ErpIconGlyph.CheckCircle, Gradients.Aurora, Brand.Teal),
                    Metric("В работе", openAssigned.size, ErpIconGlyph.Tasks, Gradients.Primary, Brand.Blue)
                ),
                loading = state.isLoadingTasks
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                SoftButton(
                    text = if (reverseBlockOrder) "Стандартный порядок блоков" else "Поменять порядок блоков",
                    onClick = { reverseBlockOrder = !reverseBlockOrder }
                )
            }

            if (twoColumns) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (reverseBlockOrder) {
                        Column(
                            modifier = Modifier.weight(0.88f),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            TaskSection(
                                title = "Задачи на приёмке",
                                subtitle = "Ожидают вашего решения",
                                tasks = onReview,
                                accent = Brand.Teal,
                                glyph = ErpIconGlyph.CheckCircle,
                                gradient = Gradients.Aurora,
                                loading = state.isLoadingTasks,
                                onTaskClick = { task -> viewModel.openTask(task.id, onOpenTask) },
                                emptyText = "Нет задач, ожидающих вашей приёмки"
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1.12f),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        TaskSection(
                            title = "Просроченные задачи",
                            subtitle = "Требуют внимания в первую очередь",
                            tasks = overdue,
                            accent = Brand.Rose,
                            glyph = ErpIconGlyph.Alert,
                            gradient = Gradients.Sunset,
                            loading = state.isLoadingTasks,
                            onTaskClick = { task -> viewModel.openTask(task.id, onOpenTask) },
                            emptyText = "Просроченных задач нет — отличная работа."
                        )
                        TaskSection(
                            title = "Ближайшие сроки",
                            subtitle = "Держите фокус на этих задачах",
                            tasks = upcoming,
                            accent = Brand.Amber,
                            glyph = ErpIconGlyph.Clock,
                            gradient = Gradients.AmberGlow,
                            loading = state.isLoadingTasks,
                            onTaskClick = { task -> viewModel.openTask(task.id, onOpenTask) },
                            emptyText = "Ближайших сроков нет."
                        )
                    }
                    Column(
                        modifier = Modifier.weight(0.88f),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        TaskSection(
                            title = "Задачи на приёмке",
                            subtitle = "Ожидают вашего решения",
                            tasks = onReview,
                            accent = Brand.Teal,
                            glyph = ErpIconGlyph.CheckCircle,
                            gradient = Gradients.Aurora,
                            loading = state.isLoadingTasks,
                            onTaskClick = { task -> viewModel.openTask(task.id, onOpenTask) },
                            emptyText = "На вашей приёмке ничего нет."
                        )
                        NotificationSection(
                            notifications = unreadNotifications,
                            loading = state.isLoadingNotifications,
                            onClick = { notification ->
                                viewModel.openNotificationEntity(
                                    notification, onOpenTask, onOpenConversation, onOpenProjectChat
                                )
                            },
                            onOpenAll = onOpenNotifications
                        )
                    }
                }
            } else {
                TaskSection(
                    title = "Просроченные задачи",
                    subtitle = "Требуют внимания в первую очередь",
                    tasks = overdue,
                    accent = Brand.Rose,
                    glyph = ErpIconGlyph.Alert,
                    gradient = Gradients.Sunset,
                    loading = state.isLoadingTasks,
                    onTaskClick = { task -> viewModel.openTask(task.id, onOpenTask) },
                    emptyText = "Просроченных задач нет — отличная работа."
                )
                TaskSection(
                    title = "Ближайшие сроки",
                    subtitle = "Держите фокус на этих задачах",
                    tasks = upcoming,
                    accent = Brand.Amber,
                    glyph = ErpIconGlyph.Clock,
                    gradient = Gradients.AmberGlow,
                    loading = state.isLoadingTasks,
                    onTaskClick = { task -> viewModel.openTask(task.id, onOpenTask) },
                    emptyText = "Ближайших сроков нет."
                )
                TaskSection(
                    title = "Задачи на приёмке",
                    subtitle = "Ожидают вашего решения",
                    tasks = onReview,
                    accent = Brand.Teal,
                    glyph = ErpIconGlyph.CheckCircle,
                    gradient = Gradients.Aurora,
                    loading = state.isLoadingTasks,
                    onTaskClick = { task -> viewModel.openTask(task.id, onOpenTask) },
                    emptyText = "На вашей приёмке ничего нет."
                )
                NotificationSection(
                    notifications = unreadNotifications,
                    loading = state.isLoadingNotifications,
                    onClick = { notification ->
                        viewModel.openNotificationEntity(
                            notification, onOpenTask, onOpenConversation, onOpenProjectChat
                        )
                    },
                    onOpenAll = onOpenNotifications
                )
            }

            // Открытые задачи — компактным списком
            TaskSection(
                title = "Мои открытые задачи",
                subtitle = "Все активные назначения",
                tasks = openAssigned,
                accent = Brand.Blue,
                glyph = ErpIconGlyph.Tasks,
                gradient = Gradients.Primary,
                loading = state.isLoadingTasks,
                onTaskClick = { task -> viewModel.openTask(task.id, onOpenTask) },
                emptyText = "Назначенных открытых задач нет.",
                limit = 8
            )
        }
    }
}

/* ---------------------------------- Hero ----------------------------------- */

@Composable
private fun HeroCard(
    userName: String,
    companyScope: Boolean,
    onToggleScope: () -> Unit,
    companyOverdueCount: Int,
    openCount: Int,
    overdueCount: Int,
    reviewCount: Int,
    unreadCount: Int,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onOpenNotifications: () -> Unit
) {
    val palette = LocalBrandPalette.current
    val heroColors = if (palette.isDark) {
        listOf(Color(0xFF14295F), Color(0xFF2A1B63), Color(0xFF3E1A55))
    } else {
        listOf(Color(0xFF1247C0), Color(0xFF4B2DD6), Color(0xFF7C2FD6))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(heroColors))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(28.dp))
    ) {
        // Декоративные круги
        Box(
            modifier = Modifier
                .size(320.dp)
                .align(Alignment.TopEnd)
                .background(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.18f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(220.dp)
                .align(Alignment.BottomStart)
                .background(
                    Brush.radialGradient(
                        listOf(Brand.Cyan.copy(alpha = 0.22f), Color.Transparent)
                    )
                )
        )

        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$userName · ${if (companyScope) "по компании" else "ваши задачи"}",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when {
                            overdueCount > 0 -> "Просрочено: $overdueCount · открыто: $openCount"
                            openCount > 0 -> "Открытых задач: $openCount"
                            companyOverdueCount > 0 && !companyScope -> "У вас просрочек нет (по компании просрочено: $companyOverdueCount)"
                            else -> if (companyScope) "По компании просроченных задач нет" else "Ваших просроченных задач нет"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.82f)
                    )
                }
                Spacer(Modifier.width(12.dp))
                HeroBadge(unreadCount = unreadCount, onOpenNotifications = onOpenNotifications)
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GradientButton(
                    text = if (companyScope) "Показать мои" else "По компании",
                    onClick = onToggleScope,
                    compact = true,
                    gradient = listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.16f))
                )
                GradientButton(
                    text = "Обновить",
                    onClick = onRefresh,
                    enabled = !isLoading,
                    compact = true,
                    gradient = listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.12f)),
                    leadingIcon = ErpIconGlyph.Refresh
                )
                GlassChip(text = "Открытых: $openCount")
                GlassChip(text = "На приёмке: $reviewCount")
            }
        }
    }
}

@Composable
private fun HeroBadge(unreadCount: Int, onOpenNotifications: () -> Unit) {
    Column(horizontalAlignment = Alignment.End) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                .clickable(onClick = onOpenNotifications)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ErpIcon(ErpIconGlyph.Bell, Color.White, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "$unreadCount",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "непрочитанных",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f)
        )
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

@Composable
private fun MetricCard(metric: Metric, loading: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalBrandPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = tween(220),
        label = "metricLift"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        palette.glassOverlay,
                        metric.accent.copy(alpha = if (palette.isDark) 0.10f else 0.06f)
                    )
                )
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.5f + 0.3f * lift),
                        metric.accent.copy(alpha = 0.16f)
                    )
                ),
                RoundedCornerShape(20.dp)
            )
            .clickable(interactionSource = interaction, indication = null) {}
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBadge(metric.glyph, gradient = metric.gradient, size = 34.dp, iconSize = 17.dp)
                if (!loading && metric.value > 0) {
                    PillBadge(
                        text = "активно",
                        accent = metric.accent
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (loading) {
                SkeletonLine(height = 22.dp, widthFraction = 0.4f)
            } else {
                Text(
                    text = metric.value.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = metric.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                SoftButton(
                    text = "Включить",
                    onClick = onEnable,
                    leadingIcon = ErpIconGlyph.Bell
                )
                SoftButton(
                    text = "Скрыть",
                    onClick = onDismiss
                )
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
            subtitle = subtitle,
            action = {
                IconBadge(glyph, gradient = gradient, size = 30.dp, iconSize = 15.dp)
            }
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
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.05f + 0.07f * bg))
            .border(
                1.dp,
                accent.copy(alpha = 0.10f + 0.18f * bg),
                RoundedCornerShape(14.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.25f)))
                )
        )
        Spacer(Modifier.width(11.dp))
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

@Composable
private fun PriorityDot(priority: TaskPriority) {
    val color = when (priority) {
        TaskPriority.CRITICAL -> Brand.Crimson
        TaskPriority.HIGH -> Brand.Orange
        TaskPriority.NORMAL -> Brand.Blue
        TaskPriority.LOW -> Brand.Mint
    }
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
    )
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
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.06f))
            .border(1.dp, accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
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
