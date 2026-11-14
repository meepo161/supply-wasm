package org.example.project.dto

import kotlinx.serialization.Serializable

// Списание WMS (8.10 ТЗ). Количества — строками для точности NUMERIC(19,4).

@Serializable
enum class WriteOffReasonType {
    DAMAGE,
    DEFECT,
    EXPIRY,
    LOSS,
    NATURAL_WASTAGE,
    TECHNOLOGICAL,
    OTHER
}

@Serializable
enum class WriteOffStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    POSTED,
    CANCELLED
}

@Serializable
data class WriteOffLineInput(
    val lineNo: Int,
    val nomenclatureId: Long,
    val locationId: Long,
    val quantity: String,
    val lotId: Long? = null,
    val serialId: Long? = null,
    val note: String? = null
)

@Serializable
data class CreateWriteOffRequest(
    val warehouseId: Long,
    val reasonType: WriteOffReasonType,
    val reasonNote: String,
    val note: String? = null,
    val lines: List<WriteOffLineInput>,
    val idempotencyKey: String
)

// Действия по документу: отправка на согласование, проведение, отмена.
@Serializable
data class WriteOffActionRequest(
    val version: Long,
    val idempotencyKey: String
)

@Serializable
data class CancelWriteOffRequest(
    val version: Long,
    val reason: String,
    val idempotencyKey: String
)

@Serializable
data class WriteOffLineDto(
    val id: Long,
    val writeOffId: Long,
    val lineNo: Int,
    val nomenclatureId: Long,
    val locationId: Long,
    val lotId: Long? = null,
    val serialId: Long? = null,
    val quantity: String,
    val note: String? = null
)

@Serializable
data class WriteOffDto(
    val id: Long,
    val number: String,
    val warehouseId: Long,
    val reasonType: WriteOffReasonType,
    val reasonNote: String,
    val status: WriteOffStatus = WriteOffStatus.DRAFT,
    val totalQuantity: String,
    val overLimit: Boolean = false,
    val limitQuantity: String? = null,
    val approvalRouteId: Long? = null,
    val approvedAt: String? = null,
    val approvedBy: Long? = null,
    val postedAt: String? = null,
    val postedBy: Long? = null,
    val cancelledAt: String? = null,
    val cancelReason: String? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val lines: List<WriteOffLineDto> = emptyList()
)

// Лимит списания без согласования на склад.
@Serializable
data class WriteOffLimitDto(
    val warehouseId: Long,
    val limitQuantity: String
)

@Serializable
data class SetWriteOffLimitRequest(
    val limitQuantity: String
)
