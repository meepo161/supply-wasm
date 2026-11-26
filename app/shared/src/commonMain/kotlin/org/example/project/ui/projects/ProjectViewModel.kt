package org.example.project.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.ProjectRepository
import org.example.project.data.ProjectMilestoneCommand
import org.example.project.data.ProjectStageCommand
import org.example.project.data.TaskRepository
import org.example.project.data.UserRepository
import org.example.project.dto.AddProjectMemberRequest
import org.example.project.dto.ApiError
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.DocumentDto
import org.example.project.dto.ProjectHistoryEntryDto
import org.example.project.dto.ProjectSummaryDto
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus
import org.example.project.dto.CloseProjectRiskRequest
import org.example.project.dto.CreateProjectMilestoneRequest
import org.example.project.dto.CreateProjectRiskRequest
import org.example.project.dto.CreateProjectStageRequest
import org.example.project.dto.ProjectMilestoneDto
import org.example.project.dto.ProjectMilestoneCommandRequest
import org.example.project.dto.ProjectPlanSummaryDto
import org.example.project.dto.ProjectRiskCategory
import org.example.project.dto.ProjectRiskCriticality
import org.example.project.dto.ProjectRiskDto
import org.example.project.dto.ProjectRiskLevel
import org.example.project.dto.ProjectRiskStatus
import org.example.project.dto.ProjectStageCommandRequest
import org.example.project.dto.ProjectStageDto
import org.example.project.dto.UpdateProjectStageRequest
import org.example.project.dto.CloseProjectRequest
import org.example.project.dto.CreateProjectRequest
import org.example.project.dto.OpenProjectRequest
import org.example.project.dto.ProjectDto
import org.example.project.dto.ProjectPriority
import org.example.project.dto.ProjectType
import org.example.project.dto.UserDto
import org.example.project.ui.tasks.filterByProject

