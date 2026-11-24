package org.example.project.dto

import kotlinx.serialization.Serializable

// Карточка номенклатуры (NSI-001, D-63). Количества — строки, как во всём WMS,
// чтобы не терять точность NUMERIC(19,4).

@Serializable
data class NomenclatureCategoryDto(
    val id: Long,
    val parentId: Long? = null,
    val code: String,
    val name: String,
    /** Полный путь от корня: «Электроника / Разъёмы». Формирует сервер. */
    val path: String,
    val archived: Boolean = false,
    val version: Long = 0
)

@Serializable
data class CreateNomenclatureCategoryRequest(
    val code: String,
    val name: String,
    val parentId: Long? = null
)

@Serializable
data class UpdateNomenclatureCategoryRequest(
    val name: String,
    val parentId: Long? = null,
    val archived: Boolean = false,
    val version: Long
)

@Serializable
data class NomenclatureAttributeDto(
    val id: Long,
    val name: String,
    val value: String,
    val unit: String? = null,
    val sortOrder: Int = 0
)

@Serializable
data class NomenclatureAttributeInput(
    val name: String,
    val value: String,
    val unit: String? = null
)

/**
 * Изменение карточки. Код, тип, единица и признаки партионного/серийного учёта
 * не меняются: от них зависят остатки и проведённые документы (D-63).
 * Характеристики и допустимые склады передаются целиком и заменяют прежние.
 */
@Serializable
data class UpdateNomenclatureRequest(
    val version: Long,
    val name: String,
    val description: String? = null,
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
    val minStock: String = "0",
    val safetyStock: String = "0",
    val leadTimeDays: Int = 0,
    val minOrderQty: String = "1",
    val orderMultiple: String = "1",
    val attributes: List<NomenclatureAttributeInput> = emptyList(),
    val allowedWarehouseIds: List<Long> = emptyList()
)

@Serializable
data class WarehouseRefDto(
    val id: Long,
    val code: String,
    val name: String
)

/** Остатки по §8.3 в разрезе склада. */
@Serializable
data class WarehouseStockSummaryDto(
    val warehouseId: Long,
    val warehouseCode: String,
    val warehouseName: String,
    val physicalQty: String,
    val availableQty: String,
    val reservedQty: String,
    val quarantineQty: String,
    val defectQty: String,
    val inTransitQty: String
)

/**
 * Семь видов остатка §8.3, посчитанные по единому правилу D-63.
 * temporaryQty — остаток во временных ячейках (в том числе транзитных):
 * он входит в физический, но не в доступный.
 */
@Serializable
data class NomenclatureStockSummaryDto(
    val physicalQty: String = "0",
    val availableQty: String = "0",
    val reservedQty: String = "0",
    val expectedQty: String = "0",
    val quarantineQty: String = "0",
    val defectQty: String = "0",
    val inTransitQty: String = "0",
    val temporaryQty: String = "0",
    val belowMinStock: Boolean = false,
    val belowSafetyStock: Boolean = false,
    val byWarehouse: List<WarehouseStockSummaryDto> = emptyList()
)

/** Ожидаемая поставка по строке заказа поставщику. */
@Serializable
data class NomenclatureExpectedSupplyDto(
    val orderId: Long,
    val orderNumber: String,
    val orderStatus: String,
    val supplierName: String? = null,
    val lineNo: Int,
    val orderedQty: String,
    val acceptedQty: String,
    val remainingQty: String,
    val expectedAt: String? = null
)

@Serializable
data class NomenclatureProductRevisionDto(
    val id: Long,
    val revisionNumber: String,
    val status: BomRevisionStatus,
    val approvedAt: String? = null
)

@Serializable
data class NomenclatureProductModelDto(
    val id: Long,
    val code: String,
    val name: String,
    val status: ProductModelStatus,
    val designerName: String? = null,
    val technologistName: String? = null,
    val revisions: List<NomenclatureProductRevisionDto> = emptyList()
)

@Serializable
data class NomenclatureCardCountsDto(
    val activeReservations: Int = 0,
    val movements: Int = 0,
    val usage: Int = 0,
    val documents: Int = 0,
    val attachments: Int = 0,
    val history: Int = 0
)

/**
 * Предложение для «Сформировать потребность» (D-63): до страхового запаса
 * (или минимального, если страховой не задан) минус доступное и ожидаемое,
 * поднято до минимальной партии и округлено вверх до кратности заказа.
 * null — дефицита нет.
 */
