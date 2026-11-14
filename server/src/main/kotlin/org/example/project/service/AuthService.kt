package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.TooManyRequestsException
import org.example.project.exception.UnauthorizedException
import org.example.project.exception.ValidationException
import org.example.project.repository.*

class AuthService(
    private val userRepository: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val sessionService: SessionService,
    private val auditService: AuditService,
    private val roleRepository: RoleRepository,
    private val permissionRepository: PermissionRepository,
    private val userRolesRepository: UserRolesRepository,
    private val loginThrottleService: LoginThrottleService
) {

    fun bootstrap(request: BootstrapRequest): LoginResponse {
        if (request.login.isBlank()) throw ValidationException("Login is required")
        if (request.fullName.isBlank()) throw ValidationException("Full name is required")
        validatePassword(request.password)
        val passwordHash = passwordHasher.hash(request.password)

        val user = try {
            userRepository.transaction { ctx ->
                ctx.execute("SELECT pg_advisory_xact_lock(?)", BOOTSTRAP_LOCK_ID)

                if (UserRepository(ctx).count() > 0) {
                    throw UnauthorizedException("Bootstrap is only allowed when no users exist")
                }

                val createdUser = UserRepository(ctx).create(
                    login = request.login,
                    passwordHash = passwordHash,
                    fullName = request.fullName,
                    email = null,
                    departmentId = null,
                    active = true,
                    createdBy = null
                )

                val adminRole = RoleRepository(ctx).create(
                    code = "ADMIN",
                    name = "Администратор",
                    description = "Первоначальная административная роль"
                )
                PermissionRepository(ctx).create(adminRole.id, "*", "*", null)
                UserRolesRepository(ctx).setRoles(createdUser.id, listOf(adminRole.id))

                createdUser
            }
        } catch (e: UnauthorizedException) {
            auditService.log(null, "BOOTSTRAP_DENIED", "USER", 0, mapOf("reason" to "users_already_exist"))
            throw e
        }

        auditService.log(user.id, "BOOTSTRAP", "USER", user.id, mapOf("login" to user.login))
        val permissions = permissionRepository.listByUserId(user.id)
            .map { "${it.resource}:${it.action}" }
        return LoginResponse(user.toDto().copy(permissions = permissions))
    }

    fun login(request: LoginRequest, remoteIp: String): Pair<org.example.project.model.CurrentUser, java.util.UUID> {
        if (request.login.isBlank() || request.password.isBlank()) {
            throw ValidationException("Login and password are required")
        }
        val normalizedLogin = request.login.trim().lowercase()

        val (blocked, retryAfter) = loginThrottleService.isBlocked(normalizedLogin, remoteIp)
        if (blocked) {
            throw TooManyRequestsException("Too many login attempts", retryAfter)
        }

        val user = userRepository.findByLogin(request.login.trim())
        if (user == null) {
            loginThrottleService.recordFailure(normalizedLogin, remoteIp)
            auditService.log(null, "LOGIN_FAILED", "USER", 0, mapOf("reason" to "unknown_login"))
            throw UnauthorizedException("Invalid login or password")
        }
        if (!passwordHasher.verify(request.password, user.passwordHash)) {
            loginThrottleService.recordFailure(normalizedLogin, remoteIp)
            auditService.log(user.id, "LOGIN_FAILED", "USER", user.id, mapOf("reason" to "invalid_password"))
            throw UnauthorizedException("Invalid login or password")
        }
        if (user.active != true) {
            auditService.log(user.id, "LOGIN_FAILED", "USER", user.id, mapOf("reason" to "account_disabled"))
            throw UnauthorizedException("Invalid login or password")
        }

        loginThrottleService.clearFailures(normalizedLogin, remoteIp)
        val token = sessionService.createSession(user.id)
        auditService.log(user.id, "LOGIN", "USER", user.id, mapOf("login" to user.login))
        val current = sessionService.validateSession(token)!!
        return current to token
    }

    fun logout(userId: Long, token: java.util.UUID) {
        sessionService.invalidateSession(token)
        auditService.log(userId, "LOGOUT", "USER", userId, emptyMap())
    }

    fun me(userId: Long): MeResponse {
        val user = userRepository.findById(userId) ?: throw UnauthorizedException("Session user not found")
        val permissions = permissionRepository.listByUserId(userId)
            .map { "${it.resource}:${it.action}" }
        return MeResponse(user.toDto().copy(permissions = permissions))
    }

    fun changePassword(userId: Long, request: ChangePasswordRequest): UserDto {
        val user = userRepository.findById(userId) ?: throw UnauthorizedException("Session user not found")
        if (!passwordHasher.verify(request.currentPassword, user.passwordHash)) {
            auditService.log(user.id, "PASSWORD_CHANGE", "USER", user.id, mapOf("reason" to "invalid_current_password"))
            throw UnauthorizedException("Invalid login or password")
        }
        validatePassword(request.newPassword)
        val updated = userRepository.updatePassword(
            id = user.id,
            passwordHash = passwordHasher.hash(request.newPassword),
            expectedVersion = user.version ?: 0
        )
        auditService.log(user.id, "PASSWORD_CHANGE", "USER", user.id, mapOf("login" to user.login))
        sessionService.invalidateUserSessions(user.id)
        return updated.toDto()
    }

    private fun validatePassword(password: String) {
        if (password.length < 6) {
            throw ValidationException("Password must be at least 6 characters long")
        }
    }

    companion object {
        private const val BOOTSTRAP_LOCK_ID = 1L
    }
}
