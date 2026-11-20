package org.example.project.dto

import kotlinx.serialization.Serializable

// Количества передаются строками, чтобы сохранить точность NUMERIC(19,4)
// без потерь при сериализации (инвариант 20 ТЗ).

@Serializable
enum class StorageLocationKind {
    ZONE,
    CELL
}

@Serializable
enum class StorageLocationPurpose {
    STORAGE,
    RECEIVING,
    SHIPPING,
    QUARANTINE,
    DEFECT,
    TEMPORARY
}

@Serializable
enum class NomenclatureType {
    RAW_MATERIAL,
    MATERIAL,
    PURCHASED_COMPONENT,
    STANDARD_PRODUCT,
    OWN_UNIT,
    ASSEMBLY,
    FINISHED_PRODUCT,
    TOOL,
    EQUIPMENT,
    CONSUMABLE,
    SERVICE,
    KIT
}

@Serializable
enum class BarcodePackageType {
    UNIT,
    PACKAGE
}

@Serializable
enum class ReceiptSourceType {
    PURCHASE_ORDER,
    RETURN,
    TRANSFER,
    INITIAL_STOCK,
    OTHER
}

@Serializable
enum class ReceiptStatus {
    DRAFT,
    IN_PROGRESS,
    POSTED,
    CANCELLED
}

@Serializable
enum class ReservationReasonType {
    PROJECT,
    PRODUCTION_ORDER,
    STOCKPILE,
    REPAIR,
    DEPARTMENT_TASK,
    OTHER
}

@Serializable
enum class ReservationStatus {
    ACTIVE,
    FULFILLED,
    RELEASED,
    CANCELLED
}

@Serializable
enum class ReservationHistoryAction {
    CREATED,
    CHANGED,
    ISSUED,
    FULFILLED,
    RELEASED,
    CANCELLED
}

@Serializable
data class WarehouseDto(
    val id: Long,
    val code: String,
    val name: String,
    val address: String? = null,
    val active: Boolean = true,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0
)

@Serializable
data class CreateWarehouseRequest(
    val code: String,
    val name: String,
    val address: String? = null
)

@Serializable
data class StorageLocationDto(
    val id: Long,
    val warehouseId: Long,
    val parentId: Long? = null,
    val code: String,
    val name: String,
    val kind: StorageLocationKind,
    val purpose: StorageLocationPurpose = StorageLocationPurpose.STORAGE,
    val capacity: String? = null,
    val active: Boolean = true,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0
)

@Serializable
data class CreateStorageLocationRequest(
    val warehouseId: Long,
    val parentId: Long? = null,
    val code: String,
    val name: String,
    val kind: StorageLocationKind,
    val purpose: StorageLocationPurpose = StorageLocationPurpose.STORAGE,
    val capacity: String? = null
)

@Serializable
data class NomenclatureBarcodeDto(
    val id: Long,
    val barcode: String,
    val packageType: BarcodePackageType = BarcodePackageType.UNIT,
    val quantityInPackage: String = "1"
)

@Serializable
data class CreateNomenclatureBarcodeRequest(
    val barcode: String,
    val packageType: BarcodePackageType = BarcodePackageType.UNIT,
    val quantityInPackage: String = "1"
)

@Serializable
data class NomenclatureDto(
    val id: Long,
    val code: String,
    val name: String,
    val description: String? = null,
    val type: NomenclatureType,
    val unit: String,
    val lotTracked: Boolean = false,
    val serialTracked: Boolean = false,
    val archived: Boolean = false,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val barcodes: List<NomenclatureBarcodeDto> = emptyList()
)

@Serializable
data class LotDto(
    val id: Long,
    val nomenclatureId: Long,
    val lotNumber: String,
    val manufactureDate: String? = null,
    val expiryDate: String? = null,
    val createdAt: String? = null
)

@Serializable
data class SerialItemDto(
    val id: Long,
    val nomenclatureId: Long,
    val lotId: Long? = null,
    val serialNumber: String,
    val createdAt: String? = null
)

@Serializable
enum class StockMovementType {
    RECEIPT,
    ISSUE,
    RETURN,
    WRITE_OFF,
    TRANSFER,
    ADJUSTMENT_IN,
    ADJUSTMENT_OUT
}

