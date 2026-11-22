package org.example.project.service

import org.example.project.dto.AuditLogEntryDto
import org.example.project.model.CurrentUser
import org.example.project.repository.AuditRepository

class AuditService(private val auditRepository: AuditRepository) {

    fun list(
        entityType: String?,
        action: String?,
        userId: Long?,
        limit: Int,
        offset: Int,
        currentUser: CurrentUser
    ): List<AuditLogEntryDto> {
        currentUser.requirePermission("audit", "read")
        return auditRepository.list(entityType, action, userId, limit, offset)
    }

    fun log(
        userId: Long?,
        action: String,
        entityType: String,
        entityId: Long,
        payload: Map<String, Any?> = emptyMap()
    ) {
        auditRepository.log(userId, action, entityType, entityId, payload)
    }
}