class ProjectViewModel(
    private val projectRepository: ProjectRepository,
    private val userRepository: UserRepository,
    private val taskRepository: TaskRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectsUiState())
    val uiState: StateFlow<ProjectsUiState> = _uiState.asStateFlow()

    init {
        loadProjects()
        loadUsers()
    }

    fun loadProjects() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            when (val result = projectRepository.list()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    projects = result.data,
                    isLoading = false,
                    error = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.error
                )
            }
        }
    }

    private fun loadUsers() {
        viewModelScope.launch {
            when (val result = userRepository.list()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(users = result.data)
                is ApiResult.Error -> { /* пользователь без права чтения справочника — UI скроет выбор */
                }
            }
        }
    }

    fun selectProject(project: ProjectDto) {
        _uiState.value = _uiState.value.copy(
            selectedProject = project,
            supplyOverview = null,
            supplyError = null,
            detailLoading = true,
            openError = null,
            memberError = null,
            selectedTab = ProjectTab.OVERVIEW,
            tabError = null,
            summary = null,
            tasks = emptyList(),
            demands = emptyList(),
            documents = emptyList(),
            history = emptyList(),
            loadedTabs = emptySet(),
            taskStatusFilter = null,
            stages = emptyList(),
            risks = emptyList(),
            planSummary = null,
            riskStatusFilter = null,
            planError = null
        )
        loadProjectTasks(project.id)
        loadSupplyOverview(project.id)
        viewModelScope.launch {
            refreshSelectedProject(project.id)
            loadTab(ProjectTab.OVERVIEW)
        }
    }

    fun loadSupplyOverview(projectId: Long) {
        _uiState.value = _uiState.value.copy(supplyLoading = true, supplyError = null)
        viewModelScope.launch {
            val result = projectRepository.getSupplyOverview(projectId)
            if (_uiState.value.selectedProject?.id != projectId) return@launch
            _uiState.value = when (result) {
                is ApiResult.Success -> _uiState.value.copy(supplyOverview = result.data, supplyLoading = false)
                is ApiResult.Error -> _uiState.value.copy(supplyError = result.error, supplyLoading = false)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Вкладки карточки проекта (PRJ-13/PRJ-14)
    // -----------------------------------------------------------------------

    /**
     * Переключение вкладки. Данные загружаются при первом открытии и по кнопке
     * «Обновить»: повторный клик по той же вкладке не тратит запросы.
     */
    fun selectTab(tab: ProjectTab) {
        val project = _uiState.value.selectedProject ?: return
        _uiState.value = _uiState.value.copy(selectedTab = tab, tabError = null)
        // Загруженность отслеживается явно: пустой список — тоже валидный
        // результат, и повторно запрашивать его при каждом клике не нужно.
        if (tab !in _uiState.value.loadedTabs) loadTab(tab, project.id)
    }

    fun loadTab(tab: ProjectTab = _uiState.value.selectedTab, projectId: Long? = null) {
        val id = projectId ?: _uiState.value.selectedProject?.id ?: return
        _uiState.value = _uiState.value.copy(tabLoading = true, tabError = null)
        viewModelScope.launch {
            when (tab) {
                ProjectTab.OVERVIEW -> when (val result = projectRepository.summary(id)) {
                    is ApiResult.Success -> {
                        if (_uiState.value.selectedProject?.id != id) return@launch
                        _uiState.value = _uiState.value.copy(
                            summary = result.data,
                            tabLoading = false,
                            loadedTabs = _uiState.value.loadedTabs + tab
                        )
                        // Сводка плана необязательна: её сбой не ломает обзор.
                        loadPlanSummary(id)
                    }
                    is ApiResult.Error -> {
                        if (_uiState.value.selectedProject?.id != id) return@launch
                        _uiState.value = _uiState.value.copy(tabLoading = false, tabError = result.error)
                    }
                }
                ProjectTab.STAGES -> loadStages(id)
                ProjectTab.RISKS -> loadRisks(id, _uiState.value.riskStatusFilter)
                ProjectTab.TASKS -> loadTasks(id, _uiState.value.taskStatusFilter)
                ProjectTab.DEMANDS -> when (val result = projectRepository.demands(id)) {
                    is ApiResult.Success -> {
                        if (_uiState.value.selectedProject?.id != id) return@launch
                        _uiState.value = _uiState.value.copy(
                            demands = result.data,
                            tabLoading = false,
                            loadedTabs = _uiState.value.loadedTabs + tab
                        )
                    }
                    is ApiResult.Error -> {
                        if (_uiState.value.selectedProject?.id != id) return@launch
                        _uiState.value = _uiState.value.copy(tabLoading = false, tabError = result.error)
                    }
                }
                ProjectTab.DOCUMENTS -> when (val result = projectRepository.documents(id)) {
                    is ApiResult.Success -> {
                        if (_uiState.value.selectedProject?.id != id) return@launch
                        _uiState.value = _uiState.value.copy(
                            documents = result.data,
                            tabLoading = false,
                            loadedTabs = _uiState.value.loadedTabs + tab
                        )
                    }
                    is ApiResult.Error -> {
                        if (_uiState.value.selectedProject?.id != id) return@launch
                        _uiState.value = _uiState.value.copy(tabLoading = false, tabError = result.error)
                    }
                }
                ProjectTab.HISTORY -> when (val result = projectRepository.history(id)) {
                    is ApiResult.Success -> {
                        if (_uiState.value.selectedProject?.id != id) return@launch
                        _uiState.value = _uiState.value.copy(
                            history = result.data,
                            tabLoading = false,
                            loadedTabs = _uiState.value.loadedTabs + tab
                        )
                    }
                    is ApiResult.Error -> {
                        if (_uiState.value.selectedProject?.id != id) return@launch
                        _uiState.value = _uiState.value.copy(tabLoading = false, tabError = result.error)
                    }
                }
                // Обсуждение отрисовывается ConversationPanel и не требует запроса
                // к projectRepository: диалог открывается отдельно через openProjectChat.
                ProjectTab.DISCUSSION -> _uiState.value = _uiState.value.copy(
                    tabLoading = false,
                    loadedTabs = _uiState.value.loadedTabs + tab
                )
            }
        }
    }

    /** Фильтр по статусу задачи выполняется на сервере, а не в списке на клиенте. */
    fun filterTasks(status: TaskStatus?) {
        val project = _uiState.value.selectedProject ?: return
        _uiState.value = _uiState.value.copy(taskStatusFilter = status)
        loadTasks(project.id, status)
    }

    private fun loadTasks(projectId: Long, status: TaskStatus?) {
        _uiState.value = _uiState.value.copy(tabLoading = true, tabError = null)
        viewModelScope.launch {
            val result = projectRepository.tasks(projectId, status)
            if (_uiState.value.selectedProject?.id != projectId ||
                _uiState.value.taskStatusFilter != status
            ) {
                return@launch
            }
            when (result) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    tasks = result.data,
                    tabLoading = false,
                    loadedTabs = _uiState.value.loadedTabs + ProjectTab.TASKS
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    tabLoading = false,
                    tabError = result.error
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // План проекта: этапы, контрольные точки, риски (PRJ-08/09/10)
    // -----------------------------------------------------------------------

    private fun loadStages(projectId: Long) {
        viewModelScope.launch {
            val result = projectRepository.stages(projectId)
            if (_uiState.value.selectedProject?.id != projectId) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    stages = result.data,
                    tabLoading = false,
                    loadedTabs = _uiState.value.loadedTabs + ProjectTab.STAGES
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    tabLoading = false,
                    tabError = result.error
                )
            }
        }
    }

    private fun loadRisks(projectId: Long, status: ProjectRiskStatus?) {
        viewModelScope.launch {
            val result = projectRepository.risks(projectId, status)
            if (_uiState.value.selectedProject?.id != projectId ||
                _uiState.value.riskStatusFilter != status
            ) {
                return@launch
            }
            when (result) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    risks = result.data,
                    tabLoading = false,
                    loadedTabs = _uiState.value.loadedTabs + ProjectTab.RISKS
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    tabLoading = false,
                    tabError = result.error
                )
            }
        }
    }

    private fun loadPlanSummary(projectId: Long) {
        viewModelScope.launch {
            when (val result = projectRepository.planSummary(projectId)) {
                is ApiResult.Success -> {
                    if (_uiState.value.selectedProject?.id != projectId) return@launch
                    _uiState.value = _uiState.value.copy(planSummary = result.data)
                }
                // Ошибка сводки плана не заменяет сводку связанных записей.
                is ApiResult.Error -> Unit
            }
        }
    }

    /** Фильтр рисков по статусу выполняется на сервере. */
    fun filterRisks(status: ProjectRiskStatus?) {
        val project = _uiState.value.selectedProject ?: return
        _uiState.value = _uiState.value.copy(riskStatusFilter = status)
        loadRisks(project.id, status)
    }

    fun dismissPlanError() {
        _uiState.value = _uiState.value.copy(planError = null)
    }

    fun createStage(name: String, plannedStartAt: String?, plannedEndAt: String?) {
        val project = _uiState.value.selectedProject ?: return
        if (name.isBlank()) {
            _uiState.value = _uiState.value.copy(
                planError = ApiError("VALIDATION_ERROR", "Укажите название этапа")
            )
            return
        }
        runPlanAction(onSuccess = { refreshPlan(project.id) }) {
            projectRepository.createStage(
                project.id,
                CreateProjectStageRequest(
                    name = name,
                    plannedStartAt = plannedStartAt?.takeIf { it.isNotBlank() },
                    plannedEndAt = plannedEndAt?.takeIf { it.isNotBlank() }
                )
            )
        }
    }

    fun startStage(stage: ProjectStageDto) = stageCommand(stage, ProjectStageCommand.START)

    fun completeStage(stage: ProjectStageDto) = stageCommand(stage, ProjectStageCommand.COMPLETE)

    fun cancelStage(stage: ProjectStageDto, reason: String) {
        val project = _uiState.value.selectedProject ?: return
        if (reason.isBlank()) {
            _uiState.value = _uiState.value.copy(
                planError = ApiError("VALIDATION_ERROR", "Укажите причину отмены этапа")
            )
            return
        }
        runPlanAction(onSuccess = { refreshPlan(project.id) }) {
            projectRepository.stageCommand(
                stage.id,
                ProjectStageCommand.CANCEL,
                ProjectStageCommandRequest(stage.version, reason)
            )
        }
    }

    fun renameStage(stage: ProjectStageDto, name: String, plannedStartAt: String?, plannedEndAt: String?) {
        val project = _uiState.value.selectedProject ?: return
        runPlanAction(onSuccess = { refreshPlan(project.id) }) {
            projectRepository.updateStage(
                stage.id,
                UpdateProjectStageRequest(
                    name = name.takeIf { it.isNotBlank() },
                    plannedStartAt = plannedStartAt?.takeIf { it.isNotBlank() },
                    plannedEndAt = plannedEndAt?.takeIf { it.isNotBlank() },
                    version = stage.version
                )
            )
        }
    }

    fun deleteStage(stage: ProjectStageDto) {
        val project = _uiState.value.selectedProject ?: return
        runPlanAction(onSuccess = { refreshPlan(project.id) }) {
            projectRepository.deleteStage(stage.id, stage.version)
        }
    }

    private fun stageCommand(stage: ProjectStageDto, command: ProjectStageCommand) {
        val project = _uiState.value.selectedProject ?: return
        runPlanAction(onSuccess = { refreshPlan(project.id) }) {
            projectRepository.stageCommand(stage.id, command, ProjectStageCommandRequest(stage.version))
        }
    }

    fun createMilestone(name: String, stageId: Long?, plannedAt: String?) {
        val project = _uiState.value.selectedProject ?: return
        if (name.isBlank()) {
            _uiState.value = _uiState.value.copy(
                planError = ApiError("VALIDATION_ERROR", "Укажите название контрольной точки")
            )
            return
        }
        runPlanAction(onSuccess = { refreshPlan(project.id) }) {
            projectRepository.createMilestone(
                project.id,
                CreateProjectMilestoneRequest(
                    name = name,
                    stageId = stageId,
                    plannedAt = plannedAt?.takeIf { it.isNotBlank() }
                )
            )
        }
    }

    fun reachMilestone(milestone: ProjectMilestoneDto) {
        val project = _uiState.value.selectedProject ?: return
        runPlanAction(onSuccess = { refreshPlan(project.id) }) {
            projectRepository.milestoneCommand(
                milestone.id,
                ProjectMilestoneCommand.REACH,
                ProjectMilestoneCommandRequest(milestone.version)
            )
        }
    }

    fun missMilestone(milestone: ProjectMilestoneDto, note: String) {
        val project = _uiState.value.selectedProject ?: return
        runPlanAction(onSuccess = { refreshPlan(project.id) }) {
            projectRepository.milestoneCommand(
                milestone.id,
                ProjectMilestoneCommand.MISS,
                ProjectMilestoneCommandRequest(milestone.version, note.takeIf { it.isNotBlank() })
            )
        }
    }

    fun createRisk(
        title: String,
        description: String?,
        category: ProjectRiskCategory,
        probability: ProjectRiskLevel,
        impact: ProjectRiskLevel,
        criticality: ProjectRiskCriticality,
        responsePlan: String?,
        dueDate: String?,
        stageId: Long?,
        taskId: Long?
    ) {
        val project = _uiState.value.selectedProject ?: return
        if (title.isBlank()) {
            _uiState.value = _uiState.value.copy(
                planError = ApiError("VALIDATION_ERROR", "Укажите название риска")
            )
            return
        }
        runPlanAction(onSuccess = { refreshPlan(project.id) }) {
            projectRepository.createRisk(
                project.id,
                CreateProjectRiskRequest(
                    title = title,
                    description = description?.takeIf { it.isNotBlank() },
                    category = category,
                    probability = probability,
                    impact = impact,
                    criticality = criticality,
                    responsePlan = responsePlan?.takeIf { it.isNotBlank() },
                    dueDate = dueDate?.takeIf { it.isNotBlank() },
                    stageId = stageId,
                    taskId = taskId
                )
            )
        }
    }

    fun mitigateRisk(risk: ProjectRiskDto) {
        val project = _uiState.value.selectedProject ?: return
        runPlanAction(onSuccess = { refreshPlan(project.id) }) {
            projectRepository.mitigateRisk(risk.id, risk.version)
        }
    }

    fun reopenRisk(risk: ProjectRiskDto) {
        val project = _uiState.value.selectedProject ?: return
        runPlanAction(onSuccess = { refreshPlan(project.id) }) {
            projectRepository.reopenRisk(risk.id, risk.version)
        }
    }

    fun closeRisk(risk: ProjectRiskDto, outcome: String, accepted: Boolean) {
        val project = _uiState.value.selectedProject ?: return
        if (outcome.isBlank()) {
            _uiState.value = _uiState.value.copy(
                planError = ApiError("VALIDATION_ERROR", "Укажите исход риска")
            )
            return
        }
        runPlanAction(onSuccess = { refreshPlan(project.id) }) {
            projectRepository.closeRisk(
                risk.id,
                CloseProjectRiskRequest(
                    version = risk.version,
                    outcome = outcome,
                    status = if (accepted) ProjectRiskStatus.ACCEPTED else ProjectRiskStatus.CLOSED
                )
            )
        }
    }

    fun deleteRisk(risk: ProjectRiskDto) {
        val project = _uiState.value.selectedProject ?: return
        runPlanAction(onSuccess = { refreshPlan(project.id) }) {
            projectRepository.deleteRisk(risk.id, risk.version)
        }
    }

    /**
     * Изменение плана меняет и сводку: этапы, точки и риски считаются на сервере,
     * поэтому после успешной команды перезапрашиваются и вкладка, и сводка.
     */
    private fun refreshPlan(projectId: Long) {
        loadStages(projectId)
        loadRisks(projectId, _uiState.value.riskStatusFilter)
        loadPlanSummary(projectId)
    }

    /**
     * Обёртка над действием плана: единая обработка ошибок и признак занятости.
     * [onSuccess] вызывается только при успехе — списки не перезапрашиваются
     * после неудачной команды.
     */
    private fun runPlanAction(
        onSuccess: () -> Unit = {},
        block: suspend () -> ApiResult<*>
    ) {
        _uiState.value = _uiState.value.copy(planBusy = true, planError = null)
        viewModelScope.launch {
            when (val result = block()) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(planBusy = false)
                    onSuccess()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    planBusy = false,
                    planError = result.error
                )
            }
        }
    }

    fun dismissTabError() {
        _uiState.value = _uiState.value.copy(tabError = null)
    }

    /**
     * Открытие проекта из глобального поиска: id приходит без загруженного DTO,
     * поэтому карточка запрашивается у сервера — права проверяются повторно.
     */
    fun openProjectById(projectId: Long) {
        // Вкладки от предыдущего проекта не должны переезжать в новый.
        _uiState.value = _uiState.value.copy(
            detailLoading = true,
            openError = null,
            memberError = null,
            selectedTab = ProjectTab.OVERVIEW,
            tabError = null,
            summary = null,
            tasks = emptyList(),
            demands = emptyList(),
            documents = emptyList(),
            history = emptyList(),
            taskStatusFilter = null,
            loadedTabs = emptySet(),
            stages = emptyList(),
            risks = emptyList(),
            planSummary = null,
            riskStatusFilter = null,
            planError = null
        )
        // projectTasksProjectId фиксирует целевой проект: задачи и UI не покажут
        // данные прежней карточки, если пользователь успел переключиться.
        loadProjectTasks(projectId)
        viewModelScope.launch {
            when (val result = projectRepository.get(projectId)) {
                is ApiResult.Success -> if (_uiState.value.projectTasksProjectId == projectId) {
                    _uiState.value = _uiState.value.copy(
                        selectedProject = result.data,
                        detailLoading = false
                    )
                    loadTab(ProjectTab.OVERVIEW, projectId)
                }
                is ApiResult.Error -> if (_uiState.value.projectTasksProjectId == projectId) {
                    _uiState.value = _uiState.value.copy(
                        detailLoading = false,
                        error = result.error
                    )
                }
            }
        }
    }

    fun backToList() {
        _uiState.value = _uiState.value.copy(
            selectedProject = null,
            openError = null,
            memberError = null,
            createError = null,
            selectedTab = ProjectTab.OVERVIEW,
            tabError = null,
            summary = null,
            tasks = emptyList(),
            demands = emptyList(),
            documents = emptyList(),
            history = emptyList(),
            taskStatusFilter = null,
            loadedTabs = emptySet(),
            stages = emptyList(),
            risks = emptyList(),
            planSummary = null,
            riskStatusFilter = null,
            planError = null,
            projectTasks = emptyList(),
            projectTasksProjectId = null,
            projectTasksLoading = false,
            projectTasksError = null
        )
    }

    /**
     * Выбор вкладки извне карточки (например, переход к обсуждению из уведомления).
     * Использует тот же [ProjectTab], что и [selectTab]: состояние вкладок одно,
     * поэтому обсуждение не может разойтись с вкладками плана.
     */
    fun selectDetailTab(tab: ProjectTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab, tabError = null)
        val projectId = _uiState.value.selectedProject?.id
        if (projectId != null && tab !in _uiState.value.loadedTabs) loadTab(tab, projectId)
    }

    /**
     * Задачи выбранного проекта: сервер возвращает только разрешённые записи,
     * отбор по projectId выполняется на клиенте по уже загруженным DTO.
     * projectTasksProjectId фиксирует целевой проект загрузки: результат отбрасывается,
     * если пользователь успел выбрать другой проект, а UI показывает задачи,
     * только когда projectTasksProjectId совпадает с выбранным проектом.
     */
    fun loadProjectTasks(projectId: Long) {
        _uiState.value = _uiState.value.copy(
            projectTasks = emptyList(),
            projectTasksProjectId = projectId,
            projectTasksLoading = true,
            projectTasksError = null
        )
        viewModelScope.launch {
            when (val result = taskRepository.list()) {
                is ApiResult.Success -> {
                    if (_uiState.value.projectTasksProjectId == projectId) {
                        _uiState.value = _uiState.value.copy(
                            projectTasks = result.data.filterByProject(projectId),
                            projectTasksLoading = false,
                            projectTasksError = null
                        )
                    }
                }
                is ApiResult.Error -> {
                    if (_uiState.value.projectTasksProjectId == projectId) {
                        _uiState.value = _uiState.value.copy(
                            projectTasksLoading = false,
                            projectTasksError = result.error
                        )
                    }
                }
            }
        }
    }

    fun createProject() {
        val form = _uiState.value.createForm
        val ownerId = form.ownerId
        if (form.name.isBlank() || form.projectType == null || ownerId == null) {
            _uiState.value = _uiState.value.copy(
                createError = org.example.project.dto.ApiError(
                    "VALIDATION_ERROR",
                    "Заполните обязательные поля: название, тип проекта и руководитель"
                )
            )
            return
        }
        _uiState.value = _uiState.value.copy(createError = null)
        viewModelScope.launch {
            val request = CreateProjectRequest(
                name = form.name,
                fullName = form.fullName.takeIf { it.isNotBlank() },
                description = form.description.takeIf { it.isNotBlank() },
                projectType = form.projectType,
                customer = form.customer.takeIf { it.isNotBlank() },
                ownerId = ownerId,
                departmentId = form.departmentId,
                priority = form.priority,
                plannedStartAt = form.plannedStartAt.takeIf { it.isNotBlank() },
                plannedEndAt = form.plannedEndAt.takeIf { it.isNotBlank() }
            )
            when (val result = projectRepository.create(request)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createForm = ProjectCreateForm(),
                        createError = null
                    )
                    // selectProject сбрасывает вкладки и запускает загрузку задач
                    // нового проекта через loadProjectTasks.
                    selectProject(result.data)
                    loadProjects()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(createError = result.error)
            }
        }
    }

    fun openProject(project: ProjectDto) {
        val reason = _uiState.value.openForm.openingReason
        if (reason.isBlank()) {
            _uiState.value = _uiState.value.copy(
                openError = org.example.project.dto.ApiError(
                    "VALIDATION_ERROR",
                    "Укажите причину открытия проекта"
                )
            )
            return
        }
        _uiState.value = _uiState.value.copy(openError = null)
        viewModelScope.launch {
            when (val result = projectRepository.open(project.id, OpenProjectRequest(reason, project.version))) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedProject = result.data,
                        openForm = OpenProjectForm(),
                        loadedTabs = emptySet()
                    )
                    loadTab()
                    loadProjects()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(openError = result.error)
            }
        }
    }

    fun closeProject(project: ProjectDto, reason: String) {
        if (_uiState.value.isClosing) return
        _uiState.value = _uiState.value.copy(isClosing = true, closeError = null)
        viewModelScope.launch {
            when (val result = projectRepository.close(project.id, CloseProjectRequest(project.version, reason))) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedProject = result.data,
                        isClosing = false,
                        closeError = null,
                        loadedTabs = emptySet()
                    )
                    loadTab()
                    loadProjects()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(isClosing = false, closeError = result.error)
                    if (result.statusCode == 409) refreshSelectedProject(project.id)
                }
            }
        }
    }

    fun addMember(projectId: Long, userId: Long) {
        _uiState.value = _uiState.value.copy(memberError = null)
        viewModelScope.launch {
            when (val result = projectRepository.addMember(projectId, AddProjectMemberRequest(userId))) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(selectedProject = result.data, loadedTabs = emptySet())
                    loadTab()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(memberError = result.error)
            }
        }
    }

    fun removeMember(projectId: Long, userId: Long) {
        _uiState.value = _uiState.value.copy(memberError = null)
        viewModelScope.launch {
            when (val result = projectRepository.removeMember(projectId, userId)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(selectedProject = result.data, loadedTabs = emptySet())
                    loadTab()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(memberError = result.error)
            }
        }
    }

    fun refreshSelectedProject(projectId: Long) {
        viewModelScope.launch {
            val result = projectRepository.get(projectId)
            if (_uiState.value.selectedProject?.id != projectId) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    selectedProject = result.data,
                    detailLoading = false
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    detailLoading = false,
                    openError = result.error
                )
            }
        }
    }

    fun updateCreateForm(form: ProjectCreateForm) {
        _uiState.value = _uiState.value.copy(createForm = form)
    }

    fun updateOpenForm(form: OpenProjectForm) {
        _uiState.value = _uiState.value.copy(openForm = form)
    }

    fun dismissCreateError() {
        _uiState.value = _uiState.value.copy(createError = null)
    }

    fun dismissOpenError() {
        _uiState.value = _uiState.value.copy(openError = null)
    }

    fun dismissMemberError() {
        _uiState.value = _uiState.value.copy(memberError = null)
    }
}

