package org.example.project.dto

import kotlinx.serialization.Serializable

// Перемещения WMS (8.6 ТЗ). Количества — строками для точности NUMERIC(19,4).

@Serializable
enum class TransferKind {
    INTERNAL,
    INTER_WAREHOUSE
}

@Serializable
enum class TransferStatus {
    CREATED,
    SHIPPED,
    IN_TRANSIT,
    RECEIVED,
    DISCREPANCY,
    CANCELLED
}

@Serializable
enum class TransferEventAction {
    CREATED,
    SHIPPED,
    IN_TRANSIT,
    RECEIVED,
    DISCREPANCY,
    CANCELLED
}

@Serializable
data class CreateTransferRequest(
    val kind: TransferKind,
    val fromWarehouseId: Long,
    val fromLocationId: Long,
    val toWarehouseId: Long,
    val toLocationId: Long,
    val nomenclatureId: Long,
    val lotId: Long? = null,
    val serialId: Long? = null,
    val quantity: String,
    val note: String? = null,
    val idempotencyKey: String
)

@Serializable
data class ShipTransferRequest(
    val version: Long,
    val idempotencyKey: String
)

@Serializable
data class MarkTransferInTransitRequest(
    val version: Long,
    val idempotencyKey: String
)

@Serializable
data class ReceiveTransferRequest(
    val version: Long,
    val receivedQuantity: String,
    val note: String? = null,
    val idempotencyKey: String
)

@Serializable
data class CancelTransferRequest(
    val version: Long,
    val reason: String,
    val idempotencyKey: String
)

@Serializable
data class TransferEventDto(
    val id: Long,
    val transferId: Long,
    val action: TransferEventAction,
    val fromStatus: TransferStatus? = null,
    val toStatus: TransferStatus? = null,
    val quantity: String? = null,
    val discrepancyQuantity: String? = null,
    val note: String? = null,
    val changedBy: Long,
    val createdAt: String? = null
)

@Serializable
data class TransferDto(
    val id: Long,
    val number: String,
    val kind: TransferKind,
    val fromWarehouseId: Long,
    val fromLocationId: Long,
    val toWarehouseId: Long,
    val toLocationId: Long,
    val transitLocationId: Long? = null,
    val nomenclatureId: Long,
    val lotId: Long? = null,
    val serialId: Long? = null,
    val quantity: String,
    val receivedQuantity: String? = null,
    val discrepancyQuantity: String? = null,
    val status: TransferStatus = TransferStatus.CREATED,
    val note: String? = null,
    val shippedAt: String? = null,
    val shippedBy: Long? = null,
    val receivedAt: String? = null,
    val receivedBy: Long? = null,
    val cancelledAt: String? = null,
    val cancelledBy: Long? = null,
    val cancelReason: String? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val events: List<TransferEventDto> = emptyList()
)
