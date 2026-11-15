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

/**
 * Основание приёмки (ТЗ §8.4): заказ поставщику, счёт, накладная/УПД, возврат,
 * перемещение, начальный остаток, прочий документированный источник.
 * Основание обязательно и проверяется сервером.
 */
@Serializable
enum class ReceiptSourceType {
    PURCHASE_ORDER,
    INVOICE,
    DELIVERY_NOTE,
    RETURN,
    TRANSFER,
    INITIAL_STOCK,
    OTHER
}

/** Вид расхождения «ожидалось / принято» по строке приёмки. */
@Serializable
enum class ReceiptDiscrepancyKind {
    /** Принято меньше ожидаемого: остаток не закрыт, недопоставка видна. */
    SHORTAGE,

    /** Принято больше ожидаемого: излишек требует отдельного решения. */
    SURPLUS
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
    val note: String? = null,
    /** Позиция заказа поставщику, которую закрывает строка приёмки. */
    val purchaseOrderLineId: Long? = null,
    /** Обоснование расхождения, если принято не столько, сколько ожидалось. */
    val discrepancyReason: String? = null
)

@Serializable
data class CreateReceiptRequest(
    val sourceType: ReceiptSourceType,
    /** Свободное описание основания, если документ ещё не заведён. */
    val sourceRef: String? = null,
    /** Номер документа-основания: накладная, УПД, счёт. */
    val sourceDocumentNo: String? = null,
    /** Дата документа-основания. */
    val sourceDocumentDate: String? = null,
    /** Заказ поставщику, по которому идёт приёмка. */
    val purchaseOrderId: Long? = null,
    /** Поставка заказа, если приёмка закрывает конкретную поставку. */
    val purchaseDeliveryId: Long? = null,
    val warehouseId: Long,
    val expectedAt: String? = null,
    val note: String? = null,
    val lines: List<ReceiptLineInput>
)

/**
 * Поздняя связь уже проведённой приёмки с заказом и/или накладной.
 * Остаток не пересоздаётся: меняется только учётная связь документов.
 */
@Serializable
data class LinkReceiptRequest(
    val purchaseOrderId: Long? = null,
    val purchaseDeliveryId: Long? = null,
    val sourceDocumentNo: String? = null,
    val reason: String,
    val version: Long
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
    val nomenclatureCode: String? = null,
    val nomenclatureName: String? = null,
    val expectedQuantity: String,
    val receivedQuantity: String,
    val lotId: Long? = null,
    val locationId: Long? = null,
    val note: String? = null,
    /** Расхождение по строке: вид, величина и обоснование. */
    val discrepancyKind: ReceiptDiscrepancyKind? = null,
    val discrepancyQuantity: String? = null,
    val discrepancyReason: String? = null,
    val purchaseOrderLineId: Long? = null
)

@Serializable
data class ReceiptDto(
    val id: Long,
    val number: String,
    val sourceType: ReceiptSourceType,
    val sourceRef: String? = null,
    val sourceDocumentNo: String? = null,
    val sourceDocumentDate: String? = null,
    val purchaseOrderId: Long? = null,
    val purchaseOrderNumber: String? = null,
    val purchaseDeliveryId: Long? = null,
    val purchaseDeliveryNumber: String? = null,
    val warehouseId: Long,
    val status: ReceiptStatus = ReceiptStatus.DRAFT,
    val expectedAt: String? = null,
    val note: String? = null,
    val postedAt: String? = null,
    val postedBy: Long? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val lines: List<ReceiptLineDto> = emptyList(),
    /** Связи, добавленные после проведения: заказ или накладная, пришедшие позже. */
    val links: List<ReceiptLinkDto> = emptyList(),
    /** Есть ли по документу расхождения «ожидалось / принято». */
    val hasDiscrepancies: Boolean = false
)

@Serializable
data class ReceiptLinkDto(
    val id: Long,
    val receiptId: Long,
    val purchaseOrderId: Long? = null,
    val purchaseDeliveryId: Long? = null,
    /** Номер документа, пришедшего позже: накладная, УПД, счёт. */
    val sourceDocumentNo: String? = null,
    val reason: String,
    val linkedBy: Long,
    val linkedAt: String? = null
)

/** Ожидаемое к приёмке по заказу: «заказано / поставлено / принято / осталось». */
@Serializable
data class ExpectedReceiptLineDto(
    val orderId: Long,
    val orderNumber: String,
    val orderStatus: String,
    val orderLineId: Long,
    val lineNo: Int,
    val nomenclatureId: Long,
    val nomenclatureCode: String? = null,
    val nomenclatureName: String? = null,
    val orderedQuantity: String,
    val deliveredQuantity: String,
    val acceptedQuantity: String,
    val expectedRemaining: String,
    val notAcceptedQuantity: String
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
