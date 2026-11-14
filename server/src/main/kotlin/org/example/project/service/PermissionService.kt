package org.example.project.service

import org.example.project.dto.CreatePermissionRequest
import org.example.project.dto.PermissionDto
import org.example.project.exception.NotFoundException
import org.example.project.model.CurrentUser
import org.example.project.repository.PermissionRepository
import org.example.project.repository.RoleRepository

class PermissionService(
    private val permissionRepository: PermissionRepository,
    private val roleRepository: RoleRepository,
    private val auditService: AuditService
) {

    fun list(): List<PermissionDto> {
        return permissionRepository.listAll().map { it.toDto() }
    }

    fun create(roleId: Long, request: CreatePermissionRequest, currentUser: CurrentUser): PermissionDto {
        if (roleRepository.findById(roleId) == null) {
            throw NotFoundException("role", roleId)
        }
        val perm = permissionRepository.create(roleId, request.resource, request.action, request.scope)
        auditService.log(currentUser.userId, "CREATE", "PERMISSION", perm.id, mapOf("roleId" to roleId, "resource" to perm.resource, "action" to perm.action))
        return perm.toDto()
    }

    fun delete(id: Long, currentUser: CurrentUser) {
        val perm = permissionRepository.findById(id) ?: throw NotFoundException("permission", id)
        permissionRepository.delete(id)
        auditService.log(currentUser.userId, "DELETE", "PERMISSION", id, mapOf("roleId" to perm.roleId, "resource" to perm.resource, "action" to perm.action))
    }
}
