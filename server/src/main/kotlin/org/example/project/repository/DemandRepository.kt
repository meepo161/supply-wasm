package org.example.project.repository

import org.example.project.dto.DemandLinkedReceiptDto
import org.example.project.generated.jooq.tables.DemandHistory.DEMAND_HISTORY
import org.example.project.generated.jooq.tables.DemandLineHistory.DEMAND_LINE_HISTORY
import org.example.project.generated.jooq.tables.DemandLines.DEMAND_LINES
import org.example.project.generated.jooq.tables.Demands.DEMANDS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.PurchaseOrderDemands.PURCHASE_ORDER_DEMANDS
import org.example.project.generated.jooq.tables.Receipts.RECEIPTS
import org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.Warehouses.WAREHOUSES
import org.example.project.generated.jooq.tables.ProjectMembers.PROJECT_MEMBERS
import org.example.project.generated.jooq.tables.Projects.PROJECTS
import org.example.project.generated.jooq.tables.Reservations.RESERVATIONS
import org.example.project.generated.jooq.tables.StockBalance.STOCK_BALANCE
import org.example.project.generated.jooq.tables.StorageLocations.STORAGE_LOCATIONS
import org.example.project.generated.jooq.tables.Warehouses.WAREHOUSES
import org.example.project.generated.jooq.tables.records.DemandLinesRecord
import org.example.project.generated.jooq.tables.records.DemandsRecord
import org.example.project.generated.jooq.tables.records.StockBalanceRecord
import org.example.project.generated.jooq.tables.records.SupplierOffersRecord
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

class DemandRepository(private val dsl: DSLContext) {

    data class NewDemandLine(
        val lineNo: Int,
        val nomenclatureId: Long,
        val quantity: BigDecimal,
        val unit: String,
        val sourceBomRevisionId: Long? = null
    )

    data class DemandHeaderProjection(
        val id: Long,
        val number: String,
        val sourceType: String,
        val projectId: Long?,
        val stockpileNote: String?,
        val initiatorId: Long,
        val requiredDate: LocalDate?,
        val priority: String,
        val status: String,
        val approvedBy: Long?,
        val approvedAt: OffsetDateTime?,
        val createdAt: OffsetDateTime?,
        val createdBy: Long,
        val version: Long,
        val sourceBomRevisionId: Long?,
        val bomReleaseQuantity: BigDecimal?,
        val bomRevisionVersion: Long?,
        val idempotencyKey: String?
    )

    fun findById(id: Long): DemandsRecord? {
        return dsl.selectFrom(DEMANDS)
            .where(DEMANDS.ID.eq(id))
            .fetchOne()
    }

    fun findHeaderById(id: Long): DemandHeaderProjection? {
        val sourceBomRevisionId = DSL.field("source_bom_revision_id", Long::class.java)
        val bomReleaseQuantity = DSL.field("bom_release_quantity", BigDecimal::class.java)
        val bomRevisionVersion = DSL.field("bom_revision_version", Long::class.java)
        val idempotencyKey = DSL.field("idempotency_key", String::class.java)
        return dsl.select(
            DEMANDS.ID,
            DEMANDS.NUMBER,
            DEMANDS.SOURCE_TYPE,
            DEMANDS.PROJECT_ID,
            DEMANDS.STOCKPILE_NOTE,
            DEMANDS.INITIATOR_ID,
            DEMANDS.REQUIRED_DATE,
            DEMANDS.PRIORITY,
            DEMANDS.STATUS,
            DEMANDS.APPROVED_BY,
            DEMANDS.APPROVED_AT,
            DEMANDS.CREATED_AT,
            DEMANDS.CREATED_BY,
            DEMANDS.VERSION,
            sourceBomRevisionId,
            bomReleaseQuantity,
            bomRevisionVersion,
            idempotencyKey
        )
            .from(DEMANDS)
            .where(DEMANDS.ID.eq(id))
            .fetchOne { record ->
                DemandHeaderProjection(
                    id = record.get(DEMANDS.ID)!!,
                    number = record.get(DEMANDS.NUMBER)!!,
                    sourceType = record.get(DEMANDS.SOURCE_TYPE)!!,
                    projectId = record.get(DEMANDS.PROJECT_ID),
                    stockpileNote = record.get(DEMANDS.STOCKPILE_NOTE),
                    initiatorId = record.get(DEMANDS.INITIATOR_ID)!!,
                    requiredDate = record.get(DEMANDS.REQUIRED_DATE),
                    priority = record.get(DEMANDS.PRIORITY)!!,
                    status = record.get(DEMANDS.STATUS)!!,
                    approvedBy = record.get(DEMANDS.APPROVED_BY),
                    approvedAt = record.get(DEMANDS.APPROVED_AT),
                    createdAt = record.get(DEMANDS.CREATED_AT),
                    createdBy = record.get(DEMANDS.CREATED_BY)!!,
                    version = record.get(DEMANDS.VERSION) ?: 0,
                    sourceBomRevisionId = record.get(sourceBomRevisionId),
                    bomReleaseQuantity = record.get(bomReleaseQuantity),
                    bomRevisionVersion = record.get(bomRevisionVersion),
                    idempotencyKey = record.get(idempotencyKey)
                )
            }
    }

