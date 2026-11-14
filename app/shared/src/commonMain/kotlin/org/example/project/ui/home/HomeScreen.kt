package org.example.project.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.NotificationDto
import org.example.project.dto.TaskDto
import org.example.project.ui.projects.ErrorMessageCard

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTask: (Long) -> Unit,
    onOpenNotifications: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var browserNotificationEnabled by remember {
        mutableStateOf(BrowserNotifications.isGranted())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Главная") },
                actions = {
                    TextButton(onClick = onOpenNotifications) {
                        Text("Уведомления (${state.notifications.count { !it.isRead }})")
                    }
                    TextButton(onClick = viewModel::refresh) { Text("Обновить") }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Здравствуйте, ${viewModel.currentUser.fullName}",
                style = MaterialTheme.typography.headlineSmall
            )

            BrowserNotificationRow(
                enabled = browserNotificationEnabled,
                supported = BrowserNotifications.isSupported(),
                onEnable = {
                    viewModel.requestBrowserNotificationPermission { granted ->
                        browserNotificationEnabled = granted
                    }
                }
            )

            state.operationError?.let { ErrorMessageCard(error = it) }

            if (state.isLoadingTasks || state.isLoadingNotifications) {
                LoadingOverlay()
            }

            val today = state.currentDate
            val myAssigned = state.allTasks.filter { it.assigneeId == viewModel.currentUser.id }
            val openAssigned = myAssigned.filter { it.isOpen() }
            val overdue = openAssigned.filter { it.isOverdue(today) }.sortedBy { it.dueDate }
            val upcoming = openAssigned.filter { it.isUpcoming(today) }.sortedBy { it.dueDate }
            val onReview = state.allTasks.filter {
                it.reviewerId == viewModel.currentUser.id && it.status == org.example.project.dto.TaskStatus.ON_REVIEW
            }
            val unreadNotifications = state.notifications.filter { !it.isRead }

            TaskSection(
                title = "Просроченные задачи",
                tasks = overdue,
                onTaskClick = { task -> viewModel.openTask(task.id, onOpenTask) },
                emptyText = "Нет просроченных задач."
            )

            TaskSection(
                title = "Ближайшие сроки",
                tasks = upcoming,
                onTaskClick = { task -> viewModel.openTask(task.id, onOpenTask) },
                emptyText = "Нет задач с ближайшими сроками."
            )

            TaskSection(
                title = "Задачи на приёмке",
                tasks = onReview,
                onTaskClick = { task -> viewModel.openTask(task.id, onOpenTask) },
                emptyText = "Нет задач на вашей приёмке."
            )

            TaskSection(
                title = "Мои открытые задачи",
                tasks = openAssigned,
                onTaskClick = { task -> viewModel.openTask(task.id, onOpenTask) },
                emptyText = "Нет назначенных открытых задач."
            )

            NotificationSection(
                title = "Непрочитанные уведомления",
                notifications = unreadNotifications,
                onNotificationClick = { notification ->
                    viewModel.openNotificationEntity(notification, onOpenTask)
                },
                onOpenAll = onOpenNotifications,
                emptyText = "Нет непрочитанных уведомлений."
            )
        }
    }
}

@Composable
private fun BrowserNotificationRow(
    enabled: Boolean,
    supported: Boolean,
    onEnable: () -> Unit
) {
    if (!supported) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (enabled) "Браузерные уведомления включены" else "Браузерные уведомления отключены",
            style = MaterialTheme.typography.bodyMedium
        )
        if (!enabled) {
            OutlinedButton(onClick = onEnable) { Text("Включить") }
        }
    }
}

@Composable
private fun TaskSection(
    title: String,
    tasks: List<TaskDto>,
    onTaskClick: (TaskDto) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "$title (${tasks.size})", style = MaterialTheme.typography.titleMedium)
        }
        if (tasks.isEmpty()) {
            Text(text = emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            HomeTaskGrid(
                tasks = tasks,
                onClick = onTaskClick,
                modifier = Modifier.fillMaxWidth().height(240.dp)
            )
        }
    }
}

@Composable
private fun NotificationSection(
    title: String,
    notifications: List<NotificationDto>,
    onNotificationClick: (NotificationDto) -> Unit,
    onOpenAll: () -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "$title (${notifications.size})", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onOpenAll) { Text("Все уведомления") }
        }
        if (notifications.isEmpty()) {
            Text(text = emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            HomeNotificationGrid(
                notifications = notifications,
                onClick = onNotificationClick,
                modifier = Modifier.fillMaxWidth().height(240.dp)
            )
        }
    }
}

@Composable
private fun LoadingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
