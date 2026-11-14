package org.example.project.ui.approvals

import org.example.project.dto.ApprovalAssigneeType
import org.example.project.dto.ApprovalDecision
import org.example.project.dto.ApprovalDecisionPolicy
import org.example.project.dto.ApprovalRouteDto
import org.example.project.dto.ApprovalRouteStatus
import org.example.project.dto.ApprovalStageDto
import org.example.project.dto.ApprovalStageStatus

fun ApprovalRouteStatus.localizedName(): String = when (this) {
    ApprovalRouteStatus.ACTIVE -> "Активен"
    ApprovalRouteStatus.COMPLETED -> "Завершён"
    ApprovalRouteStatus.REJECTED -> "Отклонён"
    ApprovalRouteStatus.RETURNED -> "Возвращён"
    ApprovalRouteStatus.CANCELLED -> "Отменён"
}

fun ApprovalStageStatus.localizedName(): String = when (this) {
    ApprovalStageStatus.PENDING -> "Ожидает"
    ApprovalStageStatus.ACTIVE -> "Активен"
    ApprovalStageStatus.APPROVED -> "Согласован"
    ApprovalStageStatus.REJECTED -> "Отклонён"
    ApprovalStageStatus.RETURNED -> "Возвращён"
    ApprovalStageStatus.CANCELLED -> "Отменён"
}

fun ApprovalDecisionPolicy.localizedName(): String = when (this) {
    ApprovalDecisionPolicy.ALL -> "Все согласующие"
    ApprovalDecisionPolicy.ANY -> "Любой согласующий"
}

fun ApprovalDecision.localizedName(): String = when (this) {
    ApprovalDecision.APPROVE -> "Согласовано"
    ApprovalDecision.REJECT -> "Отклонено"
    ApprovalDecision.RETURN -> "Возвращено"
}

fun ApprovalDecision.requiresComment(): Boolean = this != ApprovalDecision.APPROVE

fun ApprovalRouteStatus.isTerminal(): Boolean = this != ApprovalRouteStatus.ACTIVE

fun List<ApprovalRouteDto>.activeRoute(): ApprovalRouteDto? =
    firstOrNull { it.status == ApprovalRouteStatus.ACTIVE }

fun ApprovalRouteDto.currentStage(): ApprovalStageDto? =
    stages.firstOrNull { it.stageNo == currentStageNo }

/**
 * Клиентская предпроверка возможности решения по текущему этапу. Для согласующих
 * типа ROLE членство в роли на клиенте неизвестно, поэтому кнопка показывается
 * оптимистично, а сервер остаётся источником истины (403 при отсутствии роли).
 */
fun canUserDecideCurrentStage(route: ApprovalRouteDto, userId: Long): Boolean {
    if (route.status != ApprovalRouteStatus.ACTIVE) return false
    val stage = route.currentStage() ?: return false
    if (stage.status != ApprovalStageStatus.ACTIVE) return false
    if (route.decisions.any { it.stageId == stage.id && it.decidedBy == userId }) return false
    return stage.assignees.any { assignee ->
        (assignee.type == ApprovalAssigneeType.USER && assignee.userId == userId) ||
            assignee.type == ApprovalAssigneeType.ROLE
    }
}