    fun findByIdempotencyKey(key: String, userId: Long): DemandsRecord? {
        return dsl.selectFrom(DEMANDS)
            .where(DSL.field("idempotency_key", String::class.java).eq(key))
            .and(DEMANDS.CREATED_BY.eq(userId))
            .fetchOne()
    }

    fun listAccessible(userId: Long, readAny: Boolean): List<DemandsRecord> {
        return if (readAny) {
            dsl.selectFrom(DEMANDS)
                .orderBy(DEMANDS.CREATED_AT.desc())
                .fetch()
        } else {
            val projectIds = dsl.select(PROJECT_MEMBERS.PROJECT_ID)
                .from(PROJECT_MEMBERS)
                .where(PROJECT_MEMBERS.USER_ID.eq(userId))
            dsl.selectFrom(DEMANDS)
                .where(
                    DEMANDS.INITIATOR_ID.eq(userId)
                        .or(DEMANDS.CREATED_BY.eq(userId))
                        .or(DEMANDS.PROJECT_ID.`in`(projectIds))
                )
                .orderBy(DEMANDS.CREATED_AT.desc())
                .fetch()
        }
    }

    /** Потребности проекта для вкладки «Потребности» карточки (PRJ-15). */
    fun listByProject(projectId: Long): List<DemandsRecord> =
        dsl.selectFrom(DEMANDS)
            .where(DEMANDS.PROJECT_ID.eq(projectId))
            .orderBy(DEMANDS.CREATED_AT.desc())
            .fetch()

    fun countByProject(projectId: Long): Int =
        dsl.fetchCount(DEMANDS, DEMANDS.PROJECT_ID.eq(projectId))

    fun createDemand(
        number: String,
        sourceType: String,
        projectId: Long?,
        stockpileNote: String?,
        initiatorId: Long,
        requiredDate: LocalDate?,
        priority: String,
        createdBy: Long,
        sourceBomRevisionId: Long?,
        bomReleaseQuantity: BigDecimal?,
        bomRevisionVersion: Long?,
        idempotencyKey: String?,
        idempotencyRequestHash: String? = null,
        status: String = "APPROVED",
        approvedBy: Long? = null,
        approvedAt: OffsetDateTime? = null
    ): DemandHeaderProjection {
        val insert = dsl.insertInto(DEMANDS)
            .set(DEMANDS.NUMBER, number)
            .set(DEMANDS.SOURCE_TYPE, sourceType)
            .set(DEMANDS.PROJECT_ID, projectId)
            .set(DEMANDS.STOCKPILE_NOTE, stockpileNote)
            .set(DEMANDS.INITIATOR_ID, initiatorId)
            .set(DEMANDS.REQUIRED_DATE, requiredDate)
            .set(DEMANDS.PRIORITY, priority)
            .set(DEMANDS.STATUS, status)
            .set(DEMANDS.CREATED_BY, createdBy)

        approvedBy?.let { insert.set(DEMANDS.APPROVED_BY, it) }
        approvedAt?.let { insert.set(DEMANDS.APPROVED_AT, it) }

        sourceBomRevisionId?.let {
            insert.set(DSL.field("source_bom_revision_id", Long::class.java), it)
        }
        bomReleaseQuantity?.let {
            insert.set(DSL.field("bom_release_quantity", BigDecimal::class.java), it)
        }
        bomRevisionVersion?.let {
            insert.set(DSL.field("bom_revision_version", Long::class.java), it)
        }
        idempotencyKey?.let {
            insert.set(DSL.field("idempotency_key", String::class.java), it)
        }
        idempotencyRequestHash?.let {
            insert.set(DSL.field("idempotency_request_hash", String::class.java), it)
        }

        val record = insert.returning().fetchOne()!!
        return findHeaderById(record.id)!!
    }

