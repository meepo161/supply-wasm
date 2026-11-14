package org.example.project.service

import org.example.project.config.AppConfig
import org.example.project.model.CurrentUser
import org.example.project.repository.PermissionRepository
import org.example.project.repository.SessionRepository
import org.example.project.repository.UserRepository
import java.time.OffsetDateTime
import java.util.*

class SessionService(
    private val sessionRepository: SessionRepository,
    private val userRepository: UserRepository,
    private val permissionRepository: PermissionRepository,
    private val config: AppConfig
) {

    fun createSession(userId: Long): UUID {
        val expiresAt = OffsetDateTime.now().plusMinutes(config.sessionTtlMinutes)
        return sessionRepository.create(userId, expiresAt)
    }

    fun validateSession(token: UUID): CurrentUser? {
        val session = sessionRepository.findValid(token) ?: return null
        val user = userRepository.findById(session.userId) ?: return null
        if (user.active != true) return null
        val permissions = permissionRepository.listByUserId(user.id)
            .map { "${it.resource}:${it.action}" }
            .toSet()
        return CurrentUser(
            userId = user.id,
            login = user.login,
            fullName = user.fullName,
            permissions = permissions
        )
    }

    fun invalidateSession(token: UUID) {
        sessionRepository.delete(token)
    }

    fun invalidateUserSessions(userId: Long) {
        sessionRepository.deleteByUserId(userId)
    }
}
