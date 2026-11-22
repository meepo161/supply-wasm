package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.BomLines.BOM_LINES
import org.example.project.generated.jooq.tables.EngineeringChangeImpacts.ENGINEERING_CHANGE_IMPACTS
import org.example.project.generated.jooq.tables.InstanceComponents.INSTANCE_COMPONENTS
import org.example.project.generated.jooq.tables.Lots.LOTS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.Nonconformities.NONCONFORMITIES
import org.example.project.generated.jooq.tables.NonconformityHistory.NONCONFORMITY_HISTORY
import org.example.project.generated.jooq.tables.ProductRevisions.PRODUCT_REVISIONS
import org.example.project.generated.jooq.tables.ProductionOrderMaterials.PRODUCTION_ORDER_MATERIALS
import org.example.project.generated.jooq.tables.ProductionOrders.PRODUCTION_ORDERS
import org.example.project.generated.jooq.tables.Reservations.RESERVATIONS
import org.example.project.generated.jooq.tables.SerialItems.SERIAL_ITEMS
import org.example.project.generated.jooq.tables.StockBalance.STOCK_BALANCE
import org.example.project.generated.jooq.tables.StockMovement.STOCK_MOVEMENT
import org.example.project.generated.jooq.tables.StorageLocations.STORAGE_LOCATIONS
import org.example.project.model.CurrentUser
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.Year

/**
 * Комплектация и фактический расход материалов (PROD-07, PROD-08, QUAL-07),
 * инженерные изменения в производстве (PROD-09, D-34) и несоответствия (QUAL-04).
 */