    fun createDemandLines(demandId: Long, lines: List<NewDemandLine>, createdBy: Long): List<DemandLinesRecord> {
        return lines.map { line ->
            val insert = dsl.insertInto(DEMAND_LINES)
                .set(DEMAND_LINES.DEMAND_ID, demandId)
                .set(DEMAND_LINES.LINE_NO, line.lineNo)
                .set(DEMAND_LINES.NOMENCLATURE_ID, line.nomenclatureId)
                .set(DEMAND_LINES.QUANTITY, line.quantity)
                .set(DEMAND_LINES.UNIT, line.unit)
                .set(DEMAND_LINES.STATUS, "OPEN")
                .set(DEMAND_LINES.CREATED_BY, createdBy)
            line.sourceBomRevisionId?.let { revisionId ->
                insert.set(DEMAND_LINES.SOURCE_BOM_REVISION_ID, revisionId)
            }
            insert.returning().fetchOne()!!
        }
    }

    fun listLines(demandId: Long): List<DemandLinesRecord> {
        return dsl.selectFrom(DEMAND_LINES)
            .where(DEMAND_LINES.DEMAND_ID.eq(demandId))
            .orderBy(DEMAND_LINES.LINE_NO.asc())
            .fetch()
    }

    fun transitionStatus(
        id: Long,
        expectedVersion: Long,
        fromStatus: String,
        newStatus: String,
        approvedBy: Long? = null,
        approvedAt: OffsetDateTime? = null
    ): DemandsRecord? {
        val update = dsl.update(DEMANDS)
            .set(DEMANDS.STATUS, newStatus)
            .set(DEMANDS.VERSION, DEMANDS.VERSION.plus(1))
        approvedBy?.let { update.set(DEMANDS.APPROVED_BY, it) }
        approvedAt?.let { update.set(DEMANDS.APPROVED_AT, it) }
        return update.where(DEMANDS.ID.eq(id))
            .and(DEMANDS.VERSION.eq(expectedVersion))
            .and(DEMANDS.STATUS.eq(fromStatus))
            .returning()
            .fetchOne()
    }

    fun cancelDemand(id: Long, expectedVersion: Long, fromStatuses: Set<String>): DemandsRecord? {
        return dsl.update(DEMANDS)
            .set(DEMANDS.STATUS, "CANCELLED")
            .set(DEMANDS.VERSION, DEMANDS.VERSION.plus(1))
            .where(DEMANDS.ID.eq(id))
            .and(DEMANDS.VERSION.eq(expectedVersion))
            .and(DEMANDS.STATUS.`in`(fromStatuses))
            .returning()
            .fetchOne()
    }

    fun cancelAllLines(demandId: Long): Int {
        return dsl.update(DEMAND_LINES)
            .set(DEMAND_LINES.STATUS, "CANCELLED")
            .set(DEMAND_LINES.VERSION, DEMAND_LINES.VERSION.plus(1))
            .where(DEMAND_LINES.DEMAND_ID.eq(demandId))
            .execute()
    }

    fun insertDemandHistory(
        demandId: Long,
        action: String,
        fromStatus: String?,
        toStatus: String?,
        reason: String?,
        changedBy: Long
    ) {
        dsl.insertInto(DEMAND_HISTORY)
            .set(DEMAND_HISTORY.DEMAND_ID, demandId)
            .set(DEMAND_HISTORY.ACTION, action)
            .set(DEMAND_HISTORY.FROM_STATUS, fromStatus)
            .set(DEMAND_HISTORY.TO_STATUS, toStatus)
            .set(DEMAND_HISTORY.REASON, reason)
            .set(DEMAND_HISTORY.CHANGED_BY, changedBy)
            .execute()
    }

