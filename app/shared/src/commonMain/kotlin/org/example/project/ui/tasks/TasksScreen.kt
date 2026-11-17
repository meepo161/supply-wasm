package org.example.project.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import org.example.project.dto.UserDto

@Composable
fun TasksScreen(
    currentUser: UserDto,
    viewModel: TaskViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    // Экран задач и Task Chat используют один ViewModel, но это разные рабочие
    // области. Выбранная в чате задача не должна превращать реестр задач в чат.
    var openedFromTaskListId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.selectedTask) {
        if (showCreateDialog && state.selectedTask != null) {
            showCreateDialog = false
        }
    }

    val selected = state.selectedTask?.takeIf { it.id == openedFromTaskListId }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (selected != null) {
            TaskDetailScreen(
                task = selected,
                currentUser = currentUser,
                state = state,
                viewModel = viewModel,
                onBack = {
                    openedFromTaskListId = null
                    viewModel.backToList()
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            TaskListScreen(
                tasks = state.tasks,
                users = state.users,
                projects = state.projects,
                isLoading = state.isLoading,
                error = state.error,
                onTaskClick = {
                    openedFromTaskListId = it.id
                    viewModel.selectTask(it)
                },
                onCreateClick = { showCreateDialog = true },
                onRefresh = viewModel::loadTasks,
                currentUserId = currentUser.id,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showCreateDialog) {
        TaskCreateDialog(
            form = state.createForm,
            users = state.users,
            projects = state.projects,
            currentUser = currentUser,
            error = state.createError,
            onFormChange = viewModel::updateCreateForm,
            onCreate = { viewModel.createTask(currentUser.id) },
            onDismiss = { showCreateDialog = false }
        )
    }
}
