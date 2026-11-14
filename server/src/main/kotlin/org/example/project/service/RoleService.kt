package org.example.project.service

import org.example.project.dto.CreateRoleRequest
import org.example.project.dto.RoleDto
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.repository.PermissionRepository
import org.example.project.repository.RoleRepository

class RoleService(
    private val roleRepository: RoleRepository,
    private val permissionRepository: PermissionRepository,
    private val auditService: AuditService
) {

    fun list(): List<RoleDto> {
        return roleRepository.list().map { it.toDto() }
    }

    fun create(request: CreateRoleRequest, currentUser: CurrentUser): RoleDto {
        if (request.code.isBlank()) throw ValidationException("Code is required")
        if (request.name.isBlank()) throw ValidationException("Name is required")
        if (roleRepository.existsByCode(request.code)) {
            throw ValidationException("Role code already exists", mapOf("code" to "already_exists"))
        }
        val role = roleRepository.create(request.code, request.name, request.description)
        request.permissions.forEach { perm ->
            permissionRepository.create(role.id, perm.resource, perm.action, perm.scope)
        }
        auditService.log(currentUser.userId, "CREATE", "ROLE", role.id, mapOf("code" to role.code))
        return role.toDto()
    }

    fun get(id: Long): RoleDto {
        val role = roleRepository.findById(id) ?: throw NotFoundException("role", id)
        return role.toDto()
    }
}