    fun insertDemandLineHistory(
        demandLineId: Long,
        action: String,
        quantityBefore: BigDecimal?,
        quantityAfter: BigDecimal?,
        fromStatus: String?,
        toStatus: String?,
        reason: String?,
        changedBy: Long
    ) {
        dsl.insertInto(DEMAND_LINE_HISTORY)
            .set(DEMAND_LINE_HISTORY.DEMAND_LINE_ID, demandLineId)
            .set(DEMAND_LINE_HISTORY.ACTION, action)
            .set(DEMAND_LINE_HISTORY.QUANTITY_BEFORE, quantityBefore)
            .set(DEMAND_LINE_HISTORY.QUANTITY_AFTER, quantityAfter)
            .set(DEMAND_LINE_HISTORY.FROM_STATUS, fromStatus)
            .set(DEMAND_LINE_HISTORY.TO_STATUS, toStatus)
            .set(DEMAND_LINE_HISTORY.REASON, reason)
            .set(DEMAND_LINE_HISTORY.CHANGED_BY, changedBy)
            .execute()
    }

    fun projectReservations(nomenclatureIds: Set<Long>, projectId: Long?): Map<Long, BigDecimal> {
        if (nomenclatureIds.isEmpty() || projectId == null) return emptyMap()
        return dsl.select(
            RESERVATIONS.NOMENCLATURE_ID,
            DSL.sum(RESERVATIONS.QUANTITY.minus(RESERVATIONS.ISSUED_QUANTITY))
        )
            .from(RESERVATIONS)
            .where(RESERVATIONS.NOMENCLATURE_ID.`in`(nomenclatureIds))
            .and(RESERVATIONS.PROJECT_ID.eq(projectId))
            .and(RESERVATIONS.REASON_TYPE.eq("PROJECT"))
            .and(RESERVATIONS.STATUS.eq("ACTIVE"))
            .and(RESERVATIONS.QUANTITY.gt(RESERVATIONS.ISSUED_QUANTITY))
            .groupBy(RESERVATIONS.NOMENCLATURE_ID)
            .fetchMap(RESERVATIONS.NOMENCLATURE_ID) { record: Record ->
                record.get(DSL.sum(RESERVATIONS.QUANTITY.minus(RESERVATIONS.ISSUED_QUANTITY)))
                    ?: BigDecimal.ZERO
            }
    }

    /** Строка потребности для списка: название номенклатуры, количество и единица. */
    data class LineSummary(val demandId: Long, val name: String, val quantity: BigDecimal, val unit: String)

    /** Позиции потребностей с названиями номенклатуры — для колонок списка. */
    fun lineSummaries(demandIds: Collection<Long>): Map<Long, List<LineSummary>> {
        if (demandIds.isEmpty()) return emptyMap()
        return dsl.select(DEMAND_LINES.DEMAND_ID, NOMENCLATURE.NAME, DEMAND_LINES.QUANTITY, DEMAND_LINES.UNIT)
            .from(DEMAND_LINES)
            .join(NOMENCLATURE).on(NOMENCLATURE.ID.eq(DEMAND_LINES.NOMENCLATURE_ID))
            .where(DEMAND_LINES.DEMAND_ID.`in`(demandIds))
            .orderBy(DEMAND_LINES.DEMAND_ID.asc(), DEMAND_LINES.LINE_NO.asc())
            .fetch()
            .map {
                LineSummary(
                    demandId = it.get(DEMAND_LINES.DEMAND_ID)!!,
                    name = it.get(NOMENCLATURE.NAME)!!,
                    quantity = it.get(DEMAND_LINES.QUANTITY)!!,
                    unit = it.get(DEMAND_LINES.UNIT)!!
                )
            }
            .groupBy { it.demandId }
    }

    /** Сколько предложений поставщиков внесено по потребности. */
    fun countOffers(demandId: Long): Int =
        dsl.fetchCount(SUPPLIER_OFFERS, SUPPLIER_OFFERS.DEMAND_ID.eq(demandId))