data class ProjectsUiState(
    val projects: List<ProjectDto> = emptyList(),
    val users: List<UserDto> = emptyList(),
    val selectedProject: ProjectDto? = null,
    val supplyOverview: org.example.project.dto.ProjectSupplyOverviewDto? = null,
    val supplyLoading: Boolean = false,
    val supplyError: ApiError? = null,
    val isLoading: Boolean = false,
    val detailLoading: Boolean = false,
    val error: org.example.project.dto.ApiError? = null,
    val createError: org.example.project.dto.ApiError? = null,
    val openError: org.example.project.dto.ApiError? = null,
    val memberError: org.example.project.dto.ApiError? = null,
    val closeError: org.example.project.dto.ApiError? = null,
    val isClosing: Boolean = false,
    val createForm: ProjectCreateForm = ProjectCreateForm(),
    val openForm: OpenProjectForm = OpenProjectForm(),

    // Вкладки карточки (PRJ-13/PRJ-14)
    val selectedTab: ProjectTab = ProjectTab.OVERVIEW,
    val tabLoading: Boolean = false,
    val tabError: org.example.project.dto.ApiError? = null,
    val summary: ProjectSummaryDto? = null,
    val tasks: List<TaskDto> = emptyList(),
    val demands: List<DemandHeaderDto> = emptyList(),
    val documents: List<DocumentDto> = emptyList(),
    val history: List<ProjectHistoryEntryDto> = emptyList(),
    val taskStatusFilter: TaskStatus? = null,

    // План проекта (PRJ-08/09/10). Контрольные точки приходят вложенными в
    // этапы (GET /stages), поэтому отдельного списка здесь нет.
    val stages: List<ProjectStageDto> = emptyList(),
    val risks: List<ProjectRiskDto> = emptyList(),
    val planSummary: ProjectPlanSummaryDto? = null,
    val riskStatusFilter: ProjectRiskStatus? = null,
    val planError: ApiError? = null,
    val planBusy: Boolean = false,
    /** Вкладки, данные которых уже получены с сервера (включая пустые ответы). */
    val loadedTabs: Set<ProjectTab> = emptySet(),

    // Задачи проекта для вкладки TASKS карточки. Отдельный снимок нужен,
    // потому что state.tasks загружается вкладками плана и фильтруется
    // на сервере по taskStatusFilter.
    val projectTasks: List<TaskDto> = emptyList(),
    val projectTasksProjectId: Long? = null,
    val projectTasksLoading: Boolean = false,
    val projectTasksError: org.example.project.dto.ApiError? = null
)

/**
 * Вкладки карточки проекта: обзор и данные плана (PRJ-08/09/10, PRJ-13/14)
 * плюс обсуждение проекта.
 */
enum class ProjectTab(val title: String) {
    OVERVIEW("Обзор"),
    STAGES("Этапы"),
    TASKS("Задачи"),
    RISKS("Риски"),
    DEMANDS("Потребности"),
    DOCUMENTS("Документы"),
    HISTORY("История"),
    DISCUSSION("Обсуждение")
}

data class ProjectCreateForm(
    val name: String = "",
    val fullName: String = "",
    val description: String = "",
    val projectType: ProjectType? = null,
    val customer: String = "",
    val ownerId: Long? = null,
    val departmentId: Long? = null,
    val priority: ProjectPriority = ProjectPriority.NORMAL,
    val plannedStartAt: String = "",
    val plannedEndAt: String = ""
)

data class OpenProjectForm(
    val openingReason: String = ""
)
