package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class ApprovalDecisionPolicy {
    ALL,
    ANY
}

@Serializable
enum class ApprovalDecision {
    APPROVE,
    REJECT,
    RETURN
}

@Serializable
enum class ApprovalAssigneeType {
    USER,
    ROLE
}

@Serializable
enum class ApprovalRouteStatus {
    ACTIVE,
    COMPLETED,
    REJECTED,
    RETURNED,
    CANCELLED
}

@Serializable
enum class ApprovalStageStatus {
    PENDING,
    ACTIVE,
    APPROVED,
    REJECTED,
    RETURNED,
    CANCELLED
}

@Serializable
data class ApprovalAssigneeInput(
    val type: ApprovalAssigneeType,
    val userId: Long? = null,
    val roleId: Long? = null
)

// decisionPolicy намеренно без значения по умолчанию: сервер отклоняет этап
// без явно заданной политики, скрытого default нет.
@Serializable
data class ApprovalStageInput(
    val stageNo: Int,
    val name: String,
    val decisionPolicy: ApprovalDecisionPolicy? = null,
    val assignees: List<ApprovalAssigneeInput> = emptyList()
)

@Serializable
data class CreateApprovalTemplateRequest(
    val code: String,
    val name: String,
    val targetType: String,
    val stages: List<ApprovalStageInput> = emptyList()
)

@Serializable
data class ApprovalAssigneeDto(
    val id: Long,
    val type: ApprovalAssigneeType,
    val userId: Long? = null,
    val userFullName: String? = null,
    val roleId: Long? = null,
    val roleName: String? = null
)

@Serializable
data class ApprovalTemplateStageDto(
    val id: Long,
    val stageNo: Int,
    val name: String,
    val decisionPolicy: ApprovalDecisionPolicy,
    val assignees: List<ApprovalAssigneeDto> = emptyList()
)

@Serializable
data class ApprovalTemplateDto(
    val id: Long,
    val code: String,
    val name: String,
    val targetType: String,
    val active: Boolean = true,
    val stages: List<ApprovalTemplateStageDto> = emptyList(),
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0
)

@Serializable
data class StartApprovalRouteRequest(
    val templateId: Long,
    val targetType: String,
    val targetId: Long,
    val idempotencyKey: String? = null
)

@Serializable
data class ApprovalStageDto(
    val id: Long,
    val stageNo: Int,
    val name: String,
    val decisionPolicy: ApprovalDecisionPolicy,
    val status: ApprovalStageStatus,
    val assignees: List<ApprovalAssigneeDto> = emptyList(),
    val startedAt: String? = null,
    val completedAt: String? = null
)

@Serializable
data class ApprovalDecisionDto(
    val id: Long,
    val stageId: Long,
    val stageNo: Int,
    val decidedBy: Long,
    val decidedByFullName: String? = null,
    val decision: ApprovalDecision,
    val comment: String? = null,
    val createdAt: String? = null
)

@Serializable
data class ApprovalRouteDto(
    val id: Long,
    val templateId: Long,
    val targetType: String,
    val targetId: Long,
    val targetVersion: Long,
    val status: ApprovalRouteStatus,
    val currentStageNo: Int,
    val stages: List<ApprovalStageDto> = emptyList(),
    val decisions: List<ApprovalDecisionDto> = emptyList(),
    val startedBy: Long,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val version: Long = 0
)

@Serializable
data class SubmitApprovalDecisionRequest(
    val decision: ApprovalDecision,
    val comment: String? = null
)

@Serializable
data class CancelApprovalRouteRequest(
    val reason: String
)

@Serializable
data class AddApprovalAssigneeRequest(
    val type: ApprovalAssigneeType,
    val userId: Long? = null,
    val roleId: Long? = null
)
