package org.example.project.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.ProjectRepository
import org.example.project.data.TaskRepository
import org.example.project.data.UserRepository
import org.example.project.dto.AddProjectMemberRequest
import org.example.project.dto.CloseProjectRequest
import org.example.project.dto.CreateProjectRequest
import org.example.project.dto.OpenProjectRequest
import org.example.project.dto.ProjectDto
import org.example.project.dto.ProjectPriority
import org.example.project.dto.ProjectType
import org.example.project.dto.TaskDto
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
            detailLoading = true,
            openError = null,
            memberError = null,
            detailTab = ProjectDetailTab.OVERVIEW
        )
        loadProjectTasks(project.id)
        viewModelScope.launch {
            refreshSelectedProject(project.id)
        }
    }

    /**
     * Открытие проекта из глобального поиска: id приходит без загруженного DTO,
     * поэтому карточка запрашивается у сервера — права проверяются повторно.
     */
    fun openProjectById(projectId: Long) {
        _uiState.value = _uiState.value.copy(
            detailLoading = true,
            openError = null,
            memberError = null,
            detailTab = ProjectDetailTab.OVERVIEW
        )
        loadProjectTasks(projectId)
        viewModelScope.launch {
            when (val result = projectRepository.get(projectId)) {
                is ApiResult.Success -> if (_uiState.value.projectTasksProjectId == projectId) {
                    _uiState.value = _uiState.value.copy(
                        selectedProject = result.data,
                        detailLoading = false
                    )
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
            detailTab = ProjectDetailTab.OVERVIEW,
            projectTasks = emptyList(),
            projectTasksProjectId = null,
            projectTasksLoading = false,
            projectTasksError = null
        )
    }

    fun selectDetailTab(tab: ProjectDetailTab) {
        _uiState.value = _uiState.value.copy(detailTab = tab)
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
                        createError = null,
                        selectedProject = result.data,
                        detailTab = ProjectDetailTab.OVERVIEW
                    )
                    loadProjectTasks(result.data.id)
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
                        openForm = OpenProjectForm()
                    )
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
                        closeError = null
                    )
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
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(selectedProject = result.data)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(memberError = result.error)
            }
        }
    }

    fun removeMember(projectId: Long, userId: Long) {
        _uiState.value = _uiState.value.copy(memberError = null)
        viewModelScope.launch {
            when (val result = projectRepository.removeMember(projectId, userId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(selectedProject = result.data)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(memberError = result.error)
            }
        }
    }

    fun refreshSelectedProject(projectId: Long) {
        viewModelScope.launch {
            when (val result = projectRepository.get(projectId)) {
                is ApiResult.Success -> if (_uiState.value.selectedProject?.id == projectId) {
                    _uiState.value = _uiState.value.copy(
                        selectedProject = result.data,
                        detailLoading = false
                    )
                }
                is ApiResult.Error -> if (_uiState.value.selectedProject?.id == projectId) {
                    _uiState.value = _uiState.value.copy(
                        detailLoading = false,
                        openError = result.error
                    )
                }
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
    val detailTab: ProjectDetailTab = ProjectDetailTab.OVERVIEW,
    val projectTasks: List<TaskDto> = emptyList(),
    val projectTasksProjectId: Long? = null,
    val projectTasksLoading: Boolean = false,
    val projectTasksError: org.example.project.dto.ApiError? = null
)

enum class ProjectDetailTab(val label: String) {
    OVERVIEW("Обзор"),
    TASKS("Задачи"),
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
