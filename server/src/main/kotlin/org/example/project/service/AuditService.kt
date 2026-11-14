package org.example.project.service

import org.example.project.repository.AuditRepository

class AuditService(private val auditRepository: AuditRepository) {

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
