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
    val minStock: String = "0",
    val safetyStock: String = "0",
    val leadTimeDays: Int = 0,
    val archived: Boolean = false,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val barcodes: List<NomenclatureBarcodeDto> = emptyList(),
    // Атрибуты карточки (§8.2, D-63). Масса — кг, габариты — мм.
    val categoryId: Long? = null,
    val article: String? = null,
    val manufacturer: String? = null,
    val manufacturerModel: String? = null,
    val weightKg: String? = null,
    val lengthMm: String? = null,
    val widthMm: String? = null,
    val heightMm: String? = null,
    val storageConditions: String? = null,
    val productModelId: Long? = null,
    val minOrderQty: String = "1",
    val orderMultiple: String = "1"
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
    val createdAt: String? = null,
    /** Номер документа-основания (WOF-2026-00001); null, если документ не найден. */
    val documentNumber: String? = null
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
    val minStock: String = "0",
    val safetyStock: String = "0",
    val leadTimeDays: Int = 0,
    val barcodes: List<CreateNomenclatureBarcodeRequest> = emptyList()
)

@Serializable
data class UpdateNomenclatureThresholdsRequest(
    val minStock: String = "0",
    val safetyStock: String = "0",
    val leadTimeDays: Int = 0,
    val version: Long
)

@Serializable
data class NomenclatureUnitConversionDto(
    val id: Long,
    val nomenclatureId: Long,
    val fromUnit: String,
    val toUnit: String,
    val factor: String
)

@Serializable
data class CreateUnitConversionRequest(
    val fromUnit: String,
    val toUnit: String,
    val factor: String
)

@Serializable
data class NomenclatureAnalogDto(
    val id: Long,
    val nomenclatureId: Long,
    val analogNomenclatureId: Long,
    val analogCode: String = "",
    val analogName: String = "",
    val conversionFactor: String = "1",
    val applicabilityCondition: String? = null,
    val priority: Int = 1,
    val active: Boolean = true,
    val requiresApproval: Boolean = false
)

@Serializable
data class CreateNomenclatureAnalogRequest(
    val analogNomenclatureId: Long,
    val conversionFactor: String = "1",
    val applicabilityCondition: String? = null,
    val priority: Int = 1,
    val requiresApproval: Boolean = false
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
    val inTransitQty: String = "0",
    val version: Long = 0
)

@Serializable
enum class ReceiptQcStatus {
    PENDING,
    PASSED,
    PARTIAL,
    FAILED
}

@Serializable
data class ReceiptQcLineInput(
    val receiptLineId: Long,
    val acceptedQty: String,
    val quarantineQty: String = "0",
    val defectQty: String = "0",
    val defectReason: String? = null
)

@Serializable
data class InspectReceiptQcRequest(
    val comment: String? = null,
    val lines: List<ReceiptQcLineInput>
)

@Serializable
data class ReceiptQcLineDto(
    val id: Long,
    val receiptLineId: Long,
    val nomenclatureId: Long,
    val nomenclatureCode: String,
    val nomenclatureName: String,
    val receivedQty: String,
    val acceptedQty: String,
    val quarantineQty: String,
    val defectQty: String,
    val defectReason: String? = null
)

@Serializable
data class ReceiptQcDto(
    val id: Long,
    val receiptId: Long,
    val status: ReceiptQcStatus,
    val inspectorId: Long? = null,
    val inspectedAt: String? = null,
    val comment: String? = null,
    val version: Long = 0,
    val lines: List<ReceiptQcLineDto> = emptyList()
)

@Serializable
data class LowStockItemDto(
    val nomenclatureId: Long,
    val code: String,
    val name: String,
    val unit: String,
    val minStock: String,
    val safetyStock: String,
    val availableQty: String,
    val deficitQty: String
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
    val hasDiscrepancies: Boolean = false,
    /** Счёт поставщика, по которому снабжение оформило поступление (D-61). */
    val supplierInvoiceId: Long? = null,
    val supplierInvoiceNumber: String? = null
)

@Serializable
data class ReceiptLinkDto(
    val id: Long,
    val receiptId: Long,
    val purchaseOrderId: Long? = null,
    val purchaseOrderNumber: String? = null,
    val purchaseDeliveryId: Long? = null,
    val purchaseDeliveryNumber: String? = null,
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
