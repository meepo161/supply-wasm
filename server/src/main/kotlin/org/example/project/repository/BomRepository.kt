package org.example.project.repository

import org.example.project.generated.jooq.tables.BomLines.BOM_LINES
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.ProductModels.PRODUCT_MODELS
import org.example.project.generated.jooq.tables.ProductRevisions.PRODUCT_REVISIONS
import org.example.project.generated.jooq.tables.ReceiptLines.RECEIPT_LINES
import org.example.project.generated.jooq.tables.Receipts.RECEIPTS
import org.example.project.generated.jooq.tables.StockBalance.STOCK_BALANCE
import org.example.project.generated.jooq.tables.StorageLocations.STORAGE_LOCATIONS
import org.example.project.generated.jooq.tables.records.BomLinesRecord
import org.example.project.generated.jooq.tables.records.ProductModelsRecord
import org.example.project.generated.jooq.tables.records.ProductRevisionsRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.OffsetDateTime

class BomRepository(private val dsl: DSLContext) {

    data class BomLineNode(
        val line: BomLinesRecord,
        val children: MutableList<BomLineNode> = mutableListOf()
    )

    data class StockInfo(
        val physicalQty: BigDecimal,
        val reservedQty: BigDecimal,
        val availableQty: BigDecimal
    )

    // --- Product models ---

    fun findProductModelById(id: Long): ProductModelsRecord? {
        return dsl.selectFrom(PRODUCT_MODELS)
            .where(PRODUCT_MODELS.ID.eq(id))
            .fetchOne()
    }

    fun findProductModelByCode(code: String): ProductModelsRecord? {
        return dsl.selectFrom(PRODUCT_MODELS)
            .where(PRODUCT_MODELS.CODE.eq(code))
            .fetchOne()
    }

    fun existsProductModelByCode(code: String): Boolean {
        return dsl.fetchExists(
            dsl.selectOne().from(PRODUCT_MODELS).where(PRODUCT_MODELS.CODE.eq(code))
        )
    }

    fun listProductModels(): List<ProductModelsRecord> {
        return dsl.selectFrom(PRODUCT_MODELS)
            .orderBy(PRODUCT_MODELS.CODE.asc())
            .fetch()
    }

    fun createProductModel(
        code: String,
        name: String,
        description: String?,
        status: String,
        designerId: Long?,
        technologistId: Long?,
        createdBy: Long
    ): ProductModelsRecord {
        val record = dsl.newRecord(PRODUCT_MODELS)
        record.code = code
        record.name = name
        record.description = description
        record.developmentStatus = status
        record.designerId = designerId
        record.technologistId = technologistId
        record.createdBy = createdBy
        record.store()
        return findProductModelById(record.id)!!
    }

