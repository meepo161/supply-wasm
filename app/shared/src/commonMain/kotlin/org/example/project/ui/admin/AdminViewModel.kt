package org.example.project.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.AdminRepository
import org.example.project.data.ApiResult
import org.example.project.dto.ApiError
import org.example.project.dto.CreateDepartmentRequest
import org.example.project.dto.CreatePermissionRequest
import org.example.project.dto.CreateRoleRequest
import org.example.project.dto.CreateUserRequest
import org.example.project.dto.UpdateDepartmentRequest
import org.example.project.dto.UpdateUserRequest
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission

class AdminViewModel(
    private val adminRepository: AdminRepository,
    private val currentUser: UserDto
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUiState())
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    init {
        loadAll()
    }

    fun can(resource: String, action: String): Boolean =
        currentUser.hasPermission(resource, action)

    fun isSelf(userId: Long): Boolean = currentUser.id == userId

    fun selectTab(tab: AdminTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab, operationError = null)
    }

    fun selectUser(id: Long) {
        _uiState.value = _uiState.value.copy(selectedUserId = id)
    }

    fun selectDepartment(id: Long) {
        _uiState.value = _uiState.value.copy(selectedDepartmentId = id)
    }

    fun selectRole(id: Long) {
        _uiState.value = _uiState.value.copy(selectedRoleId = id)
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(operationError = null)
    }

    fun showDialog(dialog: AdminDialog) {
        val state = _uiState.value
        _uiState.value = when (dialog) {
            AdminDialog.EditUser -> {
                val user = state.users.find { it.id == state.selectedUserId } ?: return
                state.copy(
                    activeDialog = dialog,
                    operationError = null,
                    editUserForm = EditUserForm(
                        id = user.id,
                        version = user.version,
                        fullName = user.fullName,
                        email = user.email ?: "",
                        departmentId = user.departmentId
                    )
                )
            }
            AdminDialog.EditDepartment -> {
                val department = state.departments.find { it.id == state.selectedDepartmentId } ?: return
                state.copy(
                    activeDialog = dialog,
                    operationError = null,
                    departmentForm = DepartmentForm(
                        id = department.id,
                        code = department.code,
                        name = department.name,
                        parentId = department.parentId,
                        headId = department.headId
                    )
                )
            }
            else -> state.copy(activeDialog = dialog, operationError = null)
        }
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(activeDialog = null, operationError = null)
    }

    fun loadAll() {
        loadUsers()
        loadDepartments()
        loadRoles()
        loadPermissions()
    }

    fun loadUsers() {
        if (!can("users", "read_any")) return
        viewModelScope.launch {
            when (val result = adminRepository.listUsers()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(users = result.data)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun loadDepartments() {
        if (!can("departments", "read")) return
        viewModelScope.launch {
            when (val result = adminRepository.listDepartments()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(departments = result.data)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun loadRoles() {
        if (!can("roles", "read")) return
        viewModelScope.launch {
            when (val result = adminRepository.listRoles()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(roles = result.data)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun loadPermissions() {
        if (!can("permissions", "read")) return
        viewModelScope.launch {
            when (val result = adminRepository.listPermissions()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(permissions = result.data)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun createUser() {
        if (!can("users", "create")) return
        val form = _uiState.value.createUserForm
        when {
            form.login.isBlank() -> return setValidationError("Заполните логин")
            form.password.length < 6 -> return setValidationError("Пароль должен быть не короче 6 символов")
            form.fullName.isBlank() -> return setValidationError("Заполните ФИО")
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            val request = CreateUserRequest(
                login = form.login.trim(),
                password = form.password,
                fullName = form.fullName.trim(),
                email = form.email.takeIf { it.isNotBlank() }?.trim(),
                departmentId = form.departmentId,
                roleIds = form.roleIds.toList()
            )
            when (val result = adminRepository.createUser(request)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createUserForm = CreateUserForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadUsers()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateUser() {
        if (!can("users", "update_any")) return
        val form = _uiState.value.editUserForm
        if (form.fullName.isBlank()) {
            return setValidationError("ФИО не должно быть пустым")
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            val request = UpdateUserRequest(
                fullName = form.fullName.trim(),
                email = form.email.takeIf { it.isNotBlank() }?.trim(),
                departmentId = form.departmentId,
                roleIds = if (form.changeRoles) form.roleIds.toList() else null,
                version = form.version
            )
            when (val result = adminRepository.updateUser(form.id, request)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(activeDialog = null, isLoading = false)
                    loadUsers()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        operationError = result.error
                    )
                    if (result.statusCode == 409) {
                        loadUsers()
                        dismissDialog()
                    }
                }
            }
        }
    }

    fun setUserActive(user: UserDto, active: Boolean) {
        if (!can("users", "activate")) return
        if (isSelf(user.id)) return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = adminRepository.setUserActive(user.id, active, user.version)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    loadUsers()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        operationError = result.error
                    )
                    if (result.statusCode == 409) loadUsers()
                }
            }
        }
    }

    fun saveDepartment() {
        val form = _uiState.value.departmentForm
        if (form.id == null) {
            createDepartment(form)
        } else {
            updateDepartment(form)
        }
    }

    private fun createDepartment(form: DepartmentForm) {
        if (!can("departments", "create")) return
        if (form.code.isBlank() || form.name.isBlank()) {
            return setValidationError("Заполните код и название подразделения")
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            val request = CreateDepartmentRequest(
                code = form.code.trim(),
                name = form.name.trim(),
                parentId = form.parentId,
                headId = form.headId
            )
            when (val result = adminRepository.createDepartment(request)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        departmentForm = DepartmentForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadDepartments()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    private fun updateDepartment(form: DepartmentForm) {
        if (!can("departments", "update")) return
        val id = form.id ?: return
        if (form.name.isBlank()) {
            return setValidationError("Название подразделения не должно быть пустым")
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            val request = UpdateDepartmentRequest(
                name = form.name.trim(),
                parentId = form.parentId,
                headId = form.headId
            )
            when (val result = adminRepository.updateDepartment(id, request)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(activeDialog = null, isLoading = false)
                    loadDepartments()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun createRole() {
        if (!can("roles", "create")) return
        val form = _uiState.value.createRoleForm
        if (form.code.isBlank() || form.name.isBlank()) {
            return setValidationError("Заполните код и название роли")
        }
        val permissions = form.permissions.filter { it.resource.isNotBlank() && it.action.isNotBlank() }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            val request = CreateRoleRequest(
                code = form.code.trim(),
                name = form.name.trim(),
                description = form.description.takeIf { it.isNotBlank() }?.trim(),
                permissions = permissions.map {
                    CreatePermissionRequest(
                        resource = it.resource.trim(),
                        action = it.action.trim(),
                        scope = it.scope.takeIf { scope -> scope.isNotBlank() }?.trim()
                    )
                }
            )
            when (val result = adminRepository.createRole(request)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createRoleForm = CreateRoleForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadRoles()
                    loadPermissions()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun addPermission() {
        if (!can("permissions", "create")) return
        val roleId = _uiState.value.selectedRoleId ?: return
        val form = _uiState.value.permissionForm
        if (form.resource.isBlank() || form.action.isBlank()) {
            return setValidationError("Заполните resource и action")
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = adminRepository.createPermission(
                roleId,
                form.resource.trim(),
                form.action.trim(),
                form.scope.takeIf { it.isNotBlank() }?.trim()
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        permissionForm = PermissionForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadPermissions()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun deletePermission(id: Long) {
        if (!can("permissions", "delete")) return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = adminRepository.deletePermission(id)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    loadPermissions()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateCreateUserForm(form: CreateUserForm) {
        _uiState.value = _uiState.value.copy(createUserForm = form)
    }

    fun updateEditUserForm(form: EditUserForm) {
        _uiState.value = _uiState.value.copy(editUserForm = form)
    }

    fun updateDepartmentForm(form: DepartmentForm) {
        _uiState.value = _uiState.value.copy(departmentForm = form)
    }

    fun updateCreateRoleForm(form: CreateRoleForm) {
        _uiState.value = _uiState.value.copy(createRoleForm = form)
    }

    fun updatePermissionForm(form: PermissionForm) {
        _uiState.value = _uiState.value.copy(permissionForm = form)
    }

    private fun setValidationError(message: String) {
        _uiState.value = _uiState.value.copy(
            operationError = ApiError("VALIDATION_ERROR", message)
        )
    }
}
