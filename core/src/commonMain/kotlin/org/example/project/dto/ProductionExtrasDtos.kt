package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
data class ProductionOrderMaterialDto(
    val id: Long,
    val productionOrderId: Long,
    val nomenclatureId: Long,
    val nomenclatureCode: String,
    val nomenclatureName: String,
    val plannedQty: String,
    val allocatedQty: String,
    val consumedQty: String,
    val unit: String,
    val warehouseId: Long? = null,
    val reservationId: Long? = null,
    val issueOrderId: Long? = null,
    val version: Long = 0
)

@Serializable
data class AllocateProductionMaterialRequest(
    val warehouseId: Long,
    val quantity: String,
    val version: Long
)

@Serializable
data class ConsumeProductionMaterialRequest(
    val quantity: String,
    val instanceId: Long? = null,
    val lotNumber: String? = null,
    val serialNumber: String? = null,
    val version: Long
)

@Serializable
enum class EngineeringChangeDecision {
    PENDING,
    RETURN_EXCESS,
    RESERVE_ADDITIONAL,
    REPLACE_ON_FLOOR,
    NO_CHANGE
}

@Serializable
enum class EngineeringChangeStatus {
    OPEN,
    ASSESSED,
    APPROVED,
    APPLIED,
    REJECTED
}

@Serializable
data class EngineeringChangeImpactDto(
    val id: Long,
    val productionOrderId: Long,
    val oldBomRevisionId: Long,
    val newBomRevisionId: Long,
    val impactSummary: String,
    val materialDecision: EngineeringChangeDecision,
    val decisionComment: String? = null,
    val status: EngineeringChangeStatus,
    val createdBy: Long,
    val createdAt: String? = null,
    val approvedBy: Long? = null,
    val approvedAt: String? = null,
    val version: Long = 0
)

@Serializable
data class CreateEngineeringChangeRequest(
    val newBomRevisionId: Long,
    val impactSummary: String
)

@Serializable
data class DecideEngineeringChangeRequest(
    val materialDecision: EngineeringChangeDecision,
    val status: EngineeringChangeStatus,
    val decisionComment: String? = null,
    val version: Long
)

@Serializable
enum class NonconformitySeverity {
    MINOR,
    MAJOR,
    CRITICAL
}

@Serializable
enum class NonconformityStatus {
    REGISTERED,
    EVALUATION,
    ASSIGNED,
    ROOT_CAUSE_ANALYSIS,
    CORRECTIVE_ACTION,
    EFFECTIVENESS_CHECK,
    CLOSED
}

@Serializable
data class NonconformityDto(
    val id: Long,
    val number: String,
    val category: String,
    val objectType: String,
    val objectId: Long,
    val description: String,
    val severity: NonconformitySeverity,
    val status: NonconformityStatus,
    val temporaryMeasures: String? = null,
    val responsibleId: Long? = null,
    val createdBy: Long,
    val createdAt: String? = null,
    val version: Long = 0
)

@Serializable
data class CreateNonconformityRequest(
    val category: String,
    val objectType: String = "PRODUCTION",
    val objectId: Long,
    val description: String,
    val severity: NonconformitySeverity = NonconformitySeverity.MINOR,
    val temporaryMeasures: String? = null
)

@Serializable
data class TransitionNonconformityRequest(
    val targetStatus: NonconformityStatus,
    val reason: String? = null,
    val version: Long
)