    fun updateProductModel(
        id: Long,
        expectedVersion: Long,
        name: String?,
        description: String?,
        status: String?,
        designerId: Long?,
        technologistId: Long?
    ): ProductModelsRecord? {
        val sets = dsl.update(PRODUCT_MODELS)
        name?.let { sets.set(PRODUCT_MODELS.NAME, it) }
        description?.let { sets.set(PRODUCT_MODELS.DESCRIPTION, it) }
        status?.let { sets.set(PRODUCT_MODELS.DEVELOPMENT_STATUS, it) }
        sets.set(PRODUCT_MODELS.DESIGNER_ID, designerId)
        sets.set(PRODUCT_MODELS.TECHNOLOGIST_ID, technologistId)
        return sets.set(PRODUCT_MODELS.VERSION, PRODUCT_MODELS.VERSION.plus(1))
            .where(PRODUCT_MODELS.ID.eq(id))
            .and(PRODUCT_MODELS.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne()
    }

    // --- Revisions ---

    fun findRevisionById(id: Long): ProductRevisionsRecord? {
        return dsl.selectFrom(PRODUCT_REVISIONS)
            .where(PRODUCT_REVISIONS.ID.eq(id))
            .fetchOne()
    }

    fun findRevisionByModelAndNumber(modelId: Long, number: String): ProductRevisionsRecord? {
        return dsl.selectFrom(PRODUCT_REVISIONS)
            .where(PRODUCT_REVISIONS.PRODUCT_MODEL_ID.eq(modelId))
            .and(PRODUCT_REVISIONS.REVISION_NUMBER.eq(number))
            .fetchOne()
    }

    fun listRevisionsByModel(modelId: Long): List<ProductRevisionsRecord> {
        return dsl.selectFrom(PRODUCT_REVISIONS)
            .where(PRODUCT_REVISIONS.PRODUCT_MODEL_ID.eq(modelId))
            .orderBy(PRODUCT_REVISIONS.CREATED_AT.desc())
            .fetch()
    }

    fun createRevision(
        modelId: Long,
        revisionNumber: String,
        changeReason: String?,
        createdBy: Long
    ): ProductRevisionsRecord {
        val record = dsl.newRecord(PRODUCT_REVISIONS)
        record.productModelId = modelId
        record.revisionNumber = revisionNumber
        record.status = "DRAFT"
        record.changeReason = changeReason
        record.createdBy = createdBy
        record.store()
        return findRevisionById(record.id)!!
    }

    fun updateRevisionStatus(
        id: Long,
        expectedVersion: Long,
        fromStatus: String?,
        newStatus: String,
        approvedBy: Long? = null,
        approvedAt: OffsetDateTime? = null
    ): ProductRevisionsRecord? {
        val update = dsl.update(PRODUCT_REVISIONS)
            .set(PRODUCT_REVISIONS.STATUS, newStatus)
            .set(PRODUCT_REVISIONS.VERSION, PRODUCT_REVISIONS.VERSION.plus(1))
        approvedBy?.let { update.set(PRODUCT_REVISIONS.APPROVED_BY, it) }
        approvedAt?.let { update.set(PRODUCT_REVISIONS.APPROVED_AT, it) }
        var where: org.jooq.UpdateConditionStep<ProductRevisionsRecord> = update.where(PRODUCT_REVISIONS.ID.eq(id))
            .and(PRODUCT_REVISIONS.VERSION.eq(expectedVersion))
        fromStatus?.let { where = where.and(PRODUCT_REVISIONS.STATUS.eq(it)) }
        return where.returning().fetchOne()
    }

    fun setActiveRevisionReplacing(modelId: Long, activeRevisionId: Long): Int {
        return dsl.update(PRODUCT_REVISIONS)
            .set(PRODUCT_REVISIONS.STATUS, "REPLACED")
            .set(PRODUCT_REVISIONS.VERSION, PRODUCT_REVISIONS.VERSION.plus(1))
            .where(PRODUCT_REVISIONS.PRODUCT_MODEL_ID.eq(modelId))
            .and(PRODUCT_REVISIONS.STATUS.eq("ACTIVE"))
            .and(PRODUCT_REVISIONS.ID.notEqual(activeRevisionId))
            .execute()
    }

    fun copyRevision(
        sourceRevisionId: Long,
        modelId: Long,
        newRevisionNumber: String,
        reason: String,
        createdBy: Long
    ): ProductRevisionsRecord {
        val newRevision = createRevision(modelId, newRevisionNumber, reason, createdBy)
        val lines = listBomLinesByRevision(sourceRevisionId)
        val idMap = mutableMapOf<Long, Long>()
        // First pass: create lines without hierarchy references to get stable ids.
        for (line in lines) {
            val newLine = dsl.newRecord(BOM_LINES)
            newLine.revisionId = newRevision.id
            newLine.parentLineId = null
            newLine.parentNomenclatureId = line.parentNomenclatureId
            newLine.childNomenclatureId = line.childNomenclatureId
            newLine.quantity = line.quantity
            newLine.unit = line.unit
            newLine.position = line.position
            newLine.level = line.level
            newLine.mandatory = line.mandatory
            newLine.substituteOfLineId = null
            newLine.consumptionRate = line.consumptionRate
            newLine.lossPct = line.lossPct
            newLine.createdBy = createdBy
            newLine.store()
            idMap[line.id] = newLine.id
        }
        // Second pass: restore hierarchy and substitute links inside the new revision.
        for (line in lines) {
            val newLineId = idMap[line.id] ?: continue
            val newParentLineId = line.parentLineId?.let { idMap[it] }
            val newSubstituteOfLineId = line.substituteOfLineId?.let { idMap[it] }
            if (newParentLineId != null || newSubstituteOfLineId != null) {
                dsl.update(BOM_LINES)
                    .set(BOM_LINES.PARENT_LINE_ID, newParentLineId)
                    .set(BOM_LINES.SUBSTITUTE_OF_LINE_ID, newSubstituteOfLineId)
                    .where(BOM_LINES.ID.eq(newLineId))
                    .execute()
            }
        }
        return newRevision
    }

    // --- BOM lines ---

    fun findBomLineById(id: Long): BomLinesRecord? {
        return dsl.selectFrom(BOM_LINES)
            .where(BOM_LINES.ID.eq(id))
            .fetchOne()
    }

    fun listBomLinesByRevision(revisionId: Long): List<BomLinesRecord> {
        return dsl.selectFrom(BOM_LINES)
            .where(BOM_LINES.REVISION_ID.eq(revisionId))
            .orderBy(BOM_LINES.LEVEL.asc(), BOM_LINES.POSITION.asc().nullsLast(), BOM_LINES.ID.asc())
            .fetch()
    }

    fun createBomLine(
        revisionId: Long,
        parentLineId: Long?,
        parentNomenclatureId: Long,
        childNomenclatureId: Long,
        quantity: BigDecimal,
        unit: String,
        position: String?,
        level: Int,
        mandatory: Boolean,
        substituteOfLineId: Long?,
        consumptionRate: BigDecimal?,
        lossPct: BigDecimal,
        createdBy: Long
    ): BomLinesRecord {
        val record = dsl.newRecord(BOM_LINES)
        record.revisionId = revisionId
        record.parentLineId = parentLineId
        record.parentNomenclatureId = parentNomenclatureId
        record.childNomenclatureId = childNomenclatureId
        record.quantity = quantity
        record.unit = unit
        record.position = position
        record.level = level
        record.mandatory = mandatory
        record.substituteOfLineId = substituteOfLineId
        record.consumptionRate = consumptionRate
        record.lossPct = lossPct
        record.createdBy = createdBy
        record.store()
        return findBomLineById(record.id)!!
    }

    fun updateBomLine(
        id: Long,
        quantity: BigDecimal?,
        unit: String?,
        position: String?,
        level: Int?,
        mandatory: Boolean?,
        consumptionRate: BigDecimal?,
        lossPct: BigDecimal?
    ): BomLinesRecord? {
        val current = findBomLineById(id) ?: return null
        val update = dsl.update(BOM_LINES)
            .set(BOM_LINES.LEVEL, level ?: current.level)
        quantity?.let { update.set(BOM_LINES.QUANTITY, it) }
        unit?.let { update.set(BOM_LINES.UNIT, it) }
        position?.let { update.set(BOM_LINES.POSITION, it) }
        mandatory?.let { update.set(BOM_LINES.MANDATORY, it) }
        consumptionRate?.let { update.set(BOM_LINES.CONSUMPTION_RATE, it) }
        lossPct?.let { update.set(BOM_LINES.LOSS_PCT, it) }
        return update.where(BOM_LINES.ID.eq(id))
            .returning()
            .fetchOne()
    }

    fun deleteBomLine(id: Long): Boolean {
        return dsl.deleteFrom(BOM_LINES)
            .where(BOM_LINES.ID.eq(id))
            .execute() > 0
    }

    fun loadBomTree(revisionId: Long): List<BomLineNode> {
        val lines = listBomLinesByRevision(revisionId)
        val byId = lines.associateBy { it.id }
        val roots = mutableListOf<BomLineNode>()
        val nodeById = mutableMapOf<Long, BomLineNode>()
        for (line in lines) {
            nodeById[line.id] = BomLineNode(line)
        }
        for (line in lines) {
            val node = nodeById[line.id]!!
            val parentId = line.parentLineId
            if (parentId == null) {
                roots.add(node)
            } else {
                nodeById[parentId]?.children?.add(node)
            }
        }
        return roots
    }

    // --- Nomenclature helpers ---

    fun nomenclatureExists(id: Long): Boolean {
        return dsl.fetchExists(
            dsl.selectOne().from(NOMENCLATURE).where(NOMENCLATURE.ID.eq(id))
        )
    }

    fun findNomenclatureById(id: Long): org.example.project.generated.jooq.tables.records.NomenclatureRecord? {
        return dsl.selectFrom(NOMENCLATURE)
            .where(NOMENCLATURE.ID.eq(id))
            .fetchOne()
    }

    fun loadNomenclatureNames(ids: Set<Long>): Map<Long, Pair<String, String>> {
        if (ids.isEmpty()) return emptyMap()
        return dsl.select(NOMENCLATURE.ID, NOMENCLATURE.CODE, NOMENCLATURE.NAME)
            .from(NOMENCLATURE)
            .where(NOMENCLATURE.ID.`in`(ids))
            .fetchMap(NOMENCLATURE.ID) { record: Record ->
                record.get(NOMENCLATURE.CODE) to record.get(NOMENCLATURE.NAME)
            }
    }

    // --- Stock / expected receipts ---

    fun stockSummary(nomenclatureIds: Set<Long>, warehouseId: Long?): Map<Long, StockInfo> {
        if (nomenclatureIds.isEmpty()) return emptyMap()
        val conditions = mutableListOf<Condition>(
            STOCK_BALANCE.NOMENCLATURE_ID.`in`(nomenclatureIds)
        )
        warehouseId?.let { conditions += STOCK_BALANCE.WAREHOUSE_ID.eq(it) }
        conditions += STORAGE_LOCATIONS.PURPOSE.notIn("QUARANTINE", "DEFECT")
        return dsl.select(
            STOCK_BALANCE.NOMENCLATURE_ID,
            DSL.sum(STOCK_BALANCE.PHYSICAL_QTY),
            DSL.sum(STOCK_BALANCE.RESERVED_QTY),
            DSL.sum(STOCK_BALANCE.AVAILABLE_QTY)
        )
            .from(STOCK_BALANCE)
            .join(STORAGE_LOCATIONS).on(STORAGE_LOCATIONS.ID.eq(STOCK_BALANCE.LOCATION_ID))
            .where(conditions)
            .groupBy(STOCK_BALANCE.NOMENCLATURE_ID)
            .fetchMap(STOCK_BALANCE.NOMENCLATURE_ID) { record: Record ->
                StockInfo(
                    physicalQty = record.get(DSL.sum(STOCK_BALANCE.PHYSICAL_QTY)) ?: BigDecimal.ZERO,
                    reservedQty = record.get(DSL.sum(STOCK_BALANCE.RESERVED_QTY)) ?: BigDecimal.ZERO,
                    availableQty = record.get(DSL.sum(STOCK_BALANCE.AVAILABLE_QTY)) ?: BigDecimal.ZERO
                )
            }
    }

    fun expectedReceipts(nomenclatureIds: Set<Long>): Map<Long, BigDecimal> {
        if (nomenclatureIds.isEmpty()) return emptyMap()
        return dsl.select(
            RECEIPT_LINES.NOMENCLATURE_ID,
            DSL.sum(RECEIPT_LINES.EXPECTED_QUANTITY.minus(RECEIPT_LINES.RECEIVED_QUANTITY))
        )
            .from(RECEIPT_LINES)
            .join(RECEIPTS).on(RECEIPTS.ID.eq(RECEIPT_LINES.RECEIPT_ID))
            .where(RECEIPT_LINES.NOMENCLATURE_ID.`in`(nomenclatureIds))
            .and(RECEIPTS.STATUS.notIn("POSTED", "CANCELLED"))
            .and(RECEIPT_LINES.EXPECTED_QUANTITY.gt(RECEIPT_LINES.RECEIVED_QUANTITY))
            .groupBy(RECEIPT_LINES.NOMENCLATURE_ID)
            .fetchMap(RECEIPT_LINES.NOMENCLATURE_ID) { record: Record ->
                record.get(DSL.sum(RECEIPT_LINES.EXPECTED_QUANTITY.minus(RECEIPT_LINES.RECEIVED_QUANTITY)))
                    ?: BigDecimal.ZERO
            }
    }

    // --- User existence ---

    fun userExists(id: Long): Boolean {
        return dsl.fetchExists(
            dsl.selectOne().from(org.example.project.generated.jooq.tables.Users.USERS)
                .where(org.example.project.generated.jooq.tables.Users.USERS.ID.eq(id))
        )
    }
}