    /** Сколько заказов поставщикам закрывают потребность. */
    fun countOrders(demandId: Long): Int =
        dsl.fetchCount(PURCHASE_ORDER_DEMANDS, PURCHASE_ORDER_DEMANDS.DEMAND_ID.eq(demandId))

    data class DemandHeaderEnrichment(
        val selectedSupplierName: String? = null,
        val selectedGrossTotal: String? = null,
        val isPaid: Boolean = false,
        val paidAt: String? = null
    )

    data class HeaderEnrichments(
        val initiatorNames: Map<Long, String>,
        val byDemand: Map<Long, DemandHeaderEnrichment>
    )

    fun headerEnrichments(demandIds: Collection<Long>, initiatorIds: Collection<Long>): HeaderEnrichments {
        val distinctUserIds = initiatorIds.distinct()
        val initiatorNames = if (distinctUserIds.isEmpty()) {
            emptyMap()
        } else {
            dsl.select(USERS.ID, USERS.FULL_NAME)
                .from(USERS)
                .where(USERS.ID.`in`(distinctUserIds))
                .fetch()
                .associate { it.get(USERS.ID)!! to it.get(USERS.FULL_NAME)!! }
        }
        val distinctDemandIds = demandIds.distinct()
        if (distinctDemandIds.isEmpty()) {
            return HeaderEnrichments(initiatorNames, emptyMap())
        }
        val placeholders = distinctDemandIds.joinToString(",") { "?" }
        val selectedRows = dsl.fetch(
            """
            SELECT so.demand_id, s.name AS supplier_name, sot.gross_total
            FROM supplier_offers so
                     JOIN suppliers s ON s.id = so.supplier_id
                     LEFT JOIN supplier_offer_totals sot ON sot.offer_id = so.id
            WHERE so.demand_id IN ($placeholders)
              AND so.status = 'SELECTED'
            """.trimIndent(),
            *distinctDemandIds.toTypedArray()
        ).associateBy { it.get("demand_id", Long::class.java) }

        val orderRows = dsl.fetch(
            """
            SELECT pod.demand_id, po.paid_at, s.name AS supplier_name, pot.gross_total
            FROM purchase_order_demands pod
                     JOIN purchase_orders po ON po.id = pod.purchase_order_id
                     JOIN suppliers s ON s.id = po.supplier_id
                     LEFT JOIN purchase_order_totals pot ON pot.order_id = po.id
            WHERE pod.demand_id IN ($placeholders)
              AND po.status <> 'CANCELLED'
            ORDER BY po.paid_at DESC NULLS LAST, po.id DESC
            """.trimIndent(),
            *distinctDemandIds.toTypedArray()
        ).groupBy { it.get("demand_id", Long::class.java) }

        val byDemand = distinctDemandIds.associateWith { demandId ->
            val selected = selectedRows[demandId]
            val orders = orderRows[demandId].orEmpty()
            val paidTimestamps = orders.mapNotNull { it.get("paid_at", OffsetDateTime::class.java) }
            val supplierName = selected?.get("supplier_name", String::class.java)
                ?: orders.firstOrNull()?.get("supplier_name", String::class.java)
            val grossTotal = selected?.get("gross_total", BigDecimal::class.java)
                ?: orders.firstOrNull()?.get("gross_total", BigDecimal::class.java)
            DemandHeaderEnrichment(
                selectedSupplierName = supplierName,
                selectedGrossTotal = grossTotal?.toPlainString(),
                isPaid = paidTimestamps.isNotEmpty(),
                paidAt = paidTimestamps.maxOrNull()?.toString()
            )
        }
        return HeaderEnrichments(initiatorNames, byDemand)
    }

    fun findSelectedOfferForDemand(demandId: Long): SupplierOffersRecord? =
        dsl.selectFrom(SUPPLIER_OFFERS)
            .where(SUPPLIER_OFFERS.DEMAND_ID.eq(demandId))
            .and(SUPPLIER_OFFERS.STATUS.eq("SELECTED"))
            .fetchOne()

