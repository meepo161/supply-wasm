package org.example.project.service

import org.example.project.model.CurrentUser

const val APPROVAL_TARGET_WRITE_OFF = "WRITE_OFF"

/**
 * Адаптер согласования для списания сверх лимита (WMS-34).
 * Старт маршрута переводит черновик сверх лимита в PENDING_APPROVAL и
 * фиксирует версию; завершение маршрута переводит документ в APPROVED тем же
 * условным переходом по версии, что и прямой путь, поэтому конкурентное
 * изменение не перетирается.
 */
class WriteOffApprovalTarget(
    private val writeOffService: WmsWriteOffService
) : ApprovalTargetPort {

    override val targetType: String = APPROVAL_TARGET_WRITE_OFF

    override fun ensureStartAllowed(targetId: Long, currentUser: CurrentUser): Long =
        writeOffService.startApproval(targetId, currentUser)

    override fun ensureReadAllowed(targetId: Long, currentUser: CurrentUser) {
        writeOffService.readAllowed(targetId, currentUser)
    }

    override fun currentTargetVersion(targetId: Long): Long = writeOffService.currentVersion(targetId)

    override fun completeApproval(targetId: Long, expectedVersion: Long, decidedBy: Long) {
        writeOffService.applyApproval(targetId, expectedVersion, decidedBy)
    }
}
