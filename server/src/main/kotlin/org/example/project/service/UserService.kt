package org.example.project.service

import org.example.project.dto.CreateUserRequest
import org.example.project.dto.UpdateUserRequest
import org.example.project.dto.UserDto
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.repository.UserRepository
import org.example.project.repository.UserRolesRepository

class UserService(
    private val userRepository: UserRepository,
    private val userRolesRepository: UserRolesRepository,
    private val passwordHasher: PasswordHasher,
    private val auditService: AuditService,
    private val sessionService: SessionService
) {

    fun list(): List<UserDto> {
        return userRepository.list().map { it.toDto() }
    }

    fun create(request: CreateUserRequest, createdBy: CurrentUser): UserDto {
        if (request.login.isBlank()) throw ValidationException("Login is required")
        if (request.fullName.isBlank()) throw ValidationException("Full name is required")
        if (request.password.length < 6) throw ValidationException("Password must be at least 6 characters long")
        if (userRepository.existsByLogin(request.login.trim())) {
            throw ValidationException("Login already exists", mapOf("login" to "already_exists"))
        }
        val user = userRepository.create(
            login = request.login,
            passwordHash = passwordHasher.hash(request.password),
            fullName = request.fullName,
            email = request.email,
            departmentId = request.departmentId,
            active = true,
            createdBy = createdBy.userId
        )
        userRolesRepository.setRoles(user.id, request.roleIds)
        auditService.log(createdBy.userId, "CREATE", "USER", user.id, mapOf("login" to user.login))
        return user.toDto()
    }

    fun get(id: Long, currentUser: CurrentUser): UserDto {
        if (currentUser.userId != id && !currentUser.hasPermission("users", "read_any")) {
            throw ForbiddenException("Cannot access another user's record")
        }
        val user = userRepository.findById(id) ?: throw NotFoundException("user", id)
        return user.toDto()
    }

    fun update(id: Long, request: UpdateUserRequest, currentUser: CurrentUser): UserDto {
        if (currentUser.userId != id && !currentUser.hasPermission("users", "update_any")) {
            throw ForbiddenException("Cannot update another user's record")
        }
        val previous = userRepository.findById(id) ?: throw NotFoundException("user", id)
        val user = userRepository.update(
            id = id,
            fullName = request.fullName,
            email = request.email,
            departmentId = request.departmentId,
            expectedVersion = request.version
        )
        val roleIds = request.roleIds
        if (roleIds != null) {
            userRolesRepository.setRoles(id, roleIds)
        }
        if (roleIds != null || previous.active != user.active) {
            sessionService.invalidateUserSessions(id)
        }
        auditService.log(currentUser.userId, "UPDATE", "USER", id, mapOf("login" to user.login))
        return user.toDto()
    }

    fun setActive(id: Long, active: Boolean, version: Long, currentUser: CurrentUser): UserDto {
        if (currentUser.userId == id) {
            throw ForbiddenException("Cannot change own active status")
        }
        currentUser.requirePermission("users", "activate")
        val user = userRepository.setActive(id, active, version)
        sessionService.invalidateUserSessions(id)
        auditService.log(currentUser.userId, if (active) "ACTIVATE" else "DEACTIVATE", "USER", id, mapOf("login" to user.login))
        return user.toDto()
    }
}
