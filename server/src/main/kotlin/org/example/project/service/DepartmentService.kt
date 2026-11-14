package org.example.project.service

import org.example.project.dto.CreateDepartmentRequest
import org.example.project.dto.DepartmentDto
import org.example.project.dto.UpdateDepartmentRequest
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.repository.DepartmentRepository

class DepartmentService(
    private val departmentRepository: DepartmentRepository,
    private val auditService: AuditService
) {

    fun list(): List<DepartmentDto> {
        return departmentRepository.list().map { it.toDto() }
    }

    fun create(request: CreateDepartmentRequest, currentUser: CurrentUser): DepartmentDto {
        if (request.code.isBlank()) throw ValidationException("Code is required")
        if (request.name.isBlank()) throw ValidationException("Name is required")
        if (departmentRepository.existsByCode(request.code)) {
            throw ValidationException("Department code already exists", mapOf("code" to "already_exists"))
        }
        val dept = departmentRepository.create(
            code = request.code,
            name = request.name,
            parentId = request.parentId,
            headId = request.headId
        )
        auditService.log(currentUser.userId, "CREATE", "DEPARTMENT", dept.id, mapOf("code" to dept.code))
        return dept.toDto()
    }

    fun get(id: Long): DepartmentDto {
        val dept = departmentRepository.findById(id) ?: throw NotFoundException("department", id)
        return dept.toDto()
    }

    fun update(id: Long, request: UpdateDepartmentRequest, currentUser: CurrentUser): DepartmentDto {
        val dept = departmentRepository.update(
            id = id,
            name = request.name,
            parentId = request.parentId,
            headId = request.headId
        )
        auditService.log(currentUser.userId, "UPDATE", "DEPARTMENT", id, mapOf("code" to dept.code))
        return dept.toDto()
    }
}
