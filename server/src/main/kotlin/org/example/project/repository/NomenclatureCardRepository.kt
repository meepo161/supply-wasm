package org.example.project.repository

import org.example.project.generated.jooq.tables.AuditLog.AUDIT_LOG
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.NomenclatureAllowedWarehouses.NOMENCLATURE_ALLOWED_WAREHOUSES
import org.example.project.generated.jooq.tables.NomenclatureAnalogs.NOMENCLATURE_ANALOGS
import org.example.project.generated.jooq.tables.NomenclatureAttributes.NOMENCLATURE_ATTRIBUTES
import org.example.project.generated.jooq.tables.NomenclatureCategories.NOMENCLATURE_CATEGORIES
import org.example.project.generated.jooq.tables.ProductModels.PRODUCT_MODELS
import org.example.project.generated.jooq.tables.ProductRevisions.PRODUCT_REVISIONS
import org.example.project.generated.jooq.tables.StockMovement.STOCK_MOVEMENT
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.Warehouses.WAREHOUSES
import org.example.project.generated.jooq.tables.records.NomenclatureAttributesRecord
import org.example.project.generated.jooq.tables.records.NomenclatureCategoriesRecord
import org.example.project.generated.jooq.tables.records.NomenclatureRecord
import org.example.project.generated.jooq.tables.records.ProductRevisionsRecord
import org.example.project.generated.jooq.tables.records.WarehousesRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Запросы карточки номенклатуры (NSI-001, D-63): справочник категорий,
 * характеристики, допустимые склады, изменение атрибутов, сводки остатков,
 * ожидаемые поставки, резервы, применяемость и история.
 * Остатки и движения только читаются: изменять их могут лишь проведённые документы.
 */
class NomenclatureCardRepository(private val dsl: DSLContext) {

    // --- Категории ---

    fun listCategories(): List<NomenclatureCategoriesRecord> =
        dsl.selectFrom(NOMENCLATURE_CATEGORIES).orderBy(NOMENCLATURE_CATEGORIES.NAME.asc()).fetch()

    /** Все категории с блокировкой строк — вызывается внутри транзакции изменения дерева. */
    fun lockCategories(): List<NomenclatureCategoriesRecord> =
        dsl.selectFrom(NOMENCLATURE_CATEGORIES).orderBy(NOMENCLATURE_CATEGORIES.ID.asc()).forUpdate().fetch()

    fun findCategory(id: Long): NomenclatureCategoriesRecord? =
        dsl.selectFrom(NOMENCLATURE_CATEGORIES).where(NOMENCLATURE_CATEGORIES.ID.eq(id)).fetchOne()

    fun categoryCodeExists(code: String): Boolean =
        dsl.fetchExists(dsl.selectOne().from(NOMENCLATURE_CATEGORIES).where(NOMENCLATURE_CATEGORIES.CODE.eq(code)))

    fun createCategory(code: String, name: String, parentId: Long?, createdBy: Long): NomenclatureCategoriesRecord =
        dsl.insertInto(NOMENCLATURE_CATEGORIES)
            .set(NOMENCLATURE_CATEGORIES.CODE, code)
            .set(NOMENCLATURE_CATEGORIES.NAME, name)
            .set(NOMENCLATURE_CATEGORIES.PARENT_ID, parentId)
            .set(NOMENCLATURE_CATEGORIES.CREATED_BY, createdBy)
            .returning()
            .fetchOne()!!

