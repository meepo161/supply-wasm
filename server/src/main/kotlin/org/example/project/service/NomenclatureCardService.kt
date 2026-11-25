package org.example.project.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.Lots.LOTS
import org.example.project.generated.jooq.tables.NomenclatureAnalogs.NOMENCLATURE_ANALOGS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.SerialItems.SERIAL_ITEMS
import org.example.project.generated.jooq.tables.StockMovement.STOCK_MOVEMENT
import org.example.project.generated.jooq.tables.StorageLocations.STORAGE_LOCATIONS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.records.NomenclatureCategoriesRecord
import org.example.project.generated.jooq.tables.records.NomenclatureRecord
import org.example.project.model.CurrentUser
import org.example.project.repository.NomenclatureCardRepository
import org.example.project.repository.WmsRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.TransactionalCallable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Карточка номенклатуры (NSI-001, D-63).
 *
 * Читать карточку и все её вкладки может держатель `wms:read`, менять —
 * `wms:manage`. Остатки сводятся по единому правилу D-63; движения, резервы и
 * проведённые документы карточка только показывает.
 */
class NomenclatureCardService(
    private val cardRepository: NomenclatureCardRepository,
    private val wmsRepository: WmsRepository,
    private val auditService: AuditService,
    private val dsl: DSLContext,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zone) }
) {

    // --- Карточка ---

    fun getCard(id: Long, currentUser: CurrentUser): NomenclatureCardDto {
        currentUser.requirePermission("wms", "read")
        val record = wmsRepository.findNomenclatureById(id) ?: throw NotFoundException("nomenclature", id)
        val item = record.toNomenclatureDto(loadBarcodes(id))
        val categories = cardRepository.listCategories().associateBy { it.id }
        val stockRows = wmsRepository.selectStock(null, id)
        val warehouses = cardRepository.warehouses()
        val expectedSupplies = cardRepository.expectedSupplies(id)
        val summary = buildStockSummary(record, stockRows, warehouses, cardRepository.inTransitByDestination(id), expectedSupplies)
        val demandUsage = cardRepository.demandUsage(id)
        // Счётчик совпадает с числом строк вкладки «Применяемость».
        val usageCount = cardRepository.bomUsage(id, asParent = false).size +
            cardRepository.bomUsage(id, asParent = true).size +
            demandUsage.size +
            cardRepository.purchaseOrderUsage(id).size +
            cardRepository.productionUsage(id).size

        return NomenclatureCardDto(
            item = item,
            category = record.categoryId?.let { categories[it] }?.toDto(categories),
            attributes = cardRepository.listAttributes(id).map {
                NomenclatureAttributeDto(it.id, it.name, it.value, it.unit, it.sortOrder ?: 0)
            },
            unitConversions = wmsRepository.listUnitConversions(id).map {
                NomenclatureUnitConversionDto(
                    id = it.id,
                    nomenclatureId = it.nomenclatureId,
                    fromUnit = it.fromUnit,
                    toUnit = it.toUnit,
                    factor = it.factor.plain()
                )
            },
            analogs = wmsRepository.listAnalogs(id).map { it.toAnalogDto() },
            analogOf = cardRepository.listAnalogOf(id).map { it.toAnalogDto() },
            allowedWarehouses = cardRepository.listAllowedWarehouses(id).map { WarehouseRefDto(it.id, it.code, it.name) },
            productModel = record.productModelId?.let { modelId ->
                cardRepository.findProductModel(modelId)?.let { model ->
                    NomenclatureProductModelDto(
                        id = model.id,
                        code = model.code,
                        name = model.name,
                        status = ProductModelStatus.valueOf(model.status),
                        designerName = model.designerName,
                        technologistName = model.technologistName,
                        revisions = model.revisions.map {
                            NomenclatureProductRevisionDto(
                                id = it.id,
                                revisionNumber = it.revisionNumber,
                                status = BomRevisionStatus.valueOf(it.status),
                                approvedAt = it.approvedAt?.toString()
                            )
                        }
                    )
                }
            },
            stock = summary,
            stockRows = stockRows.map { row ->
                val restricted = row.locationPurpose in RESTRICTED_PURPOSES
                StockBalanceDto(
                    id = row.balance.id,
                    warehouseId = row.balance.warehouseId,
                    locationId = row.balance.locationId,
                    locationCode = row.locationCode,
                    locationPurpose = StorageLocationPurpose.valueOf(row.locationPurpose),
                    nomenclatureId = row.balance.nomenclatureId,
                    lotId = row.balance.lotId,
                    lotNumber = row.lotNumber,
                    serialId = row.balance.serialId,
                    serialNumber = row.serialNumber,
                    physicalQty = row.balance.physicalQty.plain(),
                    reservedQty = row.balance.reservedQty.plain(),
                    availableQty = if (restricted) "0" else row.balance.availableQty.plain(),
                    version = row.balance.version ?: 0
                )
            },
            lots = wmsRepository.listLots(id).map {
                LotDto(it.id, it.nomenclatureId, it.lotNumber, it.manufactureDate?.toString(), it.expiryDate?.toString(), it.createdAt?.toString())
            },
            serials = wmsRepository.listSerials(id).map {
                SerialItemDto(it.id, it.nomenclatureId, it.lotId, it.serialNumber, it.createdAt?.toString())
            },
            expectedSupplies = expectedSupplies.map {
                NomenclatureExpectedSupplyDto(
                    orderId = it.orderId,
                    orderNumber = it.orderNumber,
                    orderStatus = it.orderStatus,
                    supplierName = it.supplierName,
                    lineNo = it.lineNo,
                    orderedQty = it.ordered.plain(),
                    acceptedQty = it.accepted.plain(),
                    remainingQty = it.remaining.plain(),
                    expectedAt = it.expectedAt?.toString()
                )
            },
            demandSuggestion = suggestDemand(record, summary, demandUsage),
            openDemands = openDemandLabels(demandUsage),
            counts = NomenclatureCardCountsDto(
                activeReservations = cardRepository.countActiveReservations(id),
                movements = cardRepository.countMovements(id),
                usage = usageCount,
                documents = countDocuments(id, currentUser),
                attachments = cardRepository.countAttachments(id),
                history = cardRepository.countHistory(id)
            ),
            createdByName = cardRepository.userName(record.createdBy)
        )
    }

    fun updateCard(id: Long, request: UpdateNomenclatureRequest, currentUser: CurrentUser): NomenclatureCardDto {
        currentUser.requirePermission("wms", "manage")
        val current = wmsRepository.findNomenclatureById(id) ?: throw NotFoundException("nomenclature", id)
        // Сначала версия: устаревшая форма получает понятный 409, а не ошибку
        // валидации значений, которые за это время могли стать неактуальными.
        if ((current.version ?: 0) != request.version) {
            throw ConflictException("Карточку уже изменил другой пользователь. Обновите её и повторите.")
        }
        if (current.archived == true) {
            throw ConflictException("Архивную позицию нельзя изменять")
        }
        val name = request.name.trim()
        if (name.isEmpty()) throw ValidationException("Наименование обязательно", mapOf("name" to "required"))
        requireMaxLength(name, 500, "name", "Наименование")
        requireMaxLength(request.article, 128, "article", "Артикул")
        requireMaxLength(request.manufacturer, 255, "manufacturer", "Производитель")
        requireMaxLength(request.manufacturerModel, 255, "manufacturerModel", "Модель производителя")

        val categoryId = request.categoryId
        if (categoryId != null) {
            val category = cardRepository.findCategory(categoryId) ?: throw NotFoundException("nomenclature_category", categoryId)
            if (category.archived == true && categoryId != current.categoryId) {
                throw ValidationException("Архивную категорию нельзя назначить", mapOf("categoryId" to "archived"))
            }
        }
        val productModelId = request.productModelId
        if (productModelId != null) {
            if (!cardRepository.productModelExists(productModelId)) throw NotFoundException("product_model", productModelId)
            cardRepository.nomenclatureLinkedToModel(productModelId, id)?.let { otherCode ->
                throw ConflictException("Модель изделия уже связана с позицией $otherCode")
            }
        }
        val attributes = normalizeAttributes(request.attributes)
        val warehouseIds = request.allowedWarehouseIds.distinct()
        val missing = warehouseIds - cardRepository.existingWarehouseIds(warehouseIds)
        if (missing.isNotEmpty()) throw NotFoundException("warehouse", missing.first())
        if (request.leadTimeDays < 0) {
            throw ValidationException("Срок поставки не может быть отрицательным", mapOf("leadTimeDays" to "negative"))
        }

        val fields = NomenclatureCardRepository.CardFields(
            name = name,
            description = request.description.clean(),
            categoryId = categoryId,
            article = request.article.clean(),
            manufacturer = request.manufacturer.clean(),
            manufacturerModel = request.manufacturerModel.clean(),
            weightKg = positiveOrNull(request.weightKg, "weightKg")?.inRange("weightKg", WEIGHT_MAX, 4),
            lengthMm = positiveOrNull(request.lengthMm, "lengthMm")?.inRange("lengthMm", DIMENSION_MAX, 2),
            widthMm = positiveOrNull(request.widthMm, "widthMm")?.inRange("widthMm", DIMENSION_MAX, 2),
            heightMm = positiveOrNull(request.heightMm, "heightMm")?.inRange("heightMm", DIMENSION_MAX, 2),
            storageConditions = request.storageConditions.clean(),
            productModelId = productModelId,
            minStock = nonNegative(request.minStock, "minStock").inRange("minStock", QTY_MAX, 4),
            safetyStock = nonNegative(request.safetyStock, "safetyStock").inRange("safetyStock", QTY_MAX, 4),
            leadTimeDays = request.leadTimeDays.also {
                if (it > 3650) throw ValidationException("Срок поставки не больше 3650 дней", mapOf("leadTimeDays" to "too_large"))
            },
            minOrderQty = positive(request.minOrderQty, "minOrderQty").inRange("minOrderQty", QTY_MAX, 4),
            orderMultiple = positive(request.orderMultiple, "orderMultiple").inRange("orderMultiple", QTY_MAX, 4)
        )
        val changed = changedFields(
            current, fields,
            attributesChanged = cardRepository.listAttributes(id).map { Triple(it.name, it.value, it.unit) } !=
                attributes.map { Triple(it.name, it.value, it.unit) },
            warehousesChanged = cardRepository.listAllowedWarehouses(id).map { it.id }.toSet() != warehouseIds.toSet()
        )

        uniqueAsConflict("Модель изделия уже связана с другой позицией") {
            dsl.transaction { _ ->
                cardRepository.updateCard(id, fields, request.version)
                    ?: throw ConflictException("Карточку уже изменил другой пользователь. Обновите её и повторите.")
                cardRepository.replaceAttributes(id, attributes)
                cardRepository.replaceAllowedWarehouses(id, warehouseIds)
                auditService.log(
                    currentUser.userId, "UPDATE", "NOMENCLATURE", id,
                    mapOf("fields" to changed.joinToString(","))
                )
            }
        }
        return getCard(id, currentUser)
    }

    // --- Категории ---

    fun listCategories(currentUser: CurrentUser): List<NomenclatureCategoryDto> {
        currentUser.requirePermission("wms", "read")
        val all = cardRepository.listCategories().associateBy { it.id }
        return all.values.map { it.toDto(all) }.sortedBy { it.path.lowercase() }
    }

    fun createCategory(request: CreateNomenclatureCategoryRequest, currentUser: CurrentUser): NomenclatureCategoryDto {
        currentUser.requirePermission("wms", "manage")
        val code = request.code.trim()
        val name = request.name.trim()
        if (code.isEmpty()) throw ValidationException("Код категории обязателен", mapOf("code" to "required"))
        if (name.isEmpty()) throw ValidationException("Название категории обязательно", mapOf("name" to "required"))
        requireMaxLength(code, 64, "code", "Код категории")
        requireMaxLength(name, 255, "name", "Название категории")
        request.parentId?.let { parentId ->
            val parent = cardRepository.findCategory(parentId) ?: throw NotFoundException("nomenclature_category", parentId)
            if (parent.archived == true) {
                throw ValidationException("Нельзя добавить в архивную категорию", mapOf("parentId" to "archived"))
            }
        }
        if (cardRepository.categoryCodeExists(code)) throw ConflictException("Категория с кодом $code уже есть")
        val created = uniqueAsConflict("Категория с кодом $code уже есть") {
            dsl.transactionResult(TransactionalCallable {
                val record = cardRepository.createCategory(code, name, request.parentId, currentUser.userId)
                auditService.log(currentUser.userId, "CREATE", "NOMENCLATURE_CATEGORY", record.id, mapOf("code" to code))
                record
            })
        }
        val all = cardRepository.listCategories().associateBy { it.id }
        return created.toDto(all)
    }

    fun updateCategory(id: Long, request: UpdateNomenclatureCategoryRequest, currentUser: CurrentUser): NomenclatureCategoryDto {
        currentUser.requirePermission("wms", "manage")
        val existing = cardRepository.findCategory(id) ?: throw NotFoundException("nomenclature_category", id)
        val name = request.name.trim()
        if (name.isEmpty()) throw ValidationException("Название категории обязательно", mapOf("name" to "required"))
        requireMaxLength(name, 255, "name", "Название категории")
        val updated = dsl.transactionResult(TransactionalCallable {
            // Справочник блокируется на время проверки: два параллельных переноса
            // не смогут вместе образовать цикл по устаревшему снимку дерева.
            val all = cardRepository.lockCategories().associateBy { it.id }
            request.parentId?.let { parentId ->
                val parent = all[parentId] ?: throw NotFoundException("nomenclature_category", parentId)
                if (parent.archived == true && parentId != existing.parentId) {
                    throw ValidationException("Нельзя перенести в архивную категорию", mapOf("parentId" to "archived"))
                }
                // Цикл: новый родитель не может быть самой категорией или её потомком.
                var cursor: Long? = parentId
                val seen = mutableSetOf<Long>()
                while (cursor != null && seen.add(cursor)) {
                    if (cursor == id) {
                        throw ValidationException("Категория не может быть вложена в саму себя", mapOf("parentId" to "cycle"))
                    }
                    cursor = all[cursor]?.parentId
                }
            }
            val record = cardRepository.updateCategory(id, name, request.parentId, request.archived, request.version)
                ?: throw ConflictException("Категорию уже изменил другой пользователь. Обновите список.")
            auditService.log(currentUser.userId, "UPDATE", "NOMENCLATURE_CATEGORY", id, mapOf("name" to name))
            record
        })
        return updated.toDto(cardRepository.listCategories().associateBy { it.id })
    }

    // --- Вкладки ---

    fun stockHistory(id: Long, days: Int, currentUser: CurrentUser): List<NomenclatureStockPointDto> {
        currentUser.requirePermission("wms", "read")
        if (wmsRepository.findNomenclatureById(id) == null) throw NotFoundException("nomenclature", id)
        val span = days.coerceIn(7, 365)
        val end = today()
        val start = end.minusDays((span - 1).toLong())
        val current = wmsRepository.selectStock(null, id).fold(BigDecimal.ZERO) { acc, row -> acc + row.balance.physicalQty }
        val deltasByDay = cardRepository.movementsSince(id, start.atStartOfDay(zone).toOffsetDateTime())
            .groupBy { it.createdAt.atZoneSameInstant(zone).toLocalDate() }
            .mapValues { (_, items) -> items.fold(BigDecimal.ZERO) { acc, m -> acc + signedDelta(m.type, m.quantity) } }
        // Идём от сегодняшнего остатка назад: конец дня d-1 = конец дня d минус движения дня d.
        val points = ArrayDeque<NomenclatureStockPointDto>()
        var level = current
        var day = end
        while (!day.isBefore(start)) {
            points.addFirst(NomenclatureStockPointDto(day.toString(), level.max(BigDecimal.ZERO).plain()))
            level -= deltasByDay[day] ?: BigDecimal.ZERO
            day = day.minusDays(1)
        }
        return points.toList()
    }

    fun movements(
        id: Long,
        type: String?,
        from: String?,
        to: String?,
        before: Long?,
        limit: Int,
        currentUser: CurrentUser
    ): NomenclatureMovementsPageDto {
        currentUser.requirePermission("wms", "read")
        if (wmsRepository.findNomenclatureById(id) == null) throw NotFoundException("nomenclature", id)
        val movementType = type?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { StockMovementType.valueOf(it) }.getOrNull()
                ?: throw ValidationException("Неизвестный тип движения: $it", mapOf("type" to "invalid"))
        }
        val filter = NomenclatureCardRepository.MovementFilter(
            type = movementType?.name,
            from = parseDate(from, "from"),
            to = parseDate(to, "to"),
            before = before
        )
        val pageSize = limit.coerceIn(1, 200)
        val slFrom = STORAGE_LOCATIONS.`as`("sl_from")
        val slTo = STORAGE_LOCATIONS.`as`("sl_to")
        val rows = dsl.select(
            STOCK_MOVEMENT.ID, STOCK_MOVEMENT.MOVEMENT_TYPE, STOCK_MOVEMENT.NOMENCLATURE_ID,
            LOTS.LOT_NUMBER, SERIAL_ITEMS.SERIAL_NUMBER, slFrom.CODE, slTo.CODE,
            STOCK_MOVEMENT.QUANTITY, STOCK_MOVEMENT.DOCUMENT_TYPE, STOCK_MOVEMENT.DOCUMENT_ID,
            STOCK_MOVEMENT.NOTE, STOCK_MOVEMENT.CREATED_BY, USERS.FULL_NAME, STOCK_MOVEMENT.CREATED_AT
        )
            .from(STOCK_MOVEMENT)
            .leftJoin(LOTS).on(LOTS.ID.eq(STOCK_MOVEMENT.LOT_ID))
            .leftJoin(SERIAL_ITEMS).on(SERIAL_ITEMS.ID.eq(STOCK_MOVEMENT.SERIAL_ID))
            .leftJoin(slFrom).on(slFrom.ID.eq(STOCK_MOVEMENT.FROM_LOCATION_ID))
            .leftJoin(slTo).on(slTo.ID.eq(STOCK_MOVEMENT.TO_LOCATION_ID))
            .join(USERS).on(USERS.ID.eq(STOCK_MOVEMENT.CREATED_BY))
            .where(cardRepository.movementConditions(id, filter, zone))
            .orderBy(STOCK_MOVEMENT.ID.desc())
            .limit(pageSize + 1)
            .fetch { r ->
                StockMovementDto(
                    id = r.get(STOCK_MOVEMENT.ID)!!,
                    type = StockMovementType.valueOf(r.get(STOCK_MOVEMENT.MOVEMENT_TYPE)!!),
                    nomenclatureId = r.get(STOCK_MOVEMENT.NOMENCLATURE_ID)!!,
                    lotNumber = r.get(LOTS.LOT_NUMBER),
                    serialNumber = r.get(SERIAL_ITEMS.SERIAL_NUMBER),
                    fromLocationCode = r.get(slFrom.CODE),
                    toLocationCode = r.get(slTo.CODE),
                    quantity = r.get(STOCK_MOVEMENT.QUANTITY)!!.plain(),
                    documentType = r.get(STOCK_MOVEMENT.DOCUMENT_TYPE)!!,
                    documentId = r.get(STOCK_MOVEMENT.DOCUMENT_ID)!!,
                    note = r.get(STOCK_MOVEMENT.NOTE),
                    createdBy = r.get(STOCK_MOVEMENT.CREATED_BY)!!,
                    createdByName = r.get(USERS.FULL_NAME)!!,
                    createdAt = r.get(STOCK_MOVEMENT.CREATED_AT)?.toString()
                )
            }
        val page = rows.take(pageSize).let { items ->
            // Номер документа вместо id — как в журнале склада (W-10).
            val numbers = wmsRepository.documentNumbers(items.map { it.documentType to it.documentId })
            items.map { it.copy(documentNumber = numbers[it.documentType.uppercase() to it.documentId]) }
        }
        return NomenclatureMovementsPageDto(
            items = page,
            nextBefore = if (rows.size > pageSize) page.last().id else null
        )
    }

    fun reservations(id: Long, currentUser: CurrentUser): List<NomenclatureReservationRowDto> {
        currentUser.requirePermission("wms", "read")
        if (wmsRepository.findNomenclatureById(id) == null) throw NotFoundException("nomenclature", id)
        return cardRepository.reservations(id).map { row ->
            val reasonType = ReservationReasonType.valueOf(row.reasonType)
            NomenclatureReservationRowDto(
                id = row.id,
                number = row.number,
                reasonType = reasonType,
                reasonLabel = when (reasonType) {
                    ReservationReasonType.PROJECT -> row.projectLabel?.let { "Проект $it" } ?: "Проект"
                    ReservationReasonType.DEPARTMENT_TASK -> row.taskLabel?.let { "Задача $it" } ?: "Задача подразделения"
                    ReservationReasonType.PRODUCTION_ORDER -> "Производственный заказ"
                    ReservationReasonType.STOCKPILE -> "Задел"
                    ReservationReasonType.REPAIR -> "Ремонт"
                    ReservationReasonType.OTHER -> "Прочее"
                },
                reasonNote = row.reasonNote,
                projectId = row.projectId,
                taskId = row.taskId,
                warehouseName = row.warehouseName,
                locationCode = row.locationCode,
                lotNumber = row.lotNumber,
                serialNumber = row.serialNumber,
                quantity = row.quantity.plain(),
                issuedQuantity = row.issuedQuantity.plain(),
                status = ReservationStatus.valueOf(row.status),
                ownerName = row.ownerName,
                expiresAt = row.expiresAt?.toString(),
                createdAt = row.createdAt?.toString()
            )
        }
    }

    fun usage(id: Long, currentUser: CurrentUser): NomenclatureUsageDto {
        currentUser.requirePermission("wms", "read")
        if (wmsRepository.findNomenclatureById(id) == null) throw NotFoundException("nomenclature", id)
        return NomenclatureUsageDto(
            bomLines = cardRepository.bomUsage(id, asParent = false).map { it.toDto() },
            parentBomLines = cardRepository.bomUsage(id, asParent = true).map { it.toDto() },
            demands = cardRepository.demandUsage(id).map {
                NomenclatureUsageDemandDto(
                    it.demandId, it.number, it.status, it.sourceType, it.projectName,
                    it.quantity.plain(), it.covered.plain(), it.unit
                )
            },
            purchaseOrders = cardRepository.purchaseOrderUsage(id).map {
                NomenclatureUsagePurchaseOrderDto(
                    it.orderId, it.number, it.status, it.supplierName,
                    it.quantity.plain(), it.delivered.plain(), it.unit
                )
            },
            productionOrders = cardRepository.productionUsage(id).map {
                NomenclatureUsageProductionOrderDto(
                    it.orderId, it.number, it.status, it.planned.plain(), it.consumed.plain(), it.unit
                )
            },
            archiveBlockers = wmsRepository.findActiveNomenclatureReferences(id).map { nomenclatureReferenceLabel(it) }
        )
    }

    fun history(id: Long, currentUser: CurrentUser): List<NomenclatureHistoryEntryDto> {
        currentUser.requirePermission("wms", "read")
        if (wmsRepository.findNomenclatureById(id) == null) throw NotFoundException("nomenclature", id)
        return cardRepository.history(id, 200).map { row ->
            val payload = parsePayload(row.payload)
            NomenclatureHistoryEntryDto(
                id = row.id,
                action = row.action,
                title = historyTitle(row.action),
                details = historyDetails(row.action, payload),
                userName = row.userName,
                createdAt = row.createdAt.toString()
            )
        }
    }

    // --- Расчёты ---

    internal fun buildStockSummary(
        record: NomenclatureRecord,
        rows: List<WmsRepository.StockRow>,
        warehouses: Map<Long, org.example.project.generated.jooq.tables.records.WarehousesRecord>,
        inTransitByWarehouse: Map<Long, BigDecimal>,
        expected: List<NomenclatureCardRepository.ExpectedSupplyRow>
    ): NomenclatureStockSummaryDto {
        class Acc {
            var physical: BigDecimal = BigDecimal.ZERO
            var available: BigDecimal = BigDecimal.ZERO
            var reserved: BigDecimal = BigDecimal.ZERO
            var quarantine: BigDecimal = BigDecimal.ZERO
            var defect: BigDecimal = BigDecimal.ZERO
            var temporary: BigDecimal = BigDecimal.ZERO
        }
        val total = Acc()
        val byWarehouse = linkedMapOf<Long, Acc>()
        rows.forEach { row ->
            val balance = row.balance
            listOf(total, byWarehouse.getOrPut(balance.warehouseId) { Acc() }).forEach { acc ->
                acc.physical += balance.physicalQty
                acc.reserved += balance.reservedQty
                when (row.locationPurpose) {
                    "QUARANTINE" -> acc.quarantine += balance.physicalQty
                    "DEFECT" -> acc.defect += balance.physicalQty
                    "TEMPORARY" -> acc.temporary += balance.physicalQty
                    else -> acc.available += balance.availableQty
                }
            }
        }
        inTransitByWarehouse.keys.forEach { byWarehouse.getOrPut(it) { Acc() } }
        val inTransitTotal = inTransitByWarehouse.values.fold(BigDecimal.ZERO, BigDecimal::add)
        val expectedTotal = expected.fold(BigDecimal.ZERO) { acc, row -> acc + row.remaining.max(BigDecimal.ZERO) }
        val minStock = record.minStock ?: BigDecimal.ZERO
        val safetyStock = record.safetyStock ?: BigDecimal.ZERO
        return NomenclatureStockSummaryDto(
            physicalQty = total.physical.plain(),
            availableQty = total.available.plain(),
            reservedQty = total.reserved.plain(),
            expectedQty = expectedTotal.plain(),
            quarantineQty = total.quarantine.plain(),
            defectQty = total.defect.plain(),
            inTransitQty = inTransitTotal.plain(),
            temporaryQty = total.temporary.plain(),
            belowMinStock = minStock.signum() > 0 && total.available < minStock,
            belowSafetyStock = safetyStock.signum() > 0 && total.available < safetyStock,
            byWarehouse = byWarehouse.map { (warehouseId, acc) ->
                val warehouse = warehouses[warehouseId]
                WarehouseStockSummaryDto(
                    warehouseId = warehouseId,
                    warehouseCode = warehouse?.code ?: "#$warehouseId",
                    warehouseName = warehouse?.name ?: "Склад $warehouseId",
                    physicalQty = acc.physical.plain(),
                    availableQty = acc.available.plain(),
                    reservedQty = acc.reserved.plain(),
                    quarantineQty = acc.quarantine.plain(),
                    defectQty = acc.defect.plain(),
                    inTransitQty = (inTransitByWarehouse[warehouseId] ?: BigDecimal.ZERO).plain()
                )
            }.sortedBy { it.warehouseCode }
        )
    }

    /** Незакрытые потребности — показываются в «Сформировать потребность», чтобы не завести дубль. */
    internal fun openDemandLabels(demandUsage: List<NomenclatureCardRepository.DemandUsageRow>): List<String> =
        demandUsage
            .filter { it.status !in setOf("COVERED", "CANCELLED") }
            .map { "${it.number} · ${it.quantity.plain()} ${it.unit}" }

    internal fun suggestDemand(
        record: NomenclatureRecord,
        summary: NomenclatureStockSummaryDto,
        demandUsage: List<NomenclatureCardRepository.DemandUsageRow>
    ): NomenclatureDemandSuggestionDto? {
        if (record.archived == true) return null
        val safety = record.safetyStock ?: BigDecimal.ZERO
        val min = record.minStock ?: BigDecimal.ZERO
        val target = if (safety.signum() > 0) safety else min
        if (target.signum() <= 0) return null
        val deficit = target - BigDecimal(summary.availableQty) - BigDecimal(summary.expectedQty)
        if (deficit.signum() <= 0) return null
        val multiple = (record.orderMultiple ?: BigDecimal.ONE).takeIf { it.signum() > 0 } ?: BigDecimal.ONE
        val minOrder = (record.minOrderQty ?: BigDecimal.ONE).takeIf { it.signum() > 0 } ?: BigDecimal.ONE
        // Сначала не меньше минимальной партии, затем вверх до кратности: так
        // результат кратен заказу, даже если минимальная партия ему не кратна.
        val suggested = deficit.max(minOrder).divide(multiple, 0, RoundingMode.CEILING).multiply(multiple)
        val leadTime = record.leadTimeDays ?: 0
        val openDemands = openDemandLabels(demandUsage)
        return NomenclatureDemandSuggestionDto(
            targetQty = target.plain(),
            deficitQty = deficit.plain(),
            suggestedQty = suggested.plain(),
            requiredDate = if (leadTime > 0) today().plusDays(leadTime.toLong()).toString() else null,
            openDemands = openDemands
        )
    }

    // --- Вспомогательное ---

    private fun countDocuments(id: Long, currentUser: CurrentUser): Int {
        val hasReadAny = currentUser.hasPermission("documents", "read_any")
        val visibility = if (hasReadAny) "" else " AND (d.confidentiality IN ('PUBLIC', 'INTERNAL') OR d.owner_id = ?)"
        val sql = """
            SELECT COUNT(*) AS c FROM documents d
            JOIN document_links l ON l.document_id = d.id
            WHERE l.nomenclature_id = ?$visibility
        """.trimIndent()
        val record = if (hasReadAny) dsl.fetchOne(sql, id) else dsl.fetchOne(sql, id, currentUser.userId)
        return record?.get("c", Int::class.java) ?: 0
    }

    private fun loadBarcodes(id: Long): Map<Long, List<NomenclatureBarcodeDto>> =
        wmsRepository.listBarcodesFor(listOf(id)).groupBy({ it.nomenclatureId }, { it.toBarcodeDto() })

    private fun normalizeAttributes(input: List<NomenclatureAttributeInput>): List<NomenclatureCardRepository.AttributeValue> {
        val result = input.mapIndexedNotNull { index, attribute ->
            val name = attribute.name.trim()
            val value = attribute.value.trim()
            when {
                name.isEmpty() && value.isEmpty() -> null
                name.isEmpty() -> throw ValidationException("Укажите название характеристики", mapOf("attributes[$index].name" to "required"))
                value.isEmpty() -> throw ValidationException("Укажите значение характеристики «$name»", mapOf("attributes[$index].value" to "required"))
                name.length > 255 -> throw ValidationException("Слишком длинное название характеристики", mapOf("attributes[$index].name" to "too_long"))
                value.length > 1000 -> throw ValidationException("Слишком длинное значение характеристики", mapOf("attributes[$index].value" to "too_long"))
                else -> NomenclatureCardRepository.AttributeValue(name, value, attribute.unit.clean()?.take(32))
            }
        }
        val duplicates = result.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ValidationException("Характеристика указана дважды: ${duplicates.first()}", mapOf("attributes" to "duplicate"))
        }
        return result
    }

    private fun changedFields(
        current: NomenclatureRecord,
        next: NomenclatureCardRepository.CardFields,
        attributesChanged: Boolean,
        warehousesChanged: Boolean
    ): List<String> {
        fun same(a: BigDecimal?, b: BigDecimal?) = (a == null && b == null) || (a != null && b != null && a.compareTo(b) == 0)
        val changed = mutableListOf<String>()
        if (current.name != next.name) changed += "name"
        if (current.description != next.description) changed += "description"
        if (current.categoryId != next.categoryId) changed += "categoryId"
        if (current.article != next.article) changed += "article"
        if (current.manufacturer != next.manufacturer) changed += "manufacturer"
        if (current.manufacturerModel != next.manufacturerModel) changed += "manufacturerModel"
        if (!same(current.weightKg, next.weightKg)) changed += "weightKg"
        if (!same(current.lengthMm, next.lengthMm) || !same(current.widthMm, next.widthMm) || !same(current.heightMm, next.heightMm)) {
            changed += "dimensions"
        }
        if (current.storageConditions != next.storageConditions) changed += "storageConditions"
        if (current.productModelId != next.productModelId) changed += "productModelId"
        if (!same(current.minStock, next.minStock)) changed += "minStock"
        if (!same(current.safetyStock, next.safetyStock)) changed += "safetyStock"
        if ((current.leadTimeDays ?: 0) != next.leadTimeDays) changed += "leadTimeDays"
        if (!same(current.minOrderQty, next.minOrderQty)) changed += "minOrderQty"
        if (!same(current.orderMultiple, next.orderMultiple)) changed += "orderMultiple"
        if (attributesChanged) changed += "attributes"
        if (warehousesChanged) changed += "allowedWarehouses"
        return changed
    }

    private fun parsePayload(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(raw).jsonObject.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.content?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun historyTitle(action: String): String = when (action) {
        "CREATE" -> "Позиция создана"
        "UPDATE" -> "Карточка изменена"
        "UPDATE_THRESHOLDS" -> "Изменены пороги запаса"
        "ARCHIVE" -> "Позиция архивирована"
        "ADD_BARCODE" -> "Добавлен штрихкод"
        "DELETE_BARCODE" -> "Удалён штрихкод"
        "ADD_ANALOG" -> "Добавлен аналог"
        "DELETE_ANALOG" -> "Удалён аналог"
        "ADD_UNIT_CONVERSION" -> "Добавлен пересчёт единиц"
        "DELETE_UNIT_CONVERSION" -> "Удалён пересчёт единиц"
        "IMPORT" -> "Импортирована"
        else -> action
    }

    private fun historyDetails(action: String, payload: Map<String, String>): String? = when (action) {
        "CREATE" -> payload["code"]?.let { "Код $it" }
        "UPDATE" -> payload["fields"]?.split(',')?.filter { it.isNotBlank() }
            ?.joinToString(", ") { FIELD_LABELS[it] ?: it }
            ?.ifEmpty { null }
            ?.let { "Изменено: $it" } ?: "Без изменений значений"
        "ADD_BARCODE" -> payload["barcode"]
        "ADD_ANALOG" -> payload["analogCode"]?.let { code ->
            code + if (payload["requiresApproval"] == "true") " · требует согласования" else ""
        }
        "ADD_UNIT_CONVERSION" -> listOfNotNull(payload["fromUnit"], payload["toUnit"]).takeIf { it.size == 2 }
            ?.let { "1 ${it[0]} = ${payload["factor"] ?: "?"} ${it[1]}" }
        else -> null
    }

    private fun parseDate(raw: String?, field: String): LocalDate? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw ValidationException("Неверная дата: $value", mapOf(field to "invalid"))
        }
    }

    private fun Record.toAnalogDto(): NomenclatureAnalogDto = NomenclatureAnalogDto(
        id = get(NOMENCLATURE_ANALOGS.ID)!!,
        nomenclatureId = get(NOMENCLATURE_ANALOGS.NOMENCLATURE_ID)!!,
        analogNomenclatureId = get(NOMENCLATURE_ANALOGS.ANALOG_NOMENCLATURE_ID)!!,
        analogCode = get(NOMENCLATURE.CODE)!!,
        analogName = get(NOMENCLATURE.NAME)!!,
        conversionFactor = get(NOMENCLATURE_ANALOGS.CONVERSION_FACTOR)!!.plain(),
        applicabilityCondition = get(NOMENCLATURE_ANALOGS.APPLICABILITY_CONDITION),
        priority = get(NOMENCLATURE_ANALOGS.PRIORITY)!!,
        active = get(NOMENCLATURE_ANALOGS.ACTIVE)!!,
        requiresApproval = get(NOMENCLATURE_ANALOGS.REQUIRES_APPROVAL) ?: false
    )

    private fun NomenclatureCardRepository.BomUsageRow.toDto() = BomWhereUsedItemDto(
        productModelId = productModelId,
        modelCode = modelCode,
        modelName = modelName,
        revisionId = revisionId,
        revisionNumber = revisionNumber,
        revisionStatus = BomRevisionStatus.valueOf(revisionStatus),
        lineId = lineId,
        quantity = quantity.plain(),
        unit = unit,
        level = level,
        position = position
    )

    private fun NomenclatureCategoriesRecord.toDto(all: Map<Long, NomenclatureCategoriesRecord>): NomenclatureCategoryDto {
        val names = ArrayDeque<String>()
        var cursor: NomenclatureCategoriesRecord? = this
        val seen = mutableSetOf<Long>()
        while (cursor != null && seen.add(cursor.id)) {
            names.addFirst(cursor.name)
            cursor = cursor.parentId?.let { all[it] }
        }
        return NomenclatureCategoryDto(
            id = id,
            parentId = parentId,
            code = code,
            name = name,
            path = names.joinToString(" / "),
            archived = archived ?: false,
            version = version ?: 0
        )
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun requireMaxLength(value: String?, max: Int, field: String, label: String) {
        val length = value?.trim()?.length ?: 0
        if (length > max) {
            throw ValidationException("$label — не больше $max символов", mapOf(field to "too_long"))
        }
    }

    /** Граница значения по точности колонки: иначе БД ответит переполнением и 500. */
    private fun BigDecimal.inRange(field: String, max: BigDecimal, scale: Int): BigDecimal {
        if (this >= max) throw ValidationException("Слишком большое значение", mapOf(field to "too_large"))
        if (this.stripTrailingZeros().scale() > scale) {
            throw ValidationException("Не больше $scale знаков после запятой", mapOf(field to "scale"))
        }
        return this
    }

    /**
     * Уникальность проверяется заранее для понятного сообщения, но при гонке её
     * ловит ограничение БД — тогда ответ тоже 409, а не 500.
     */
    private fun <T> uniqueAsConflict(message: String, block: () -> T): T =
        try {
            block()
        } catch (e: org.jooq.exception.DataAccessException) {
            if (e.sqlState() == "23505") throw ConflictException(message) else throw e
        }

    private fun nonNegative(raw: String, field: String): BigDecimal {
        val value = raw.trim().replace(',', '.').ifEmpty { "0" }.toBigDecimalOrNull()
            ?: throw ValidationException("Неверное число: $raw", mapOf(field to "invalid"))
        if (value.signum() < 0) throw ValidationException("Значение не может быть отрицательным", mapOf(field to "negative"))
        return value
    }

    private fun positive(raw: String, field: String): BigDecimal {
        val value = raw.trim().replace(',', '.').toBigDecimalOrNull()
            ?: throw ValidationException("Неверное число: $raw", mapOf(field to "invalid"))
        if (value.signum() <= 0) throw ValidationException("Значение должно быть больше нуля", mapOf(field to "not_positive"))
        return value
    }

    private fun positiveOrNull(raw: String?, field: String): BigDecimal? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return positive(value, field)
    }

    companion object {
        private val RESTRICTED_PURPOSES = setOf("QUARANTINE", "DEFECT", "TEMPORARY")
        private val QTY_MAX = BigDecimal("1E15")        // NUMERIC(19,4)
        private val WEIGHT_MAX = BigDecimal("1E15")     // NUMERIC(19,4)
        private val DIMENSION_MAX = BigDecimal("1E17")  // NUMERIC(19,2)

        private val FIELD_LABELS = mapOf(
            "name" to "наименование",
            "description" to "описание",
            "categoryId" to "категория",
            "article" to "артикул",
            "manufacturer" to "производитель",
            "manufacturerModel" to "модель производителя",
            "weightKg" to "масса",
            "dimensions" to "габариты",
            "storageConditions" to "условия хранения",
            "productModelId" to "связь с изделием",
            "minStock" to "минимальный запас",
            "safetyStock" to "страховой запас",
            "leadTimeDays" to "срок поставки",
            "minOrderQty" to "минимальная партия",
            "orderMultiple" to "кратность заказа",
            "attributes" to "характеристики",
            "allowedWarehouses" to "допустимые склады"
        )

        internal fun signedDelta(type: String, quantity: BigDecimal): BigDecimal = when (type) {
            "RECEIPT", "RETURN", "ADJUSTMENT_IN" -> quantity
            "ISSUE", "WRITE_OFF", "ADJUSTMENT_OUT" -> quantity.negate()
            else -> BigDecimal.ZERO // TRANSFER меняет ячейку, а не общий остаток
        }
    }
}

private fun BigDecimal.plain(): String = stripTrailingZeros().toPlainString()
