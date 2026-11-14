package org.example.project.service

import org.example.project.exception.ConflictException
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.generated.jooq.tables.records.DemandsRecord
import org.example.project.model.CurrentUser
import org.example.project.repository.DemandRepository
import org.example.project.repository.ProjectRepository
import java.time.OffsetDateTime

/**
 * Реальный адаптер цели согласования для потребности (DEMAND).
 * Завершение маршрута переводит потребность PENDING_APPROVAL -> APPROVED
 * тем же атомарным переходом с версией, что и прямое утверждение
 * (demands:approve), без обхода состояния объекта.
 */
class DemandApprovalTarget(
    private val demandRepository: DemandRepository,
    private val projectRepository: ProjectRepository,
    private val auditService: AuditService
) : ApprovalTargetPort {

    override val targetType: String = APPROVAL_TARGET_DEMAND

    override fun ensureStartAllowed(targetId: Long, currentUser: CurrentUser): Long {
        val demand = demandRepository.findById(targetId) ?: throw NotFoundException("demand", targetId)
        ensureAccess(demand, currentUser)
        if (demand.status != "PENDING_APPROVAL") {
            throw ConflictException(
                "Approval route can only start for a PENDING_APPROVAL demand; submit the demand first"
            )
        }
        return demand.version ?: 0
    }

    override fun ensureReadAllowed(targetId: Long, currentUser: CurrentUser) {
        val demand = demandRepository.findById(targetId) ?: throw NotFoundException("demand", targetId)
        ensureAccess(demand, currentUser)
    }

    override fun currentTargetVersion(targetId: Long): Long {
        val demand = demandRepository.findById(targetId) ?: throw NotFoundException("demand", targetId)
        return demand.version ?: 0
    }

    override fun completeApproval(targetId: Long, expectedVersion: Long, decidedBy: Long) {
        val updated = demandRepository.transitionStatus(
            id = targetId,
            expectedVersion = expectedVersion,
            fromStatus = "PENDING_APPROVAL",
            newStatus = "APPROVED",
            approvedBy = decidedBy,
            approvedAt = OffsetDateTime.now()
        ) ?: throw ConflictException(
            "Demand changed since the approval route started; restart the approval"
        )
        demandRepository.insertDemandHistory(
            demandId = targetId,
            action = "APPROVED",
            fromStatus = "PENDING_APPROVAL",
            toStatus = "APPROVED",
            reason = "approval route",
            changedBy = decidedBy
        )
        auditService.log(
            decidedBy, "APPROVE", "DEMAND", targetId,
            mapOf("number" to updated.number, "via" to "approval_route")
        )
    }

    private fun ensureAccess(demand: DemandsRecord, currentUser: CurrentUser) {
        if (currentUser.hasPermission("demands", "read_any")) return
        if (demand.initiatorId == currentUser.userId || demand.createdBy == currentUser.userId) return
        val projectId = demand.projectId
        if (projectId != null && projectRepository.isMember(projectId, currentUser.userId)) return
        throw ForbiddenException("Access denied")
    }
}