    fun nomenclatureNamesBatch(nomenclatureIds: Collection<Long>): Map<Long, Pair<String, String>> {
        val ids = nomenclatureIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return dsl.select(NOMENCLATURE.ID, NOMENCLATURE.CODE, NOMENCLATURE.NAME)
            .from(NOMENCLATURE)
            .where(NOMENCLATURE.ID.`in`(ids))
            .fetch()
            .associate {
                it.get(NOMENCLATURE.ID)!! to (it.get(NOMENCLATURE.CODE)!! to it.get(NOMENCLATURE.NAME)!!)
            }
    }

    fun listLinkedReceipts(orderIds: Collection<Long>): List<DemandLinkedReceiptDto> {
        val ids = orderIds.distinct()
        if (ids.isEmpty()) return emptyList()
        return dsl.select(
            RECEIPTS.ID,
            RECEIPTS.NUMBER,
            RECEIPTS.STATUS,
            RECEIPTS.WAREHOUSE_ID,
            WAREHOUSES.NAME,
            RECEIPTS.POSTED_AT,
            RECEIPTS.EXPECTED_AT
        )
            .from(RECEIPTS)
            .leftJoin(WAREHOUSES).on(WAREHOUSES.ID.eq(RECEIPTS.WAREHOUSE_ID))
            .where(RECEIPTS.PURCHASE_ORDER_ID.`in`(ids))
            .and(RECEIPTS.STATUS.ne("CANCELLED"))
            .orderBy(RECEIPTS.ID.desc())
            .fetch { r ->
                DemandLinkedReceiptDto(
                    id = r.get(RECEIPTS.ID)!!,
                    number = r.get(RECEIPTS.NUMBER)!!,
                    status = r.get(RECEIPTS.STATUS) ?: "DRAFT",
                    warehouseId = r.get(RECEIPTS.WAREHOUSE_ID)!!,
                    warehouseName = r.get(WAREHOUSES.NAME),
                    postedAt = r.get(RECEIPTS.POSTED_AT)?.toString(),
                    expectedAt = r.get(RECEIPTS.EXPECTED_AT)?.toString()
                )
            }
    }

    fun generateNumber(): String {
        val now = OffsetDateTime.now()
        val year = now.year
        val lockKey = year * 10000L + 20L
        dsl.fetch("SELECT pg_advisory_xact_lock(?)", lockKey)
        val prefix = "DM-$year-"
        val maxNumber = dsl.select(DEMANDS.NUMBER)
            .from(DEMANDS)
            .where(DEMANDS.NUMBER.like("$prefix%"))
            .orderBy(DEMANDS.NUMBER.desc())
            .limit(1)
            .fetchOne(DEMANDS.NUMBER)
        val nextIndex = if (maxNumber == null) {
            1
        } else {
            val suffix = maxNumber.removePrefix(prefix)
            suffix.toIntOrNull()?.plus(1) ?: 1
        }
        return String.format("$prefix%05d", nextIndex)
    }

    fun lockDemandById(id: Long): DemandsRecord? =
        dsl.selectFrom(DEMANDS).where(DEMANDS.ID.eq(id)).forUpdate().fetchOne()

    fun lockDemandLineById(id: Long): DemandLinesRecord? =
        dsl.selectFrom(DEMAND_LINES).where(DEMAND_LINES.ID.eq(id)).forUpdate().fetchOne()

    fun updateDemandLineStockReserve(
        lineId: Long,
        stockReservedQuantity: BigDecimal,
        coveredQuantity: BigDecimal,
        status: String
    ): Int = dsl.update(DEMAND_LINES)
        .set(DEMAND_LINES.STOCK_RESERVED_QUANTITY, stockReservedQuantity)
        .set(DEMAND_LINES.COVERED_QUANTITY, coveredQuantity)
        .set(DEMAND_LINES.STATUS, status)
        .set(DEMAND_LINES.VERSION, DEMAND_LINES.VERSION.plus(1))
        .where(DEMAND_LINES.ID.eq(lineId))
        .execute()

    fun updateDemandStatusDirect(demandId: Long, newStatus: String): Int =
        dsl.update(DEMANDS)
            .set(DEMANDS.STATUS, newStatus)
            .set(DEMANDS.VERSION, DEMANDS.VERSION.plus(1))
            .where(DEMANDS.ID.eq(demandId))
            .execute()

    fun findActiveWarehouse(warehouseId: Long) =
        dsl.selectFrom(WAREHOUSES)
            .where(WAREHOUSES.ID.eq(warehouseId))
            .fetchOne()

