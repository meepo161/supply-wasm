package org.example.project.data

import org.example.project.dto.AdminResetPasswordRequest
import org.example.project.dto.AuditLogEntryDto
import org.example.project.dto.CreateDepartmentRequest
import org.example.project.dto.CreateRoleRequest
import org.example.project.dto.CreateUserRequest
import org.example.project.dto.DepartmentDto
import org.example.project.dto.PermissionDto
import org.example.project.dto.RoleDto
import org.example.project.dto.UpdateDepartmentRequest
import org.example.project.dto.UpdateUserRequest
import org.example.project.dto.UserDto

interface AdminRepository {
    suspend fun listUsers(): ApiResult<List<UserDto>>
    suspend fun createUser(request: CreateUserRequest): ApiResult<UserDto>
    suspend fun updateUser(id: Long, request: UpdateUserRequest): ApiResult<UserDto>
    suspend fun setUserActive(id: Long, active: Boolean, version: Long): ApiResult<UserDto>
    suspend fun resetUserPassword(id: Long, request: AdminResetPasswordRequest): ApiResult<UserDto>
    suspend fun listDepartments(): ApiResult<List<DepartmentDto>>
    suspend fun createDepartment(request: CreateDepartmentRequest): ApiResult<DepartmentDto>
    suspend fun updateDepartment(id: Long, request: UpdateDepartmentRequest): ApiResult<DepartmentDto>
    suspend fun listRoles(): ApiResult<List<RoleDto>>
    suspend fun createRole(request: CreateRoleRequest): ApiResult<RoleDto>
    suspend fun listPermissions(): ApiResult<List<PermissionDto>>
    suspend fun createPermission(
        roleId: Long,
        resource: String,
        action: String,
        scope: String?
    ): ApiResult<PermissionDto>
    suspend fun deletePermission(id: Long): ApiResult<Unit>
    suspend fun listAuditLogs(
        entityType: String? = null,
        action: String? = null,
        limit: Int = 100
    ): ApiResult<List<AuditLogEntryDto>>
}