@Serializable
data class NomenclatureDemandSuggestionDto(
    val targetQty: String,
    val deficitQty: String,
    val suggestedQty: String,
    val requiredDate: String? = null,
    /**
     * Открытые потребности по позиции (не покрыты и не отменены). В расчёт не
     * входят — это ещё не поставка, — но показываются, чтобы не создать дубль.
     */
    val openDemands: List<String> = emptyList()
)

@Serializable
data class NomenclatureCardDto(
    val item: NomenclatureDto,
    val category: NomenclatureCategoryDto? = null,
    val attributes: List<NomenclatureAttributeDto> = emptyList(),
    val unitConversions: List<NomenclatureUnitConversionDto> = emptyList(),
    val analogs: List<NomenclatureAnalogDto> = emptyList(),
    /** Позиции, для которых эта номенклатура указана аналогом. */
    val analogOf: List<NomenclatureAnalogDto> = emptyList(),
    val allowedWarehouses: List<WarehouseRefDto> = emptyList(),
    val productModel: NomenclatureProductModelDto? = null,
    val stock: NomenclatureStockSummaryDto = NomenclatureStockSummaryDto(),
    val stockRows: List<StockBalanceDto> = emptyList(),
    val lots: List<LotDto> = emptyList(),
    val serials: List<SerialItemDto> = emptyList(),
    val expectedSupplies: List<NomenclatureExpectedSupplyDto> = emptyList(),
    val demandSuggestion: NomenclatureDemandSuggestionDto? = null,
    val counts: NomenclatureCardCountsDto = NomenclatureCardCountsDto(),
    val createdByName: String? = null
)

@Serializable
data class NomenclatureStockPointDto(
    val date: String,
    val physicalQty: String
)

@Serializable
data class NomenclatureMovementsPageDto(
    val items: List<StockMovementDto> = emptyList(),
    /** id последнего движения страницы; передаётся как `before` для следующей. */
    val nextBefore: Long? = null
)

@Serializable
data class NomenclatureReservationRowDto(
    val id: Long,
    val number: String,
    val reasonType: ReservationReasonType,
    val reasonLabel: String,
    val reasonNote: String? = null,
    val projectId: Long? = null,
    val taskId: Long? = null,
    val warehouseName: String,
    val locationCode: String? = null,
    val lotNumber: String? = null,
    val serialNumber: String? = null,
    val quantity: String,
    val issuedQuantity: String,
    val status: ReservationStatus,
    val ownerName: String,
    val expiresAt: String? = null,
    val createdAt: String? = null
)

@Serializable
data class NomenclatureUsageDemandDto(
    val demandId: Long,
    val number: String,
    val status: String,
    val sourceType: String,
    val projectName: String? = null,
    val quantity: String,
    val coveredQuantity: String,
    val unit: String
)

@Serializable
data class NomenclatureUsagePurchaseOrderDto(
    val orderId: Long,
    val number: String,
    val status: String,
    val supplierName: String? = null,
    val quantity: String,
    val deliveredQuantity: String,
    val unit: String
)

@Serializable
data class NomenclatureUsageProductionOrderDto(
    val orderId: Long,
    val number: String,
    val status: String,
    val plannedQty: String,
    val consumedQty: String,
    val unit: String
)

@Serializable
data class NomenclatureUsageDto(
    val bomLines: List<BomWhereUsedItemDto> = emptyList(),
    /** Ревизии, где позиция — родитель строки (сама является сборкой). */
    val parentBomLines: List<BomWhereUsedItemDto> = emptyList(),
    val demands: List<NomenclatureUsageDemandDto> = emptyList(),
    val purchaseOrders: List<NomenclatureUsagePurchaseOrderDto> = emptyList(),
    val productionOrders: List<NomenclatureUsageProductionOrderDto> = emptyList(),
    /** Причины, по которым позицию сейчас нельзя архивировать. Подписи — с сервера. */
    val archiveBlockers: List<String> = emptyList()
)

@Serializable
data class NomenclatureHistoryEntryDto(
    val id: Long,
    val action: String,
    /** Подпись действия по-русски; клиент коды не переводит (как в D-50). */
    val title: String,
    val details: String? = null,
    val userName: String? = null,
    val createdAt: String
)
