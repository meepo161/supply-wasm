package org.example.project.repository

import org.example.project.generated.jooq.tables.DemandHistory.DEMAND_HISTORY
import org.example.project.generated.jooq.tables.DemandLineHistory.DEMAND_LINE_HISTORY
import org.example.project.generated.jooq.tables.DemandLines.DEMAND_LINES
import org.example.project.generated.jooq.tables.Demands.DEMANDS
import org.example.project.generated.jooq.tables.ProjectMembers.PROJECT_MEMBERS
import org.example.project.generated.jooq.tables.Reservations.RESERVATIONS
import org.example.project.generated.jooq.tables.records.DemandLinesRecord
import org.example.project.generated.jooq.tables.records.DemandsRecord
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
        idempotencyRequestHash: String? = null
    ): DemandHeaderProjection {
        val insert = dsl.insertInto(DEMANDS)
            .set(DEMANDS.NUMBER, number)
            .set(DEMANDS.SOURCE_TYPE, sourceType)
            .set(DEMANDS.PROJECT_ID, projectId)
            .set(DEMANDS.STOCKPILE_NOTE, stockpileNote)
            .set(DEMANDS.INITIATOR_ID, initiatorId)
            .set(DEMANDS.REQUIRED_DATE, requiredDate)
            .set(DEMANDS.PRIORITY, priority)
            .set(DEMANDS.STATUS, "DRAFT")
            .set(DEMANDS.CREATED_BY, createdBy)

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
}
