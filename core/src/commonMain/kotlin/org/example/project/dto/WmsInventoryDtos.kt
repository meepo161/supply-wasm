package org.example.project.dto

import kotlinx.serialization.Serializable

// Инвентаризация WMS (8.11 ТЗ). Количества — строками для точности NUMERIC(19,4).

@Serializable
enum class InventoryScopeType {
    WAREHOUSE,
    ZONE,
    CELL,
    NOMENCLATURE
}

@Serializable
enum class InventoryStatus {
    DRAFT,
    BOOKED,
    COUNTING,
    DISCREPANCY,
    PAUSED,
    APPROVED,
    POSTED,
    CANCELLED
}

@Serializable
enum class InventoryDiscrepancyKind {
    SURPLUS,
    SHORTAGE
}

@Serializable
data class CreateInventoryRequest(
    val warehouseId: Long,
    val scopeType: InventoryScopeType,
    val scopeLocationId: Long? = null,
    val scopeNomenclatureId: Long? = null,
    val note: String? = null,
    val idempotencyKey: String
)

@Serializable
data class InventoryActionRequest(
    val version: Long,
    val idempotencyKey: String
)

// Подсчёт комиссией: количество по строке снимка; recount = повторный пересчёт.
@Serializable
data class InventoryCountInput(
    val snapshotId: Long,
    val countedQuantity: String,
    val recount: Boolean = false,
    val note: String? = null
)

@Serializable
data class SubmitInventoryCountsRequest(
    val version: Long,
    val counts: List<InventoryCountInput>,
    val idempotencyKey: String
)

@Serializable
data class CancelInventoryRequest(
    val version: Long,
    val reason: String,
    val idempotencyKey: String
)

@Serializable
data class InventorySnapshotDto(
    val id: Long,
    val inventoryId: Long,
    val locationId: Long,
    val nomenclatureId: Long,
    val lotId: Long? = null,
    val serialId: Long? = null,
    val bookQuantity: String,
    val countedQuantity: String? = null,
    val countedAt: String? = null,
    val recount: Boolean = false
)

// Строка ведомости расхождений (WMS-36).
@Serializable
data class InventoryDiscrepancyDto(
    val id: Long,
    val inventoryId: Long,
    val snapshotId: Long,
    val locationId: Long,
    val nomenclatureId: Long,
    val lotId: Long? = null,
    val serialId: Long? = null,
    val bookQuantity: String,
    val countedQuantity: String,
    val discrepancy: String,
    val kind: InventoryDiscrepancyKind? = null,
    val note: String? = null
)

@Serializable
data class InventoryDto(
    val id: Long,
    val number: String,
    val warehouseId: Long,
    val scopeType: InventoryScopeType,
    val scopeLocationId: Long? = null,
    val scopeNomenclatureId: Long? = null,
    val status: InventoryStatus = InventoryStatus.DRAFT,
    val note: String? = null,
    val bookedAt: String? = null,
    val bookedBy: Long? = null,
    val countedAt: String? = null,
    val countedBy: Long? = null,
    val approvedAt: String? = null,
    val approvedBy: Long? = null,
    val postedAt: String? = null,
    val postedBy: Long? = null,
    val pausedAt: String? = null,
    val pausedBy: Long? = null,
    val pausedFromStatus: InventoryStatus? = null,
    val cancelledAt: String? = null,
    val cancelReason: String? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val snapshot: List<InventorySnapshotDto> = emptyList(),
    val discrepancies: List<InventoryDiscrepancyDto> = emptyList()
)
