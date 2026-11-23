package org.example.project.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.NotificationDto
import org.example.project.ui.home.BrowserNotifications
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.tasks.toShortDate
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.IconBadge
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SkeletonLine
import org.example.project.ui.theme.SoftButton

@Composable
fun NotificationScreen(
    viewModel: NotificationViewModel,
    onOpenTask: (Long) -> Unit,
    onOpenConversation: (Long) -> Unit,
    onOpenProjectChat: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val unread = state.notifications.count { !it.isRead }
    var unreadOnly by remember { mutableStateOf(false) }
    val visibleNotifications = remember(state.notifications, unreadOnly) {
        if (unreadOnly) state.notifications.filter { !it.isRead } else state.notifications
    }
    var pushEnabled by remember { mutableStateOf(BrowserNotifications.isGranted()) }

    LaunchedEffect(viewModel) {
        BrowserNotifications.setPendingOpenListener {
            viewModel.openPendingNotification(onOpenTask, onOpenConversation, onOpenProjectChat)
        }
        viewModel.openPendingNotification(onOpenTask, onOpenConversation, onOpenProjectChat)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(ErpIconGlyph.Bell, gradient = Gradients.Berry, size = 40.dp, iconSize = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Центр уведомлений",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (unread == 0) "Всё прочитано" else "Непрочитанных: $unread",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unread > 0) {
                    SoftButton(
                        text = "Прочитать все",
                        onClick = { viewModel.markAllRead() }
                    )
                }
                SoftButton(
                    text = if (unreadOnly) "Показать все (${state.notifications.size})" else "Только непрочитанные ($unread)",
                    onClick = { unreadOnly = !unreadOnly }
                )
                if (!pushEnabled && BrowserNotifications.isSupported()) {
                    SoftButton(
                        text = "Включить push",
                        onClick = { BrowserNotifications.enablePush { pushEnabled = it } },
                        leadingIcon = ErpIconGlyph.Bell
                    )
                }
                SoftButton(
                    text = "Обновить",
                    onClick = viewModel::loadNotifications,
                    enabled = !state.isLoading,
                    leadingIcon = ErpIconGlyph.Refresh
                )
            }
        }

        state.operationError?.let {
            ErrorMessageCard(error = it, modifier = Modifier.padding(bottom = 12.dp))
        }

        when {
            state.isLoading && state.notifications.isEmpty() -> Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(5) { SkeletonLine(height = 64.dp) }
            }
            visibleNotifications.isEmpty() -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    title = if (unreadOnly) "Непрочитанных уведомлений нет" else "Уведомлений нет",
                    text = if (unreadOnly) "Все уведомления уже прочитаны" else "Здесь появятся события по задачам, проектам и документам",
                    glyph = ErpIconGlyph.Bell,
                    accent = Brand.Violet
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
            ) {
                items(visibleNotifications, key = { it.id }) { notification ->
                    NotificationCard(
                        notification = notification,
                        onClick = {
                            viewModel.markRead(notification)
                            viewModel.openNotification(
                                notification,
                                onOpenTask,
                                onOpenConversation,
                                onOpenProjectChat
                            )
                        }
                    )
                }
                if (state.hasMore) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                            GradientButton(
                                text = "Загрузить ещё",
                                onClick = viewModel::loadMore,
                                enabled = !state.isLoading,
                                gradient = Gradients.Berry
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(notification: NotificationDto, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (notification.isRead) Brand.Slate else Brand.Violet
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (notification.isRead) scheme.surfaceContainerLow.copy(alpha = 0.55f)
                else scheme.surfaceContainerLowest
            )
            .border(1.dp, accent.copy(alpha = if (notification.isRead) 0.10f else 0.24f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(
            ErpIconGlyph.Bell,
            gradient = if (notification.isRead) Gradients.Slate else Gradients.Berry,
            size = 36.dp,
            iconSize = 18.dp
        )
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = notification.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (notification.isRead) FontWeight.Normal else FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!notification.isRead) {
                    Spacer(Modifier.width(8.dp))
                    PillBadge(text = "Новое", accent = Brand.Violet, filled = true)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = localizedNotificationMeta(notification),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        ErpIcon(
            ErpIconGlyph.ChevronRight,
            scheme.onSurfaceVariant.copy(alpha = 0.7f),
            Modifier.size(16.dp)
        )
    }
}

private fun localizedNotificationMeta(notification: NotificationDto): String {
    val typeLabel = when (notification.type.uppercase()) {
        "TASK_ASSIGNED" -> "Назначение задачи"
        "TASK_COMMENT", "MENTION" -> "Комментарий / упоминание"
        "APPROVAL_ASSIGNED" -> "Требуется согласование"
        "APPROVAL_DECISION" -> "Решение по согласованию"
        else -> notification.type
    }
    val entityLabel = when (notification.entityType.uppercase()) {
        "TASK" -> "Задача"
        "DEMAND" -> "Потребность"
        "PROJECT" -> "Проект"
        "DOCUMENT" -> "Документ"
        "CONVERSATION" -> "Чат"
        "PURCHASE_ORDER" -> "Заказ"
        else -> notification.entityType
    }
    return "$typeLabel · $entityLabel #${notification.entityId} · ${notification.createdAt.toShortDate()}"
}
