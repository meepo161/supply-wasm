package org.example.project.ui.projects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.example.project.dto.hasPermission

@Composable
fun ProjectsScreen(
    currentUser: org.example.project.dto.UserDto,
    viewModel: ProjectViewModel,
    onTaskOpen: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.selectedProject) {
        if (showCreateDialog && state.selectedProject != null) {
            showCreateDialog = false
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
            onOpenReasonChange = { viewModel.updateOpenForm(state.openForm.copy(openingReason = it)) },
            onOpenProject = { viewModel.openProject(selected) },
            onCloseProject = { viewModel.closeProject(selected, it) },
            onAddMember = { viewModel.addMember(selected.id, it) },
            onRemoveMember = { viewModel.removeMember(selected.id, it) },
            onBack = viewModel::backToList,
            modifier = modifier,
            tabsState = state,
            onTabSelected = viewModel::selectTab,
            onTabRefresh = { viewModel.loadTab() },
            onTaskStatusFilter = viewModel::filterTasks,
            onTaskOpen = onTaskOpen,
            // План может менять владелец проекта или держатель projects:manage_plan;
            // сервер проверяет это повторно, здесь решается только видимость кнопок.
            canManagePlan = selected.ownerId == currentUser.id ||
                currentUser.hasPermission("projects", "manage_plan"),
            onRiskFilter = viewModel::filterRisks,
            planActions = ProjectPlanActions(
                onCreateStage = viewModel::createStage,
                onStartStage = viewModel::startStage,
                onCompleteStage = viewModel::completeStage,
                onCancelStage = viewModel::cancelStage,
                onDeleteStage = viewModel::deleteStage,
                onCreateMilestone = viewModel::createMilestone,
                onReachMilestone = viewModel::reachMilestone,
                onMissMilestone = viewModel::missMilestone,
                onCreateRisk = viewModel::createRisk,
                onMitigateRisk = viewModel::mitigateRisk,
                onReopenRisk = viewModel::reopenRisk,
                onCloseRisk = viewModel::closeRisk,
                onDeleteRisk = viewModel::deleteRisk,
                onDismissError = viewModel::dismissPlanError
            )
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
