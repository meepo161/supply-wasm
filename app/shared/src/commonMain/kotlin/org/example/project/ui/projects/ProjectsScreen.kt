package org.example.project.ui.projects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.example.project.ui.conversations.ConversationViewModel

@Composable
fun ProjectsScreen(
    currentUser: org.example.project.dto.UserDto,
    viewModel: ProjectViewModel,
    conversationViewModel: ConversationViewModel,
    onOpenTask: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.selectedProject) {
        if (showCreateDialog && state.selectedProject != null) {
            showCreateDialog = false
        }
    }

    // Проектное обсуждение: get-or-create на сервере при выборе проекта,
    // при смене/закрытии проекта панель сбрасывается, чужие сообщения не показываются.
    LaunchedEffect(state.selectedProject?.id) {
        val projectId = state.selectedProject?.id
        if (projectId != null) {
            conversationViewModel.openProjectChat(projectId)
        } else {
            conversationViewModel.closeConversation()
        }
    }

    val selected = state.selectedProject
    if (selected != null) {
        ProjectDetailScreen(
            project = selected,
            currentUser = currentUser,
            users = state.users,
            detailLoading = state.detailLoading,
            openError = state.openError,
            closeError = state.closeError,
            isClosing = state.isClosing,
            memberError = state.memberError,
            openForm = state.openForm,
            activeTab = state.detailTab,
            projectTasks = if (state.projectTasksProjectId == selected.id) state.projectTasks else emptyList(),
            projectTasksLoading = state.projectTasksLoading || state.projectTasksProjectId != selected.id,
            projectTasksError = if (state.projectTasksProjectId == selected.id) state.projectTasksError else null,
            onTabChange = viewModel::selectDetailTab,
            onRefreshTasks = { viewModel.loadProjectTasks(selected.id) },
            onOpenTask = { onOpenTask(it.id) },
            conversationViewModel = conversationViewModel,
            onOpenReasonChange = { viewModel.updateOpenForm(state.openForm.copy(openingReason = it)) },
            onOpenProject = { viewModel.openProject(selected) },
            onCloseProject = { viewModel.closeProject(selected, it) },
            onAddMember = { viewModel.addMember(selected.id, it) },
            onRemoveMember = { viewModel.removeMember(selected.id, it) },
            onBack = viewModel::backToList,
            modifier = modifier
        )
    } else {
        ProjectListScreen(
            projects = state.projects,
            isLoading = state.isLoading,
            error = state.error,
            onProjectClick = viewModel::selectProject,
            onCreateClick = {
                if (state.createForm.ownerId == null) {
                    viewModel.updateCreateForm(state.createForm.copy(ownerId = currentUser.id))
                }
                showCreateDialog = true
            },
            onRefresh = viewModel::loadProjects,
            modifier = modifier
        )
    }

    if (showCreateDialog) {
        ProjectCreateDialog(
            form = state.createForm,
            users = state.users,
            currentUser = currentUser,
            error = state.createError,
            onFormChange = viewModel::updateCreateForm,
            onCreate = viewModel::createProject,
            onDismiss = { showCreateDialog = false }
        )
    }
}
