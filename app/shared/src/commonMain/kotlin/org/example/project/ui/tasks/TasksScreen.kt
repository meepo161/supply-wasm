package org.example.project.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.example.project.dto.UserDto

@Composable
fun TasksScreen(
    currentUser: UserDto,
    viewModel: TaskViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.selectedTask) {
        if (showCreateDialog && state.selectedTask != null) {
            showCreateDialog = false
        }
    }

    val selected = state.selectedTask
    if (selected != null) {
        TaskDetailScreen(
            task = selected,
            currentUser = currentUser,
            state = state,
            viewModel = viewModel,
            onBack = viewModel::backToList,
            modifier = modifier
        )
    } else {
        TaskListScreen(
            tasks = state.tasks,
            users = state.users,
            isLoading = state.isLoading,
            error = state.error,
            onTaskClick = viewModel::selectTask,
            onCreateClick = { showCreateDialog = true },
            onRefresh = viewModel::loadTasks,
            modifier = modifier
        )
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
