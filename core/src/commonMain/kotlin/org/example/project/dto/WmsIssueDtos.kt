package org.example.project.dto

import kotlinx.serialization.Serializable

// Выдача и возврат WMS (8.8–8.9 ТЗ). Основания переиспользуют
// ReservationReasonType, количества — строками для точности NUMERIC(19,4).

@Serializable
enum class IssueStatus {
    DRAFT,
    ISSUED,
    RECEIVED,
    SHORTAGE,
    REFUSED,
    CANCELLED
}

@Serializable
enum class ReturnStatus {
    DRAFT,
    RECEIVED,
    INSPECTED
}

@Serializable
enum class ReturnInspection {
    PENDING,
    GOOD,
    DEFECT
}

@Serializable
enum class ReturnInspectionDecision {
    GOOD,
    DEFECT
}

@Serializable
data class IssueLineInput(
    val lineNo: Int,
    val nomenclatureId: Long,
    val requestedQuantity: String,
    val reservationId: Long? = null,
    val note: String? = null
)

@Serializable
data class CreateIssueOrderRequest(
    val warehouseId: Long,
    val reasonType: ReservationReasonType,
    val projectId: Long? = null,
    val taskId: Long? = null,
    val reasonNote: String? = null,
    val recipientId: Long? = null,
    val note: String? = null,
    val lines: List<IssueLineInput>,
    val idempotencyKey: String
)

@Serializable
data class IssuePickInput(
    val lineId: Long,
    val locationId: Long,
    val lotId: Long? = null,
    val serialId: Long? = null,
    val quantity: String
)

@Serializable
data class PickIssueRequest(
    val version: Long,
    val picks: List<IssuePickInput>,
    val idempotencyKey: String
)

@Serializable
data class IssueActionRequest(
    val version: Long,
    val idempotencyKey: String
)

@Serializable
data class RefuseIssueRequest(
    val version: Long,
    val reason: String,
    val idempotencyKey: String
)

@Serializable
data class IssuePickDto(
    val id: Long,
    val issueLineId: Long,
    val locationId: Long,
    val lotId: Long? = null,
    val serialId: Long? = null,
    val quantity: String,
    val pickedBy: Long,
    val createdAt: String? = null
)

@Serializable
data class IssueLineDto(
    val id: Long,
    val lineNo: Int,
    val nomenclatureId: Long,
    val requestedQuantity: String,
    val reservationId: Long? = null,
    val issuedQuantity: String = "0",
    val note: String? = null,
    val picks: List<IssuePickDto> = emptyList()
)

@Serializable
data class IssueOrderDto(
    val id: Long,
    val number: String,
    val warehouseId: Long,
    val reasonType: ReservationReasonType,
    val projectId: Long? = null,
    val taskId: Long? = null,
    val reasonNote: String? = null,
    val recipientId: Long,
    val status: IssueStatus = IssueStatus.DRAFT,
    val note: String? = null,
    val issuedAt: String? = null,
    val issuedBy: Long? = null,
    val confirmedAt: String? = null,
    val confirmedBy: Long? = null,
    val refusalReason: String? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val lines: List<IssueLineDto> = emptyList()
)

@Serializable
data class ReturnLineInput(
    val issueLineId: Long,
    val quantity: String,
    val lotId: Long? = null,
    val serialId: Long? = null
)

@Serializable
data class CreateReturnOrderRequest(
    val issueOrderId: Long,
    val quarantineLocationId: Long,
    val returnedById: Long? = null,
    val reason: String,
    val lines: List<ReturnLineInput>,
    val idempotencyKey: String
)

@Serializable
data class ReturnActionRequest(
    val version: Long,
    val idempotencyKey: String
)

@Serializable
data class InspectReturnLineRequest(
    val version: Long,
    val decision: ReturnInspectionDecision,
    val targetLocationId: Long? = null,
    val note: String? = null,
    val idempotencyKey: String
)

@Serializable
data class ReturnLineDto(
    val id: Long,
    val returnOrderId: Long,
    val issueLineId: Long,
    val quantity: String,
    val lotId: Long? = null,
    val serialId: Long? = null,
    val inspection: ReturnInspection = ReturnInspection.PENDING,
    val inspectedAt: String? = null,
    val inspectedBy: Long? = null,
    val targetLocationId: Long? = null,
    val inspectionNote: String? = null
)

@Serializable
data class ReturnOrderDto(
    val id: Long,
    val number: String,
    val issueOrderId: Long,
    val warehouseId: Long,
    val quarantineLocationId: Long,
    val status: ReturnStatus = ReturnStatus.DRAFT,
    val returnedBy: Long,
    val reason: String,
    val receivedAt: String? = null,
    val receivedBy: Long? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val lines: List<ReturnLineDto> = emptyList()
)