@Serializable
data class StockMovementDto(
    val id: Long,
    val type: StockMovementType,
    val nomenclatureId: Long,
    val lotNumber: String? = null,
    val serialNumber: String? = null,
    val fromLocationCode: String? = null,
    val toLocationCode: String? = null,
    val quantity: String,
    val documentType: String,
    val documentId: Long,
    val note: String? = null,
    val createdBy: Long,
    val createdByName: String,
    val createdAt: String? = null
)

@Serializable
data class NomenclatureDetailsDto(
    val item: NomenclatureDto,
    val physicalQty: String = "0",
    val reservedQty: String = "0",
    val availableQty: String = "0",
    val stock: List<StockBalanceDto> = emptyList(),
    val lots: List<LotDto> = emptyList(),
    val serials: List<SerialItemDto> = emptyList(),
    val movements: List<StockMovementDto> = emptyList()
)

@Serializable
data class CreateNomenclatureRequest(
    val code: String,
    val name: String,
    val description: String? = null,
    val type: NomenclatureType,
    val unit: String,
    val lotTracked: Boolean = false,
    val serialTracked: Boolean = false,
    val barcodes: List<CreateNomenclatureBarcodeRequest> = emptyList()
)

@Serializable
data class StockBalanceDto(
    val id: Long,
    val warehouseId: Long,
    val locationId: Long,
    val locationCode: String,
    val locationPurpose: StorageLocationPurpose,
    val nomenclatureId: Long,
    val lotId: Long? = null,
    val lotNumber: String? = null,
    val serialId: Long? = null,
    val serialNumber: String? = null,
    val physicalQty: String,
    val reservedQty: String,
    val availableQty: String,
    val version: Long = 0
)

@Serializable
data class ReceiptLineInput(
    val lineNo: Int,
    val nomenclatureId: Long,
    val expectedQuantity: String,
    val receivedQuantity: String? = null,
    val lotNumber: String? = null,
    val locationId: Long,
    val note: String? = null
)

@Serializable
data class CreateReceiptRequest(
    val sourceType: ReceiptSourceType,
    val sourceRef: String? = null,
    val warehouseId: Long,
    val expectedAt: String? = null,
    val note: String? = null,
    val lines: List<ReceiptLineInput>
)

@Serializable
data class PostReceiptRequest(
    val version: Long
)

@Serializable
data class ReceiptLineDto(
    val id: Long,
    val lineNo: Int,
    val nomenclatureId: Long,
    val expectedQuantity: String,
    val receivedQuantity: String,
    val lotId: Long? = null,
    val locationId: Long? = null,
    val note: String? = null
)

@Serializable
data class ReceiptDto(
    val id: Long,
    val number: String,
    val sourceType: ReceiptSourceType,
    val sourceRef: String? = null,
    val warehouseId: Long,
    val status: ReceiptStatus = ReceiptStatus.DRAFT,
    val expectedAt: String? = null,
    val note: String? = null,
    val postedAt: String? = null,
    val postedBy: Long? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val lines: List<ReceiptLineDto> = emptyList()
)

@Serializable
data class CreateReservationRequest(
    val reasonType: ReservationReasonType,
    val projectId: Long? = null,
    val taskId: Long? = null,
    val reasonNote: String? = null,
    val nomenclatureId: Long,
    val warehouseId: Long,
    val locationId: Long,
    val lotId: Long? = null,
    val serialId: Long? = null,
    val quantity: String,
    val ownerId: Long? = null,
    val expiresAt: String? = null
)

@Serializable
data class ReleaseReservationRequest(
    val version: Long,
    val reason: String? = null
)

@Serializable
data class ReservationHistoryDto(
    val id: Long,
    val reservationId: Long,
    val action: ReservationHistoryAction,
    val fromStatus: ReservationStatus? = null,
    val toStatus: ReservationStatus? = null,
    val quantityBefore: String? = null,
    val quantityAfter: String? = null,
    val reason: String? = null,
    val changedBy: Long,
    val createdAt: String? = null
)

@Serializable
data class ReservationDto(
    val id: Long,
    val number: String,
    val reasonType: ReservationReasonType,
    val projectId: Long? = null,
    val taskId: Long? = null,
    val reasonNote: String? = null,
    val nomenclatureId: Long,
    val warehouseId: Long,
    val locationId: Long? = null,
    val lotId: Long? = null,
    val serialId: Long? = null,
    val quantity: String,
    val issuedQuantity: String = "0",
    val status: ReservationStatus = ReservationStatus.ACTIVE,
    val ownerId: Long,
    val expiresAt: String? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val history: List<ReservationHistoryDto> = emptyList()
)
