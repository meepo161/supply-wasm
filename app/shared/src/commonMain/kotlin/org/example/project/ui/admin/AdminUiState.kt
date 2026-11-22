package org.example.project.ui.admin

import org.example.project.dto.ApiError
import org.example.project.dto.AuditLogEntryDto
import org.example.project.dto.DepartmentDto
import org.example.project.dto.PermissionDto
import org.example.project.dto.RoleDto
import org.example.project.dto.UserDto

data class AdminUiState(
    val users: List<UserDto> = emptyList(),
    val departments: List<DepartmentDto> = emptyList(),
    val roles: List<RoleDto> = emptyList(),
    val permissions: List<PermissionDto> = emptyList(),
    val auditLogs: List<AuditLogEntryDto> = emptyList(),
    val auditEntityTypeFilter: String = "",
    val auditActionFilter: String = "",
    val selectedTab: AdminTab = AdminTab.USERS,
    val selectedUserId: Long? = null,
    val selectedDepartmentId: Long? = null,
    val selectedRoleId: Long? = null,
    val isLoading: Boolean = false,
    val operationError: ApiError? = null,
    val successMessage: String? = null,
    val activeDialog: AdminDialog? = null,
    val createUserForm: CreateUserForm = CreateUserForm(),
    val editUserForm: EditUserForm = EditUserForm(),
    val resetPasswordForm: ResetPasswordForm = ResetPasswordForm(),
    val departmentForm: DepartmentForm = DepartmentForm(),
    val createRoleForm: CreateRoleForm = CreateRoleForm(),
    val permissionForm: PermissionForm = PermissionForm()
)

enum class AdminTab(val label: String) {
    USERS("Пользователи"),
    DEPARTMENTS("Подразделения"),
    ROLES("Роли"),
    AUDIT("Журнал аудита")
}

sealed class AdminDialog {
    data object CreateUser : AdminDialog()
    data object EditUser : AdminDialog()
    data object ResetPassword : AdminDialog()
    data object CreateDepartment : AdminDialog()
    data object EditDepartment : AdminDialog()
    data object CreateRole : AdminDialog()
    data object AddPermission : AdminDialog()
}

data class CreateUserForm(
    val login: String = "",
    val password: String = "",
    val fullName: String = "",
    val email: String = "",
    val departmentId: Long? = null,
    val roleIds: Set<Long> = emptySet()
)

data class EditUserForm(
    val id: Long = 0,
    val version: Long = 0,
    val fullName: String = "",
    val email: String = "",
    val departmentId: Long? = null,
    val changeRoles: Boolean = false,
    val roleIds: Set<Long> = emptySet()
)

data class ResetPasswordForm(
    val userId: Long = 0,
    val version: Long = 0,
    val userName: String = "",
    val newPassword: String = "",
    val confirmPassword: String = ""
)

data class DepartmentForm(
    val id: Long? = null,
    val code: String = "",
    val name: String = "",
    val parentId: Long? = null,
    val headId: Long? = null
)

data class CreateRoleForm(
    val code: String = "",
    val name: String = "",
    val description: String = "",
    val permissions: List<PermissionForm> = emptyList()
)

data class PermissionForm(
    val resource: String = "",
    val action: String = "",
    val scope: String = ""
)