class ProductionExtrasService(
    private val wmsService: WmsService,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    fun listMaterials(orderId: Long, currentUser: CurrentUser): List<ProductionOrderMaterialDto> {
        currentUser.requirePermission("production", "read")
        return dsl.select(
            PRODUCTION_ORDER_MATERIALS.ID,
            PRODUCTION_ORDER_MATERIALS.PRODUCTION_ORDER_ID,
            PRODUCTION_ORDER_MATERIALS.NOMENCLATURE_ID,
            NOMENCLATURE.CODE,
            NOMENCLATURE.NAME,
            PRODUCTION_ORDER_MATERIALS.PLANNED_QTY,
            PRODUCTION_ORDER_MATERIALS.ALLOCATED_QTY,
            PRODUCTION_ORDER_MATERIALS.CONSUMED_QTY,
            PRODUCTION_ORDER_MATERIALS.UNIT,
            PRODUCTION_ORDER_MATERIALS.WAREHOUSE_ID,
            PRODUCTION_ORDER_MATERIALS.RESERVATION_ID,
            PRODUCTION_ORDER_MATERIALS.ISSUE_ORDER_ID,
            PRODUCTION_ORDER_MATERIALS.VERSION
        )
            .from(PRODUCTION_ORDER_MATERIALS)
            .join(NOMENCLATURE).on(NOMENCLATURE.ID.eq(PRODUCTION_ORDER_MATERIALS.NOMENCLATURE_ID))
            .where(PRODUCTION_ORDER_MATERIALS.PRODUCTION_ORDER_ID.eq(orderId))
            .orderBy(NOMENCLATURE.CODE.asc())
            .fetch { r ->
                ProductionOrderMaterialDto(
                    id = r.get(PRODUCTION_ORDER_MATERIALS.ID)!!,
                    productionOrderId = r.get(PRODUCTION_ORDER_MATERIALS.PRODUCTION_ORDER_ID)!!,
                    nomenclatureId = r.get(PRODUCTION_ORDER_MATERIALS.NOMENCLATURE_ID)!!,
                    nomenclatureCode = r.get(NOMENCLATURE.CODE)!!,
                    nomenclatureName = r.get(NOMENCLATURE.NAME)!!,
                    plannedQty = r.get(PRODUCTION_ORDER_MATERIALS.PLANNED_QTY)!!.stripTrailingZeros().toPlainString(),
                    allocatedQty = r.get(PRODUCTION_ORDER_MATERIALS.ALLOCATED_QTY)!!.stripTrailingZeros().toPlainString(),
                    consumedQty = r.get(PRODUCTION_ORDER_MATERIALS.CONSUMED_QTY)!!.stripTrailingZeros().toPlainString(),
                    unit = r.get(PRODUCTION_ORDER_MATERIALS.UNIT)!!,
                    warehouseId = r.get(PRODUCTION_ORDER_MATERIALS.WAREHOUSE_ID),
                    reservationId = r.get(PRODUCTION_ORDER_MATERIALS.RESERVATION_ID),
                    issueOrderId = r.get(PRODUCTION_ORDER_MATERIALS.ISSUE_ORDER_ID),
                    version = r.get(PRODUCTION_ORDER_MATERIALS.VERSION) ?: 0
                )
            }
    }

    fun initMaterialsFromBom(orderId: Long, currentUser: CurrentUser): List<ProductionOrderMaterialDto> {
        currentUser.requirePermission("production", "manage")
        val order = dsl.selectFrom(PRODUCTION_ORDERS).where(PRODUCTION_ORDERS.ID.eq(orderId)).fetchOne()
            ?: throw NotFoundException("production_order", orderId)
        val lines = dsl.select(BOM_LINES.CHILD_NOMENCLATURE_ID, BOM_LINES.QUANTITY, BOM_LINES.UNIT)
            .from(BOM_LINES)
            .where(BOM_LINES.REVISION_ID.eq(order.bomRevisionId))
            .fetch()
        dsl.transaction { _ ->
            for (line in lines) {
                val nomId = line.get(BOM_LINES.CHILD_NOMENCLATURE_ID)!!
                val qty = line.get(BOM_LINES.QUANTITY)!! * order.plannedQuantity.toBigDecimal()
                val unit = line.get(BOM_LINES.UNIT)!!
                val exists = dsl.fetchExists(
                    dsl.selectOne().from(PRODUCTION_ORDER_MATERIALS)
                        .where(PRODUCTION_ORDER_MATERIALS.PRODUCTION_ORDER_ID.eq(orderId))
                        .and(PRODUCTION_ORDER_MATERIALS.NOMENCLATURE_ID.eq(nomId))
                )
                if (!exists) {
                    dsl.insertInto(PRODUCTION_ORDER_MATERIALS)
                        .set(PRODUCTION_ORDER_MATERIALS.PRODUCTION_ORDER_ID, orderId)
                        .set(PRODUCTION_ORDER_MATERIALS.NOMENCLATURE_ID, nomId)
                        .set(PRODUCTION_ORDER_MATERIALS.PLANNED_QTY, qty)
                        .set(PRODUCTION_ORDER_MATERIALS.UNIT, unit)
                        .execute()
                }
            }
            auditService.log(currentUser.userId, "PROD_MATERIALS_INIT", "PRODUCTION_ORDER", orderId)
        }
        return listMaterials(orderId, currentUser)
    }

    fun allocateMaterial(
        orderId: Long,
        materialId: Long,
        request: AllocateProductionMaterialRequest,
        currentUser: CurrentUser
    ): ProductionOrderMaterialDto {
        currentUser.requirePermission("production", "manage")
        val order = dsl.selectFrom(PRODUCTION_ORDERS).where(PRODUCTION_ORDERS.ID.eq(orderId)).fetchOne()
            ?: throw NotFoundException("production_order", orderId)
        if (order.status in setOf("COMPLETED", "CANCELLED")) {
            throw ConflictException("Cannot allocate material for order in status ${order.status}")
        }
        val mat = dsl.selectFrom(PRODUCTION_ORDER_MATERIALS)
            .where(PRODUCTION_ORDER_MATERIALS.ID.eq(materialId))
            .and(PRODUCTION_ORDER_MATERIALS.PRODUCTION_ORDER_ID.eq(orderId))
            .fetchOne() ?: throw NotFoundException("production_order_material", materialId)
        if ((mat.version ?: 0L) != request.version) throw ConflictException("Material version conflict")
        val qty = request.quantity.trim().toBigDecimalOrNull()
            ?: throw ValidationException("Invalid quantity")
        if (qty <= BigDecimal.ZERO) throw ValidationException("Quantity must be > 0")

        val locationId = dsl.select(STOCK_BALANCE.LOCATION_ID)
            .from(STOCK_BALANCE)
            .join(STORAGE_LOCATIONS).on(STORAGE_LOCATIONS.ID.eq(STOCK_BALANCE.LOCATION_ID))
            .where(STOCK_BALANCE.WAREHOUSE_ID.eq(request.warehouseId))
            .and(STOCK_BALANCE.NOMENCLATURE_ID.eq(mat.nomenclatureId))
            .and(STORAGE_LOCATIONS.ACTIVE.isTrue)
            .and(STORAGE_LOCATIONS.PURPOSE.notIn("QUARANTINE", "DEFECT", "TEMPORARY"))
            .and(STOCK_BALANCE.PHYSICAL_QTY.minus(STOCK_BALANCE.RESERVED_QTY).ge(qty))
            .orderBy(STOCK_BALANCE.PHYSICAL_QTY.desc())
            .limit(1)
            .fetchOne(STOCK_BALANCE.LOCATION_ID)
            ?: dsl.select(STORAGE_LOCATIONS.ID)
                .from(STORAGE_LOCATIONS)
                .where(STORAGE_LOCATIONS.WAREHOUSE_ID.eq(request.warehouseId))
                .and(STORAGE_LOCATIONS.ACTIVE.isTrue)
                .and(STORAGE_LOCATIONS.PURPOSE.notIn("QUARANTINE", "DEFECT", "TEMPORARY"))
                .orderBy(STORAGE_LOCATIONS.ID.asc())
                .limit(1)
                .fetchOne(STORAGE_LOCATIONS.ID)
            ?: throw ValidationException("No active storage location found for warehouse ${request.warehouseId}")

        val reservation = wmsService.createReservation(
            CreateReservationRequest(
                warehouseId = request.warehouseId,
                locationId = locationId,
                nomenclatureId = mat.nomenclatureId,
                quantity = qty.toPlainString(),
                reasonType = ReservationReasonType.PRODUCTION_ORDER,
                reasonNote = "Production order #$orderId"
            ),
            currentUser
        )
        try {
            dsl.transaction { _ ->
                val updated = dsl.update(PRODUCTION_ORDER_MATERIALS)
                    .set(PRODUCTION_ORDER_MATERIALS.ALLOCATED_QTY, PRODUCTION_ORDER_MATERIALS.ALLOCATED_QTY.plus(qty))
                    .set(PRODUCTION_ORDER_MATERIALS.WAREHOUSE_ID, request.warehouseId)
                    .set(PRODUCTION_ORDER_MATERIALS.RESERVATION_ID, reservation.id)
                    .set(PRODUCTION_ORDER_MATERIALS.UPDATED_AT, OffsetDateTime.now())
                    .set(PRODUCTION_ORDER_MATERIALS.VERSION, PRODUCTION_ORDER_MATERIALS.VERSION.plus(1))
                    .where(PRODUCTION_ORDER_MATERIALS.ID.eq(materialId))
                    .and(PRODUCTION_ORDER_MATERIALS.VERSION.eq(request.version))
                    .execute()
                if (updated == 0) throw ConflictException("Material version conflict")
                auditService.log(
                    currentUser.userId,
                    "PROD_MATERIAL_ALLOCATE",
                    "PRODUCTION_ORDER",
                    orderId,
                    mapOf("materialId" to materialId, "reservationId" to reservation.id, "qty" to qty.toPlainString())
                )
            }
        } catch (e: Throwable) {
            runCatching {
                wmsService.releaseReservation(
                    reservation.id,
                    ReleaseReservationRequest(reason = "Rollback allocation due to error", version = reservation.version),
                    currentUser
                )
            }
            throw e
        }
        return listMaterials(orderId, currentUser).first { it.id == materialId }
    }

    fun consumeMaterial(
        orderId: Long,
        materialId: Long,
        request: ConsumeProductionMaterialRequest,
        currentUser: CurrentUser
    ): ProductionOrderMaterialDto {
        currentUser.requirePermission("production", "manage")
        val order = dsl.selectFrom(PRODUCTION_ORDERS).where(PRODUCTION_ORDERS.ID.eq(orderId)).fetchOne()
            ?: throw NotFoundException("production_order", orderId)
        if (order.status in setOf("COMPLETED", "CANCELLED")) {
            throw ConflictException("Cannot consume material for order in status ${order.status}")
        }
        val mat = dsl.selectFrom(PRODUCTION_ORDER_MATERIALS)
            .where(PRODUCTION_ORDER_MATERIALS.ID.eq(materialId))
            .and(PRODUCTION_ORDER_MATERIALS.PRODUCTION_ORDER_ID.eq(orderId))
            .fetchOne() ?: throw NotFoundException("production_order_material", materialId)
        if ((mat.version ?: 0L) != request.version) throw ConflictException("Material version conflict")
        val qty = request.quantity.trim().toBigDecimalOrNull()
            ?: throw ValidationException("Invalid quantity")
        if (qty <= BigDecimal.ZERO) throw ValidationException("Quantity must be > 0")
        if (mat.consumedQty + qty > mat.allocatedQty) {
            throw ConflictException("Consumed quantity cannot exceed allocated quantity")
        }
        dsl.transaction { _ ->
            val updated = dsl.update(PRODUCTION_ORDER_MATERIALS)
                .set(PRODUCTION_ORDER_MATERIALS.CONSUMED_QTY, PRODUCTION_ORDER_MATERIALS.CONSUMED_QTY.plus(qty))
                .set(PRODUCTION_ORDER_MATERIALS.UPDATED_AT, OffsetDateTime.now())
                .set(PRODUCTION_ORDER_MATERIALS.VERSION, PRODUCTION_ORDER_MATERIALS.VERSION.plus(1))
                .where(PRODUCTION_ORDER_MATERIALS.ID.eq(materialId))
                .and(PRODUCTION_ORDER_MATERIALS.VERSION.eq(request.version))
                .execute()
            if (updated == 0) throw ConflictException("Material version conflict")

            // AUDIT-07: фактический расход списывает складской остаток и закрывает/уменьшает резерв.
            val resId = mat.reservationId
            if (resId != null) {
                val res = dsl.selectFrom(RESERVATIONS).where(RESERVATIONS.ID.eq(resId)).forUpdate().fetchOne()
                if (res != null && res.status == "ACTIVE") {
                    val bal = dsl.selectFrom(STOCK_BALANCE)
                        .where(STOCK_BALANCE.WAREHOUSE_ID.eq(res.warehouseId))
                        .and(STOCK_BALANCE.LOCATION_ID.eq(res.locationId))
                        .and(STOCK_BALANCE.NOMENCLATURE_ID.eq(res.nomenclatureId))
                        .let { q -> if (res.lotId != null) q.and(STOCK_BALANCE.LOT_ID.eq(res.lotId)) else q.and(STOCK_BALANCE.LOT_ID.isNull) }
                        .let { q -> if (res.serialId != null) q.and(STOCK_BALANCE.SERIAL_ID.eq(res.serialId)) else q.and(STOCK_BALANCE.SERIAL_ID.isNull) }
                        .forUpdate()
                        .fetchOne()
                        ?: throw ConflictException("Reserved stock balance not found")
                    if (bal.physicalQty < qty || bal.reservedQty < qty) {
                        throw ConflictException("Insufficient reserved stock balance")
                    }
                    dsl.update(STOCK_BALANCE)
                        .set(STOCK_BALANCE.PHYSICAL_QTY, STOCK_BALANCE.PHYSICAL_QTY.minus(qty))
                        .set(STOCK_BALANCE.RESERVED_QTY, STOCK_BALANCE.RESERVED_QTY.minus(qty))
                        .set(STOCK_BALANCE.UPDATED_AT, OffsetDateTime.now())
                        .set(STOCK_BALANCE.VERSION, STOCK_BALANCE.VERSION.plus(1))
                        .where(STOCK_BALANCE.ID.eq(bal.id))
                        .execute()
                    val remainingRes = (res.quantity - qty).max(BigDecimal.ZERO)
                    val resUpdate = dsl.update(RESERVATIONS)
                        .set(RESERVATIONS.STATUS, if (remainingRes.compareTo(BigDecimal.ZERO) == 0) "FULFILLED" else "ACTIVE")
                        .set(RESERVATIONS.VERSION, (res.version ?: 0L) + 1L)
                    if (remainingRes.compareTo(BigDecimal.ZERO) > 0) {
                        resUpdate.set(RESERVATIONS.QUANTITY, remainingRes)
                    }
                    resUpdate.where(RESERVATIONS.ID.eq(res.id)).execute()
                    dsl.insertInto(STOCK_MOVEMENT)
                        .set(STOCK_MOVEMENT.MOVEMENT_TYPE, "ISSUE")
                        .set(STOCK_MOVEMENT.NOMENCLATURE_ID, res.nomenclatureId)
                        .set(STOCK_MOVEMENT.LOT_ID, res.lotId)
                        .set(STOCK_MOVEMENT.SERIAL_ID, res.serialId)
                        .set(STOCK_MOVEMENT.FROM_LOCATION_ID, res.locationId)
                        .set(STOCK_MOVEMENT.QUANTITY, qty)
                        .set(STOCK_MOVEMENT.DOCUMENT_TYPE, "PRODUCTION_ORDER")
                        .set(STOCK_MOVEMENT.DOCUMENT_ID, orderId)
                        .set(STOCK_MOVEMENT.NOTE, "Расход по производственному заказу #$orderId")
                        .set(STOCK_MOVEMENT.CREATED_BY, currentUser.userId)
                        .execute()
                }
            }

            if (request.instanceId != null) {
                val lotId = request.lotNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { ln ->
                    dsl.select(LOTS.ID).from(LOTS).where(LOTS.LOT_NUMBER.eq(ln)).fetchOne(LOTS.ID)
                }
                val serialId = request.serialNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { sn ->
                    dsl.select(SERIAL_ITEMS.ID).from(SERIAL_ITEMS).where(SERIAL_ITEMS.SERIAL_NUMBER.eq(sn)).fetchOne(SERIAL_ITEMS.ID)
                }
                dsl.insertInto(INSTANCE_COMPONENTS)
                    .set(INSTANCE_COMPONENTS.INSTANCE_ID, request.instanceId)
                    .set(INSTANCE_COMPONENTS.NOMENCLATURE_ID, mat.nomenclatureId)
                    .set(INSTANCE_COMPONENTS.LOT_ID, lotId)
                    .set(INSTANCE_COMPONENTS.SERIAL_ID, serialId)
                    .set(INSTANCE_COMPONENTS.QUANTITY, qty)
                    .set(INSTANCE_COMPONENTS.INSTALLED_BY, currentUser.userId)
                    .execute()
            }
            auditService.log(
                currentUser.userId,
                "PROD_MATERIAL_CONSUME",
                "PRODUCTION_ORDER",
                orderId,
                mapOf("materialId" to materialId, "qty" to qty.toPlainString(), "instanceId" to request.instanceId)
            )
        }
        return listMaterials(orderId, currentUser).first { it.id == materialId }
    }

    fun listEngineeringChanges(orderId: Long, currentUser: CurrentUser): List<EngineeringChangeImpactDto> {
        currentUser.requirePermission("production", "read")
        return dsl.selectFrom(ENGINEERING_CHANGE_IMPACTS)
            .where(ENGINEERING_CHANGE_IMPACTS.PRODUCTION_ORDER_ID.eq(orderId))
            .orderBy(ENGINEERING_CHANGE_IMPACTS.ID.desc())
            .fetch { r ->
                EngineeringChangeImpactDto(
                    id = r.id,
                    productionOrderId = r.productionOrderId,
                    oldBomRevisionId = r.oldBomRevisionId,
                    newBomRevisionId = r.newBomRevisionId,
                    impactSummary = r.impactSummary,
                    materialDecision = EngineeringChangeDecision.valueOf(r.materialDecision),
                    decisionComment = r.decisionComment,
                    status = EngineeringChangeStatus.valueOf(r.status),
                    createdBy = r.createdBy,
                    createdAt = r.createdAt?.toString(),
                    approvedBy = r.approvedBy,
                    approvedAt = r.approvedAt?.toString(),
                    version = r.version ?: 0
                )
            }
    }

    fun createEngineeringChange(
        orderId: Long,
        request: CreateEngineeringChangeRequest,
        currentUser: CurrentUser
    ): EngineeringChangeImpactDto {
        currentUser.requirePermission("production", "manage")
        val order = dsl.selectFrom(PRODUCTION_ORDERS).where(PRODUCTION_ORDERS.ID.eq(orderId)).fetchOne()
            ?: throw NotFoundException("production_order", orderId)
        if (request.impactSummary.isBlank()) throw ValidationException("impactSummary is required")
        val rev = dsl.selectFrom(PRODUCT_REVISIONS).where(PRODUCT_REVISIONS.ID.eq(request.newBomRevisionId)).fetchOne()
            ?: throw NotFoundException("bom_revision", request.newBomRevisionId)
        if (rev.status !in setOf("APPROVED", "ACTIVE")) {
            throw ConflictException("New BOM revision must be APPROVED or ACTIVE")
        }
        val id = dsl.transactionResult(TransactionalCallable {
            val rec = dsl.insertInto(ENGINEERING_CHANGE_IMPACTS)
                .set(ENGINEERING_CHANGE_IMPACTS.PRODUCTION_ORDER_ID, orderId)
                .set(ENGINEERING_CHANGE_IMPACTS.OLD_BOM_REVISION_ID, order.bomRevisionId)
                .set(ENGINEERING_CHANGE_IMPACTS.NEW_BOM_REVISION_ID, request.newBomRevisionId)
                .set(ENGINEERING_CHANGE_IMPACTS.IMPACT_SUMMARY, request.impactSummary.trim())
                .set(ENGINEERING_CHANGE_IMPACTS.MATERIAL_DECISION, EngineeringChangeDecision.PENDING.name)
                .set(ENGINEERING_CHANGE_IMPACTS.STATUS, EngineeringChangeStatus.OPEN.name)
                .set(ENGINEERING_CHANGE_IMPACTS.CREATED_BY, currentUser.userId)
                .returning(ENGINEERING_CHANGE_IMPACTS.ID)
                .fetchOne()!!
            auditService.log(currentUser.userId, "ENGINEERING_CHANGE_CREATE", "PRODUCTION_ORDER", orderId)
            rec.id
        })
        return listEngineeringChanges(orderId, currentUser).first { it.id == id }
    }

    fun decideEngineeringChange(
        changeId: Long,
        request: DecideEngineeringChangeRequest,
        currentUser: CurrentUser
    ): EngineeringChangeImpactDto {
        currentUser.requirePermission("bom", "approve")
        val existing = dsl.selectFrom(ENGINEERING_CHANGE_IMPACTS)
            .where(ENGINEERING_CHANGE_IMPACTS.ID.eq(changeId))
            .fetchOne() ?: throw NotFoundException("engineering_change", changeId)
        if ((existing.version ?: 0L) != request.version) throw ConflictException("Version conflict")
        val approvedNow = request.status in setOf(EngineeringChangeStatus.APPROVED, EngineeringChangeStatus.APPLIED)
        dsl.transaction { _ ->
            val updated = dsl.update(ENGINEERING_CHANGE_IMPACTS)
                .set(ENGINEERING_CHANGE_IMPACTS.MATERIAL_DECISION, request.materialDecision.name)
                .set(ENGINEERING_CHANGE_IMPACTS.STATUS, request.status.name)
                .set(ENGINEERING_CHANGE_IMPACTS.DECISION_COMMENT, request.decisionComment?.trim())
                .set(ENGINEERING_CHANGE_IMPACTS.APPROVED_BY, if (approvedNow) currentUser.userId else existing.approvedBy)
                .set(ENGINEERING_CHANGE_IMPACTS.APPROVED_AT, if (approvedNow) OffsetDateTime.now() else existing.approvedAt)
                .set(ENGINEERING_CHANGE_IMPACTS.VERSION, ENGINEERING_CHANGE_IMPACTS.VERSION.plus(1))
                .where(ENGINEERING_CHANGE_IMPACTS.ID.eq(changeId))
                .and(ENGINEERING_CHANGE_IMPACTS.VERSION.eq(request.version))
                .execute()
            if (updated == 0) throw ConflictException("Version conflict")
            auditService.log(
                currentUser.userId,
                "ENGINEERING_CHANGE_DECIDE",
                "PRODUCTION_ORDER",
                existing.productionOrderId,
                mapOf("changeId" to changeId, "decision" to request.materialDecision.name, "status" to request.status.name)
            )
        }
        return listEngineeringChanges(existing.productionOrderId, currentUser).first { it.id == changeId }
    }

    fun listNonconformities(
        objectType: String?,
        objectId: Long?,
        status: NonconformityStatus?,
        currentUser: CurrentUser
    ): List<NonconformityDto> {
        currentUser.requirePermission("production", "read")
        var cond: org.jooq.Condition = DSL.trueCondition()
        objectType?.trim()?.takeIf { it.isNotEmpty() }?.let { cond = cond.and(NONCONFORMITIES.OBJECT_TYPE.eq(it)) }
        if (objectId != null) cond = cond.and(NONCONFORMITIES.OBJECT_ID.eq(objectId))
        if (status != null) cond = cond.and(NONCONFORMITIES.STATUS.eq(status.name))
        return dsl.selectFrom(NONCONFORMITIES)
            .where(cond)
            .orderBy(NONCONFORMITIES.ID.desc())
            .fetch { r ->
                NonconformityDto(
                    id = r.id,
                    number = r.number,
                    category = r.category,
                    objectType = r.objectType,
                    objectId = r.objectId,
                    description = r.description,
                    severity = NonconformitySeverity.valueOf(r.severity),
                    status = NonconformityStatus.valueOf(r.status),
                    temporaryMeasures = r.temporaryMeasures,
                    responsibleId = r.responsibleId,
                    createdBy = r.createdBy,
                    createdAt = r.createdAt?.toString(),
                    version = r.version ?: 0
                )
            }
    }

    fun createNonconformity(request: CreateNonconformityRequest, currentUser: CurrentUser): NonconformityDto {
        currentUser.requirePermission("production", "manage")
        if (request.category.isBlank() || request.description.isBlank()) {
            throw ValidationException("category and description are required")
        }
        val id = dsl.transactionResult(TransactionalCallable {
            dsl.execute("SELECT pg_advisory_xact_lock(720018)")
            val year = Year.now().value
            val prefix = "NCR-$year-"
            val count = dsl.fetchCount(dsl.selectFrom(NONCONFORMITIES).where(NONCONFORMITIES.NUMBER.like("$prefix%"))) + 1
            val number = "$prefix${count.toString().padStart(5, '0')}"
            val rec = dsl.insertInto(NONCONFORMITIES)
                .set(NONCONFORMITIES.NUMBER, number)
                .set(NONCONFORMITIES.CATEGORY, request.category.trim())
                .set(NONCONFORMITIES.OBJECT_TYPE, request.objectType.trim().uppercase())
                .set(NONCONFORMITIES.OBJECT_ID, request.objectId)
                .set(NONCONFORMITIES.DESCRIPTION, request.description.trim())
                .set(NONCONFORMITIES.SEVERITY, request.severity.name)
                .set(NONCONFORMITIES.TEMPORARY_MEASURES, request.temporaryMeasures?.trim())
                .set(NONCONFORMITIES.AUTHOR_ID, currentUser.userId)
                .set(NONCONFORMITIES.STATUS, NonconformityStatus.REGISTERED.name)
                .set(NONCONFORMITIES.CREATED_BY, currentUser.userId)
                .returning(NONCONFORMITIES.ID)
                .fetchOne()!!
            dsl.insertInto(NONCONFORMITY_HISTORY)
                .set(NONCONFORMITY_HISTORY.NONCONFORMITY_ID, rec.id)
                .set(NONCONFORMITY_HISTORY.ACTION, "CREATED")
                .set(NONCONFORMITY_HISTORY.FROM_STATUS, null as String?)
                .set(NONCONFORMITY_HISTORY.TO_STATUS, NonconformityStatus.REGISTERED.name)
                .set(NONCONFORMITY_HISTORY.CHANGED_BY, currentUser.userId)
                .set(NONCONFORMITY_HISTORY.REASON, "Created")
                .execute()
            auditService.log(currentUser.userId, "NCR_CREATE", "NONCONFORMITY", rec.id, mapOf("number" to number))
            rec.id
        })
        return listNonconformities(null, null, null, currentUser).first { it.id == id }
    }

    fun transitionNonconformity(
        id: Long,
        request: TransitionNonconformityRequest,
        currentUser: CurrentUser
    ): NonconformityDto {
        currentUser.requirePermission("production", "manage")
        val existing = dsl.selectFrom(NONCONFORMITIES).where(NONCONFORMITIES.ID.eq(id)).fetchOne()
            ?: throw NotFoundException("nonconformity", id)
        if ((existing.version ?: 0L) != request.version) throw ConflictException("Version conflict")
        val action = if (request.targetStatus == NonconformityStatus.CLOSED) "CLOSED" else "STATUS_CHANGED"
        dsl.transaction { _ ->
            val updated = dsl.update(NONCONFORMITIES)
                .set(NONCONFORMITIES.STATUS, request.targetStatus.name)
                .set(NONCONFORMITIES.VERSION, NONCONFORMITIES.VERSION.plus(1))
                .where(NONCONFORMITIES.ID.eq(id))
                .and(NONCONFORMITIES.VERSION.eq(request.version))
                .execute()
            if (updated == 0) throw ConflictException("Version conflict")
            dsl.insertInto(NONCONFORMITY_HISTORY)
                .set(NONCONFORMITY_HISTORY.NONCONFORMITY_ID, id)
                .set(NONCONFORMITY_HISTORY.ACTION, action)
                .set(NONCONFORMITY_HISTORY.FROM_STATUS, existing.status)
                .set(NONCONFORMITY_HISTORY.TO_STATUS, request.targetStatus.name)
                .set(NONCONFORMITY_HISTORY.CHANGED_BY, currentUser.userId)
                .set(NONCONFORMITY_HISTORY.REASON, request.reason?.trim())
                .execute()
            auditService.log(
                currentUser.userId,
                "NCR_TRANSITION",
                "NONCONFORMITY",
                id,
                mapOf("from" to existing.status, "to" to request.targetStatus.name)
            )
        }
        return listNonconformities(null, null, null, currentUser).first { it.id == id }
    }
}