    fun updateCategory(
        id: Long,
        name: String,
        parentId: Long?,
        archived: Boolean,
        expectedVersion: Long
    ): NomenclatureCategoriesRecord? =
        dsl.update(NOMENCLATURE_CATEGORIES)
            .set(NOMENCLATURE_CATEGORIES.NAME, name)
            .set(NOMENCLATURE_CATEGORIES.PARENT_ID, parentId)
            .set(NOMENCLATURE_CATEGORIES.ARCHIVED, archived)
            .set(NOMENCLATURE_CATEGORIES.VERSION, NOMENCLATURE_CATEGORIES.VERSION.plus(1))
            .where(NOMENCLATURE_CATEGORIES.ID.eq(id))
            .and(NOMENCLATURE_CATEGORIES.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne()

    // --- Характеристики и допустимые склады ---

    fun listAttributes(nomenclatureId: Long): List<NomenclatureAttributesRecord> =
        dsl.selectFrom(NOMENCLATURE_ATTRIBUTES)
            .where(NOMENCLATURE_ATTRIBUTES.NOMENCLATURE_ID.eq(nomenclatureId))
            .orderBy(NOMENCLATURE_ATTRIBUTES.SORT_ORDER.asc(), NOMENCLATURE_ATTRIBUTES.ID.asc())
            .fetch()

    data class AttributeValue(val name: String, val value: String, val unit: String?)

    fun replaceAttributes(nomenclatureId: Long, attributes: List<AttributeValue>) {
        dsl.deleteFrom(NOMENCLATURE_ATTRIBUTES).where(NOMENCLATURE_ATTRIBUTES.NOMENCLATURE_ID.eq(nomenclatureId)).execute()
        attributes.forEachIndexed { index, attribute ->
            dsl.insertInto(NOMENCLATURE_ATTRIBUTES)
                .set(NOMENCLATURE_ATTRIBUTES.NOMENCLATURE_ID, nomenclatureId)
                .set(NOMENCLATURE_ATTRIBUTES.NAME, attribute.name)
                .set(NOMENCLATURE_ATTRIBUTES.VALUE, attribute.value)
                .set(NOMENCLATURE_ATTRIBUTES.UNIT, attribute.unit)
                .set(NOMENCLATURE_ATTRIBUTES.SORT_ORDER, index)
                .execute()
        }
    }

    fun listAllowedWarehouses(nomenclatureId: Long): List<WarehousesRecord> =
        dsl.select(WAREHOUSES.asterisk())
            .from(NOMENCLATURE_ALLOWED_WAREHOUSES)
            .join(WAREHOUSES).on(WAREHOUSES.ID.eq(NOMENCLATURE_ALLOWED_WAREHOUSES.WAREHOUSE_ID))
            .where(NOMENCLATURE_ALLOWED_WAREHOUSES.NOMENCLATURE_ID.eq(nomenclatureId))
            .orderBy(WAREHOUSES.CODE.asc())
            .fetchInto(WAREHOUSES)

    fun replaceAllowedWarehouses(nomenclatureId: Long, warehouseIds: Collection<Long>) {
        dsl.deleteFrom(NOMENCLATURE_ALLOWED_WAREHOUSES)
            .where(NOMENCLATURE_ALLOWED_WAREHOUSES.NOMENCLATURE_ID.eq(nomenclatureId))
            .execute()
        warehouseIds.forEach { warehouseId ->
            dsl.insertInto(NOMENCLATURE_ALLOWED_WAREHOUSES)
                .set(NOMENCLATURE_ALLOWED_WAREHOUSES.NOMENCLATURE_ID, nomenclatureId)
                .set(NOMENCLATURE_ALLOWED_WAREHOUSES.WAREHOUSE_ID, warehouseId)
                .execute()
        }
    }

    fun existingWarehouseIds(ids: Collection<Long>): Set<Long> =
        if (ids.isEmpty()) emptySet()
        else dsl.select(WAREHOUSES.ID).from(WAREHOUSES).where(WAREHOUSES.ID.`in`(ids)).fetchSet(WAREHOUSES.ID)

    fun warehouses(): Map<Long, WarehousesRecord> =
        dsl.selectFrom(WAREHOUSES).fetch().associateBy { it.id }

    // --- Изменение карточки ---

    data class CardFields(
        val name: String,
        val description: String?,
        val categoryId: Long?,
        val article: String?,
        val manufacturer: String?,
        val manufacturerModel: String?,
        val weightKg: BigDecimal?,
        val lengthMm: BigDecimal?,
        val widthMm: BigDecimal?,
        val heightMm: BigDecimal?,
        val storageConditions: String?,
        val productModelId: Long?,
        val minStock: BigDecimal,
        val safetyStock: BigDecimal,
        val leadTimeDays: Int,
        val minOrderQty: BigDecimal,
        val orderMultiple: BigDecimal
    )

    fun updateCard(id: Long, fields: CardFields, expectedVersion: Long): NomenclatureRecord? =
        dsl.update(NOMENCLATURE)
            .set(NOMENCLATURE.NAME, fields.name)
            .set(NOMENCLATURE.DESCRIPTION, fields.description)
            .set(NOMENCLATURE.CATEGORY_ID, fields.categoryId)
            .set(NOMENCLATURE.ARTICLE, fields.article)
            .set(NOMENCLATURE.MANUFACTURER, fields.manufacturer)
            .set(NOMENCLATURE.MANUFACTURER_MODEL, fields.manufacturerModel)
            .set(NOMENCLATURE.WEIGHT_KG, fields.weightKg)
            .set(NOMENCLATURE.LENGTH_MM, fields.lengthMm)
            .set(NOMENCLATURE.WIDTH_MM, fields.widthMm)
            .set(NOMENCLATURE.HEIGHT_MM, fields.heightMm)
            .set(NOMENCLATURE.STORAGE_CONDITIONS, fields.storageConditions)
            .set(NOMENCLATURE.PRODUCT_MODEL_ID, fields.productModelId)
            .set(NOMENCLATURE.MIN_STOCK, fields.minStock)
            .set(NOMENCLATURE.SAFETY_STOCK, fields.safetyStock)
            .set(NOMENCLATURE.LEAD_TIME_DAYS, fields.leadTimeDays)
            .set(NOMENCLATURE.MIN_ORDER_QTY, fields.minOrderQty)
            .set(NOMENCLATURE.ORDER_MULTIPLE, fields.orderMultiple)
            .set(NOMENCLATURE.VERSION, NOMENCLATURE.VERSION.plus(1))
            .where(NOMENCLATURE.ID.eq(id))
            .and(NOMENCLATURE.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne()

    fun nomenclatureLinkedToModel(productModelId: Long, exceptNomenclatureId: Long): String? =
        dsl.select(NOMENCLATURE.CODE).from(NOMENCLATURE)
            .where(NOMENCLATURE.PRODUCT_MODEL_ID.eq(productModelId))
            .and(NOMENCLATURE.ID.ne(exceptNomenclatureId))
            .fetchOne(NOMENCLATURE.CODE)

    fun productModelExists(id: Long): Boolean =
        dsl.fetchExists(dsl.selectOne().from(PRODUCT_MODELS).where(PRODUCT_MODELS.ID.eq(id)))

    fun userName(id: Long): String? =
        dsl.select(USERS.FULL_NAME).from(USERS).where(USERS.ID.eq(id)).fetchOne(USERS.FULL_NAME)

    // --- Изделие ---

    data class ProductModelRow(
        val id: Long,
        val code: String,
        val name: String,
        val status: String,
        val designerName: String?,
        val technologistName: String?,
        val revisions: List<ProductRevisionsRecord>
    )

    fun findProductModel(id: Long): ProductModelRow? {
        val designer = USERS.`as`("designer")
        val technologist = USERS.`as`("technologist")
        val row = dsl.select(
            PRODUCT_MODELS.ID, PRODUCT_MODELS.CODE, PRODUCT_MODELS.NAME, PRODUCT_MODELS.DEVELOPMENT_STATUS,
            designer.FULL_NAME, technologist.FULL_NAME
        )
            .from(PRODUCT_MODELS)
            .leftJoin(designer).on(designer.ID.eq(PRODUCT_MODELS.DESIGNER_ID))
            .leftJoin(technologist).on(technologist.ID.eq(PRODUCT_MODELS.TECHNOLOGIST_ID))
            .where(PRODUCT_MODELS.ID.eq(id))
            .fetchOne() ?: return null
        val revisions = dsl.selectFrom(PRODUCT_REVISIONS)
            .where(PRODUCT_REVISIONS.PRODUCT_MODEL_ID.eq(id))
            .orderBy(PRODUCT_REVISIONS.ID.desc())
            .fetch()
        return ProductModelRow(
            id = row.get(PRODUCT_MODELS.ID)!!,
            code = row.get(PRODUCT_MODELS.CODE)!!,
            name = row.get(PRODUCT_MODELS.NAME)!!,
            status = row.get(PRODUCT_MODELS.DEVELOPMENT_STATUS)!!,
            designerName = row.get(designer.FULL_NAME),
            technologistName = row.get(technologist.FULL_NAME),
            revisions = revisions
        )
    }

    // --- Аналоги: где позиция сама указана аналогом ---

    fun listAnalogOf(nomenclatureId: Long): List<Record> =
        dsl.select(
            NOMENCLATURE_ANALOGS.ID,
            NOMENCLATURE_ANALOGS.NOMENCLATURE_ID,
            NOMENCLATURE_ANALOGS.ANALOG_NOMENCLATURE_ID,
            NOMENCLATURE.CODE,
            NOMENCLATURE.NAME,
            NOMENCLATURE_ANALOGS.CONVERSION_FACTOR,
            NOMENCLATURE_ANALOGS.APPLICABILITY_CONDITION,
            NOMENCLATURE_ANALOGS.PRIORITY,
            NOMENCLATURE_ANALOGS.ACTIVE,
            NOMENCLATURE_ANALOGS.REQUIRES_APPROVAL
        )
            .from(NOMENCLATURE_ANALOGS)
            .join(NOMENCLATURE).on(NOMENCLATURE.ID.eq(NOMENCLATURE_ANALOGS.NOMENCLATURE_ID))
            .where(NOMENCLATURE_ANALOGS.ANALOG_NOMENCLATURE_ID.eq(nomenclatureId))
            .orderBy(NOMENCLATURE.CODE.asc())
            .fetch()

    // --- Остатки ---

    /** В пути по складу назначения: перемещения SHIPPED/IN_TRANSIT (D-63). */
    fun inTransitByDestination(nomenclatureId: Long): Map<Long, BigDecimal> =
        dsl.fetch(
            """
            SELECT to_warehouse_id, SUM(quantity) AS qty
            FROM transfers
            WHERE nomenclature_id = ? AND status IN ('SHIPPED', 'IN_TRANSIT')
            GROUP BY to_warehouse_id
            """.trimIndent(),
            nomenclatureId
        ).associate { it.get("to_warehouse_id", Long::class.java) to it.get("qty", BigDecimal::class.java) }

    data class ExpectedSupplyRow(
        val orderId: Long,
        val orderNumber: String,
        val orderStatus: String,
        val supplierName: String?,
        val lineNo: Int,
        val ordered: BigDecimal,
        val accepted: BigDecimal,
        val remaining: BigDecimal,
        val expectedAt: LocalDate?
    )

    /**
     * Ожидаемые поставки: заказано минус принято по заказам в статусах
     * SENT/PARTIALLY_DELIVERED (D-63). Источник — view purchase_order_receipt_status.
     */
    fun expectedSupplies(nomenclatureId: Long): List<ExpectedSupplyRow> =
        dsl.fetch(
            """
            SELECT s.order_id, s.order_number, s.order_status, s.line_no,
                   s.ordered_quantity, s.accepted_quantity, s.not_accepted_quantity,
                   o.expected_at, sp.name AS supplier_name
            FROM purchase_order_receipt_status s
            JOIN purchase_orders o ON o.id = s.order_id
            LEFT JOIN suppliers sp ON sp.id = o.supplier_id
            WHERE s.nomenclature_id = ?
              AND s.order_status IN ('SENT', 'PARTIALLY_DELIVERED')
              AND s.not_accepted_quantity > 0
            ORDER BY o.expected_at NULLS LAST, s.order_id, s.line_no
            """.trimIndent(),
            nomenclatureId
        ).map {
            ExpectedSupplyRow(
                orderId = it.get("order_id", Long::class.java),
                orderNumber = it.get("order_number", String::class.java),
                orderStatus = it.get("order_status", String::class.java),
                supplierName = it.get("supplier_name", String::class.java),
                lineNo = it.get("line_no", Int::class.java),
                ordered = it.get("ordered_quantity", BigDecimal::class.java),
                accepted = it.get("accepted_quantity", BigDecimal::class.java),
                remaining = it.get("not_accepted_quantity", BigDecimal::class.java),
                expectedAt = it.get("expected_at", LocalDate::class.java)
            )
        }

    // --- Резервы ---

    data class ReservationRow(
        val id: Long,
        val number: String,
        val reasonType: String,
        val reasonNote: String?,
        val projectId: Long?,
        val projectLabel: String?,
        val taskId: Long?,
        val taskLabel: String?,
        val warehouseName: String,
        val locationCode: String?,
        val lotNumber: String?,
        val serialNumber: String?,
        val quantity: BigDecimal,
        val issuedQuantity: BigDecimal,
        val status: String,
        val ownerName: String,
        val expiresAt: OffsetDateTime?,
        val createdAt: OffsetDateTime?
    )

    fun reservations(nomenclatureId: Long): List<ReservationRow> =
        dsl.fetch(
            """
            SELECT r.id, r.number, r.reason_type, r.reason_note, r.project_id, r.task_id,
                   p.number AS project_number, p.name AS project_name,
                   t.number AS task_number, t.title AS task_title,
                   w.name AS warehouse_name, sl.code AS location_code,
                   l.lot_number, s.serial_number,
                   r.quantity, r.issued_quantity, r.status, u.full_name AS owner_name,
                   r.expires_at, r.created_at
            FROM reservations r
            JOIN warehouses w ON w.id = r.warehouse_id
            JOIN users u ON u.id = r.owner_id
            LEFT JOIN storage_locations sl ON sl.id = r.location_id
            LEFT JOIN lots l ON l.id = r.lot_id
            LEFT JOIN serial_items s ON s.id = r.serial_id
            LEFT JOIN projects p ON p.id = r.project_id
            LEFT JOIN tasks t ON t.id = r.task_id
            WHERE r.nomenclature_id = ?
            ORDER BY (r.status = 'ACTIVE') DESC, r.created_at DESC, r.id DESC
            """.trimIndent(),
            nomenclatureId
        ).map {
            val projectNumber = it.get("project_number", String::class.java)
            val projectName = it.get("project_name", String::class.java)
            val taskNumber = it.get("task_number", String::class.java)
            val taskTitle = it.get("task_title", String::class.java)
            ReservationRow(
                id = it.get("id", Long::class.java),
                number = it.get("number", String::class.java),
                reasonType = it.get("reason_type", String::class.java),
                reasonNote = it.get("reason_note", String::class.java),
                projectId = it.get("project_id", Long::class.javaObjectType),
                projectLabel = listOfNotNull(projectNumber, projectName).joinToString(" · ").ifEmpty { null },
                taskId = it.get("task_id", Long::class.javaObjectType),
                taskLabel = listOfNotNull(taskNumber, taskTitle).joinToString(" · ").ifEmpty { null },
                warehouseName = it.get("warehouse_name", String::class.java),
                locationCode = it.get("location_code", String::class.java),
                lotNumber = it.get("lot_number", String::class.java),
                serialNumber = it.get("serial_number", String::class.java),
                quantity = it.get("quantity", BigDecimal::class.java),
                issuedQuantity = it.get("issued_quantity", BigDecimal::class.java),
                status = it.get("status", String::class.java),
                ownerName = it.get("owner_name", String::class.java),
                expiresAt = it.get("expires_at", OffsetDateTime::class.java),
                createdAt = it.get("created_at", OffsetDateTime::class.java)
            )
        }

    // --- Движения ---

    data class MovementFilter(
        val type: String?,
        val from: LocalDate?,
        val to: LocalDate?,
        val before: Long?
    )

    /** Границы дат — в часовом поясе сервера, как и суточные точки графика остатка. */
    fun movementConditions(nomenclatureId: Long, filter: MovementFilter, zone: ZoneId): List<Condition> {
        val conditions = mutableListOf<Condition>(STOCK_MOVEMENT.NOMENCLATURE_ID.eq(nomenclatureId))
        filter.type?.let { conditions += STOCK_MOVEMENT.MOVEMENT_TYPE.eq(it) }
        filter.from?.let { conditions += STOCK_MOVEMENT.CREATED_AT.ge(it.atStartOfDay(zone).toOffsetDateTime()) }
        filter.to?.let { conditions += STOCK_MOVEMENT.CREATED_AT.lt(it.plusDays(1).atStartOfDay(zone).toOffsetDateTime()) }
        filter.before?.let { conditions += STOCK_MOVEMENT.ID.lt(it) }
        return conditions
    }

    fun countMovements(nomenclatureId: Long): Int =
        dsl.fetchCount(STOCK_MOVEMENT, STOCK_MOVEMENT.NOMENCLATURE_ID.eq(nomenclatureId))

    data class MovementDelta(val type: String, val quantity: BigDecimal, val createdAt: OffsetDateTime)

    fun movementsSince(nomenclatureId: Long, since: OffsetDateTime): List<MovementDelta> =
        dsl.select(STOCK_MOVEMENT.MOVEMENT_TYPE, STOCK_MOVEMENT.QUANTITY, STOCK_MOVEMENT.CREATED_AT)
            .from(STOCK_MOVEMENT)
            .where(STOCK_MOVEMENT.NOMENCLATURE_ID.eq(nomenclatureId))
            .and(STOCK_MOVEMENT.CREATED_AT.ge(since))
            .orderBy(STOCK_MOVEMENT.CREATED_AT.asc(), STOCK_MOVEMENT.ID.asc())
            .fetch {
                MovementDelta(
                    type = it.get(STOCK_MOVEMENT.MOVEMENT_TYPE)!!,
                    quantity = it.get(STOCK_MOVEMENT.QUANTITY)!!,
                    createdAt = it.get(STOCK_MOVEMENT.CREATED_AT)!!
                )
            }

    // --- Применяемость ---

    data class BomUsageRow(
        val productModelId: Long,
        val modelCode: String,
        val modelName: String,
        val revisionId: Long,
        val revisionNumber: String,
        val revisionStatus: String,
        val lineId: Long,
        val quantity: BigDecimal,
        val unit: String,
        val level: Int,
        val position: String?
    )

    fun bomUsage(nomenclatureId: Long, asParent: Boolean): List<BomUsageRow> {
        val column = if (asParent) "parent_nomenclature_id" else "child_nomenclature_id"
        return dsl.fetch(
            """
            SELECT pm.id AS model_id, pm.code AS model_code, pm.name AS model_name,
                   pr.id AS revision_id, pr.revision_number, pr.status AS revision_status,
                   bl.id AS line_id, bl.quantity, bl.unit, bl.level, bl.position
            FROM bom_lines bl
            JOIN product_revisions pr ON pr.id = bl.revision_id
            JOIN product_models pm ON pm.id = pr.product_model_id
            WHERE bl.$column = ?
            ORDER BY pm.code, pr.id DESC, bl.id
            """.trimIndent(),
            nomenclatureId
        ).map {
            BomUsageRow(
                productModelId = it.get("model_id", Long::class.java),
                modelCode = it.get("model_code", String::class.java),
                modelName = it.get("model_name", String::class.java),
                revisionId = it.get("revision_id", Long::class.java),
                revisionNumber = it.get("revision_number", String::class.java),
                revisionStatus = it.get("revision_status", String::class.java),
                lineId = it.get("line_id", Long::class.java),
                quantity = it.get("quantity", BigDecimal::class.java),
                unit = it.get("unit", String::class.java),
                level = it.get("level", Int::class.java),
                position = it.get("position", String::class.java)
            )
        }
    }

    data class DemandUsageRow(
        val demandId: Long,
        val number: String,
        val status: String,
        val sourceType: String,
        val projectName: String?,
        val quantity: BigDecimal,
        val covered: BigDecimal,
        val unit: String
    )

    fun demandUsage(nomenclatureId: Long): List<DemandUsageRow> =
        dsl.fetch(
            """
            SELECT d.id, d.number, d.status, d.source_type, p.name AS project_name,
                   SUM(dl.quantity) AS quantity, SUM(dl.covered_quantity) AS covered, MIN(dl.unit) AS unit,
                   MAX(d.created_at) AS created_at
            FROM demand_lines dl
            JOIN demands d ON d.id = dl.demand_id
            LEFT JOIN projects p ON p.id = d.project_id
            WHERE dl.nomenclature_id = ? AND dl.status <> 'CANCELLED'
            GROUP BY d.id, d.number, d.status, d.source_type, p.name
            ORDER BY (d.status IN ('COVERED', 'CANCELLED')), MAX(d.created_at) DESC
            LIMIT 200
            """.trimIndent(),
            nomenclatureId
        ).map {
            DemandUsageRow(
                demandId = it.get("id", Long::class.java),
                number = it.get("number", String::class.java),
                status = it.get("status", String::class.java),
                sourceType = it.get("source_type", String::class.java),
                projectName = it.get("project_name", String::class.java),
                quantity = it.get("quantity", BigDecimal::class.java),
                covered = it.get("covered", BigDecimal::class.java),
                unit = it.get("unit", String::class.java)
            )
        }

    data class PurchaseOrderUsageRow(
        val orderId: Long,
        val number: String,
        val status: String,
        val supplierName: String?,
        val quantity: BigDecimal,
        val delivered: BigDecimal,
        val unit: String
    )

    fun purchaseOrderUsage(nomenclatureId: Long): List<PurchaseOrderUsageRow> =
        dsl.fetch(
            """
            SELECT o.id, o.number, o.status, sp.name AS supplier_name,
                   SUM(l.quantity) AS quantity, SUM(l.delivered_quantity) AS delivered, MIN(l.unit) AS unit
            FROM purchase_order_lines l
            JOIN purchase_orders o ON o.id = l.order_id
            LEFT JOIN suppliers sp ON sp.id = o.supplier_id
            WHERE l.nomenclature_id = ?
            GROUP BY o.id, o.number, o.status, sp.name
            ORDER BY (o.status IN ('DELIVERED', 'CLOSED', 'CANCELLED')), MAX(o.created_at) DESC
            LIMIT 200
            """.trimIndent(),
            nomenclatureId
        ).map {
            PurchaseOrderUsageRow(
                orderId = it.get("id", Long::class.java),
                number = it.get("number", String::class.java),
                status = it.get("status", String::class.java),
                supplierName = it.get("supplier_name", String::class.java),
                quantity = it.get("quantity", BigDecimal::class.java),
                delivered = it.get("delivered", BigDecimal::class.java),
                unit = it.get("unit", String::class.java)
            )
        }

    data class ProductionUsageRow(
        val orderId: Long,
        val number: String,
        val status: String,
        val planned: BigDecimal,
        val consumed: BigDecimal,
        val unit: String
    )

    fun productionUsage(nomenclatureId: Long): List<ProductionUsageRow> =
        dsl.fetch(
            """
            SELECT po.id, po.number, po.status, m.planned_qty, m.consumed_qty, m.unit
            FROM production_order_materials m
            JOIN production_orders po ON po.id = m.production_order_id
            WHERE m.nomenclature_id = ?
            ORDER BY (po.status IN ('COMPLETED', 'CLOSED', 'CANCELLED')), po.created_at DESC
            LIMIT 200
            """.trimIndent(),
            nomenclatureId
        ).map {
            ProductionUsageRow(
                orderId = it.get("id", Long::class.java),
                number = it.get("number", String::class.java),
                status = it.get("status", String::class.java),
                planned = it.get("planned_qty", BigDecimal::class.java),
                consumed = it.get("consumed_qty", BigDecimal::class.java),
                unit = it.get("unit", String::class.java)
            )
        }

    // --- Счётчики и история ---

    fun countActiveReservations(nomenclatureId: Long): Int =
        dsl.fetchOne(
            "SELECT COUNT(*) AS c FROM reservations WHERE nomenclature_id = ? AND status = 'ACTIVE'",
            nomenclatureId
        )!!.get("c", Int::class.java)

    fun countAttachments(nomenclatureId: Long): Int =
        dsl.fetchOne(
            "SELECT COUNT(*) AS c FROM attachments WHERE entity_type = 'NOMENCLATURE' AND entity_id = ?",
            nomenclatureId
        )!!.get("c", Int::class.java)

    fun countHistory(nomenclatureId: Long): Int =
        dsl.fetchCount(AUDIT_LOG, AUDIT_LOG.ENTITY_TYPE.eq("NOMENCLATURE").and(AUDIT_LOG.ENTITY_ID.eq(nomenclatureId)))

    data class HistoryRow(
        val id: Long,
        val action: String,
        val userName: String?,
        val payload: String?,
        val createdAt: OffsetDateTime
    )

    fun history(nomenclatureId: Long, limit: Int): List<HistoryRow> =
        dsl.select(AUDIT_LOG.ID, AUDIT_LOG.ACTION, USERS.FULL_NAME, AUDIT_LOG.PAYLOAD, AUDIT_LOG.CREATED_AT)
            .from(AUDIT_LOG)
            .leftJoin(USERS).on(USERS.ID.eq(AUDIT_LOG.USER_ID))
            .where(AUDIT_LOG.ENTITY_TYPE.eq("NOMENCLATURE"))
            .and(AUDIT_LOG.ENTITY_ID.eq(nomenclatureId))
            .orderBy(AUDIT_LOG.CREATED_AT.desc(), AUDIT_LOG.ID.desc())
            .limit(limit)
            .fetch {
                HistoryRow(
                    id = it.get(AUDIT_LOG.ID)!!,
                    action = it.get(AUDIT_LOG.ACTION)!!,
                    userName = it.get(USERS.FULL_NAME),
                    payload = it.get(AUDIT_LOG.PAYLOAD)?.data(),
                    createdAt = it.get(AUDIT_LOG.CREATED_AT)!!
                )
            }
}
