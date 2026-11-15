package org.example.project.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.ProjectRepository
import org.example.project.data.UserRepository
import org.example.project.dto.AddProjectMemberRequest
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.DocumentDto
import org.example.project.dto.ProjectHistoryEntryDto
import org.example.project.dto.ProjectSummaryDto
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus
import org.example.project.dto.CloseProjectRequest
import org.example.project.dto.CreateProjectRequest
import org.example.project.dto.OpenProjectRequest
import org.example.project.dto.ProjectDto
import org.example.project.dto.ProjectPriority
import org.example.project.dto.ProjectType
import org.example.project.dto.UserDto

class ProjectViewModel(
    private val projectRepository: ProjectRepository,
    private val userRepository: UserRepository
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
            taskStatusFilter = null
        )
        viewModelScope.launch {
            refreshSelectedProject(project.id)
            loadTab(ProjectTab.OVERVIEW)
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
                    }
                    is ApiResult.Error -> {
                        if (_uiState.value.selectedProject?.id != id) return@launch
                        _uiState.value = _uiState.value.copy(tabLoading = false, tabError = result.error)
                    }
                }
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
            loadedTabs = emptySet()
        )
        viewModelScope.launch {
            when (val result = projectRepository.get(projectId)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedProject = result.data,
                        detailLoading = false
                    )
                    loadTab(ProjectTab.OVERVIEW, projectId)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    detailLoading = false,
                    error = result.error
                )
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
            loadedTabs = emptySet()
        )
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
    /** Вкладки, данные которых уже получены с сервера (включая пустые ответы). */
    val loadedTabs: Set<ProjectTab> = emptySet()
)

/**
 * Вкладки карточки проекта по M.4: обзор, задачи, потребности, документы,
 * история. Этапы, риски и проценты готовности в первую поставку не входят.
 */
enum class ProjectTab(val title: String) {
    OVERVIEW("Обзор"),
    TASKS("Задачи"),
    DEMANDS("Потребности"),
    DOCUMENTS("Документы"),
    HISTORY("История")
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