    fun lockFreeStockBalancesForNomenclature(
        nomenclatureId: Long,
        warehouseId: Long?
    ): List<StockBalanceRecord> {
        val conditions = mutableListOf<org.jooq.Condition>(
            STOCK_BALANCE.NOMENCLATURE_ID.eq(nomenclatureId),
            WAREHOUSES.ACTIVE.eq(true),
            STORAGE_LOCATIONS.ACTIVE.eq(true),
            STORAGE_LOCATIONS.PURPOSE.notIn("QUARANTINE", "DEFECT"),
            STOCK_BALANCE.PHYSICAL_QTY.gt(STOCK_BALANCE.RESERVED_QTY)
        )
        warehouseId?.let { conditions += STOCK_BALANCE.WAREHOUSE_ID.eq(it) }
        return dsl.select(STOCK_BALANCE.asterisk())
            .from(STOCK_BALANCE)
            .join(WAREHOUSES).on(WAREHOUSES.ID.eq(STOCK_BALANCE.WAREHOUSE_ID))
            .join(STORAGE_LOCATIONS).on(STORAGE_LOCATIONS.ID.eq(STOCK_BALANCE.LOCATION_ID))
            .where(conditions)
            .orderBy(STOCK_BALANCE.ID.asc())
            .forUpdate().of(STOCK_BALANCE)
            .fetchInto(STOCK_BALANCE)
    }

    fun freeWarehouseStockBatch(nomenclatureIds: Collection<Long>): Map<Long, BigDecimal> {
        val ids = nomenclatureIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return dsl.fetch(
            """
            SELECT sb.nomenclature_id,
                   COALESCE(SUM(GREATEST(sb.physical_qty - sb.reserved_qty, 0)), 0) AS free_qty
            FROM stock_balance sb
                     JOIN warehouses w ON w.id = sb.warehouse_id
                     JOIN storage_locations sl ON sl.id = sb.location_id
            WHERE sb.nomenclature_id IN ($placeholders)
              AND w.active = TRUE
              AND sl.active = TRUE
              AND sl.purpose NOT IN ('QUARANTINE', 'DEFECT')
            GROUP BY sb.nomenclature_id
            """.trimIndent(),
            *ids.toTypedArray()
        ).associate {
            it.get("nomenclature_id", Long::class.java) to (it.get("free_qty", BigDecimal::class.java) ?: BigDecimal.ZERO)
        }
    }

    fun projectReservedTotalsBatch(projectId: Long, nomenclatureIds: Collection<Long>): Map<Long, BigDecimal> {
        val ids = nomenclatureIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return dsl.fetch(
            """
            SELECT r.nomenclature_id, COALESCE(SUM(r.quantity), 0) AS total_qty
            FROM reservations r
            WHERE r.project_id = ?
              AND r.nomenclature_id IN ($placeholders)
              AND r.status IN ('ACTIVE', 'FULFILLED')
            GROUP BY r.nomenclature_id
            """.trimIndent(),
            projectId,
            *ids.toTypedArray()
        ).associate {
            it.get("nomenclature_id", Long::class.java) to (it.get("total_qty", BigDecimal::class.java) ?: BigDecimal.ZERO)
        }
    }

    fun projectNamesBatch(projectIds: Collection<Long>): Map<Long, Pair<String, String>> {
        val ids = projectIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return dsl.select(PROJECTS.ID, PROJECTS.NUMBER, PROJECTS.NAME)
            .from(PROJECTS)
            .where(PROJECTS.ID.`in`(ids))
            .fetch()
            .associate {
                it.get(PROJECTS.ID)!! to (it.get(PROJECTS.NUMBER)!! to it.get(PROJECTS.NAME)!!)
            }
    }

    fun linkProjectBomRevision(projectId: Long, productModelId: Long, revisionId: Long, userId: Long) {
        dsl.execute(
            """
            INSERT INTO project_products (project_id, product_model_id, created_by)
            VALUES (?, ?, ?)
            ON CONFLICT (project_id, product_model_id) DO NOTHING
            """.trimIndent(),
            projectId, productModelId, userId
        )
        val projectProductId = dsl.fetchOne(
            "SELECT id FROM project_products WHERE project_id = ? AND product_model_id = ?",
            projectId, productModelId
        )?.get("id", Long::class.java) ?: return
        dsl.execute(
            """
            INSERT INTO project_bom_revisions (project_product_id, bom_revision_id, created_by)
            VALUES (?, ?, ?)
            ON CONFLICT (project_product_id, bom_revision_id) DO NOTHING
            """.trimIndent(),
            projectProductId, revisionId, userId
        )
    }

    fun demandLinesBatch(demandIds: Collection<Long>): Map<Long, List<DemandLinesRecord>> {
        val ids = demandIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return dsl.selectFrom(DEMAND_LINES)
            .where(DEMAND_LINES.DEMAND_ID.`in`(ids))
            .orderBy(DEMAND_LINES.DEMAND_ID.asc(), DEMAND_LINES.LINE_NO.asc())
            .fetch()
            .groupBy { it.demandId }
    }

    fun offersForDemandsBatch(demandIds: Collection<Long>): Map<Long, List<SupplierOffersRecord>> {
        val ids = demandIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return dsl.selectFrom(SUPPLIER_OFFERS)
            .where(SUPPLIER_OFFERS.DEMAND_ID.`in`(ids))
            .orderBy(SUPPLIER_OFFERS.ID.asc())
            .fetch()
            .groupBy { it.demandId }
    }

    data class DemandLineOrderStats(
        val orderedQty: BigDecimal,
        val orderId: Long,
        val orderNumber: String,
        val supplierName: String
    )

    fun demandLineOrderStatsBatch(demandLineIds: Collection<Long>): Map<Long, DemandLineOrderStats> {
        val ids = demandLineIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        val rows = dsl.fetch(
            """
            SELECT sol.demand_line_id,
                   po.id AS order_id,
                   po.number AS order_number,
                   s.name AS supplier_name,
                   COALESCE(SUM(pol.quantity), 0) AS ordered_qty
            FROM purchase_order_lines pol
                     JOIN purchase_orders po ON po.id = pol.order_id
                     JOIN suppliers s ON s.id = po.supplier_id
                     JOIN supplier_offer_lines sol ON sol.id = pol.offer_line_id
            WHERE sol.demand_line_id IN ($placeholders)
              AND po.status <> 'CANCELLED'
            GROUP BY sol.demand_line_id, po.id, po.number, s.name
            ORDER BY po.id DESC
            """.trimIndent(),
            *ids.toTypedArray()
        )
        return rows.groupBy { it.get("demand_line_id", Long::class.java) }
            .mapValues { (_, list) ->
                val totalOrdered = list.fold(BigDecimal.ZERO) { acc, r ->
                    acc + (r.get("ordered_qty", BigDecimal::class.java) ?: BigDecimal.ZERO)
                }
                val latest = list.first()
                DemandLineOrderStats(
                    orderedQty = totalOrdered,
                    orderId = latest.get("order_id", Long::class.java),
                    orderNumber = latest.get("order_number", String::class.java),
                    supplierName = latest.get("supplier_name", String::class.java)
                )
            }
    }

    fun demandLineReceivedQtyBatch(demandLineIds: Collection<Long>): Map<Long, BigDecimal> {
        val ids = demandLineIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return dsl.fetch(
            """
            SELECT sol.demand_line_id,
                   COALESCE(SUM(rl.received_quantity), 0) AS received_qty
            FROM receipt_lines rl
                     JOIN receipts r ON r.id = rl.receipt_id
                     JOIN purchase_order_lines pol ON pol.id = rl.purchase_order_line_id
                     JOIN supplier_offer_lines sol ON sol.id = pol.offer_line_id
                     JOIN purchase_orders po ON po.id = pol.order_id
            WHERE r.status = 'POSTED'
              AND po.status <> 'CANCELLED'
              AND sol.demand_line_id IN ($placeholders)
            GROUP BY sol.demand_line_id
            """.trimIndent(),
            *ids.toTypedArray()
        ).associate {
            it.get("demand_line_id", Long::class.java) to (it.get("received_qty", BigDecimal::class.java) ?: BigDecimal.ZERO)
        }
    }
}
