package org.example.project.repository

import org.example.project.generated.jooq.tables.Departments.DEPARTMENTS
import org.example.project.generated.jooq.tables.ProductInstances.PRODUCT_INSTANCES
import org.example.project.generated.jooq.tables.ProductModels.PRODUCT_MODELS
import org.example.project.generated.jooq.tables.ProductRevisions.PRODUCT_REVISIONS
import org.example.project.generated.jooq.tables.ProductionOrderStages.PRODUCTION_ORDER_STAGES
import org.example.project.generated.jooq.tables.ProductionOrders.PRODUCTION_ORDERS
import org.example.project.generated.jooq.tables.ProductionStageAssignees.PRODUCTION_STAGE_ASSIGNEES
import org.example.project.generated.jooq.tables.Projects.PROJECTS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.records.ProductInstancesRecord
import org.example.project.generated.jooq.tables.records.ProductionOrderStagesRecord
import org.example.project.generated.jooq.tables.records.ProductionOrdersRecord
import org.example.project.generated.jooq.tables.records.ProductionStageAssigneesRecord
import org.jooq.Condition
import org.jooq.DSLContext
import java.time.OffsetDateTime

class ProductionRepository(private val dsl: DSLContext) {

    fun findOrderById(id: Long): ProductionOrdersRecord? =
        dsl.selectFrom(PRODUCTION_ORDERS).where(PRODUCTION_ORDERS.ID.eq(id)).fetchOne()

    fun lockOrderById(id: Long): ProductionOrdersRecord? =
        dsl.selectFrom(PRODUCTION_ORDERS).where(PRODUCTION_ORDERS.ID.eq(id)).forUpdate().fetchOne()

    fun listOrders(
        projectId: Long?,
        productModelId: Long?,
        status: String?
    ): List<ProductionOrdersRecord> {
        val conditions = mutableListOf<Condition>()
        projectId?.let { conditions += PRODUCTION_ORDERS.PROJECT_ID.eq(it) }
        productModelId?.let { conditions += PRODUCTION_ORDERS.PRODUCT_MODEL_ID.eq(it) }
        status?.let { conditions += PRODUCTION_ORDERS.STATUS.eq(it) }
        return dsl.selectFrom(PRODUCTION_ORDERS)
            .where(conditions)
            .orderBy(PRODUCTION_ORDERS.ID.desc())
            .fetch()
    }

    fun projectExists(id: Long): Boolean =
        dsl.fetchExists(dsl.selectOne().from(PROJECTS).where(PROJECTS.ID.eq(id)))

    fun productModelExists(id: Long): Boolean =
        dsl.fetchExists(dsl.selectOne().from(PRODUCT_MODELS).where(PRODUCT_MODELS.ID.eq(id)))

    // Ревизия BOM: должна принадлежать изделию заказа и быть APPROVED/ACTIVE.
    fun revisionStatus(revisionId: Long): Pair<String, Long>? =
        dsl.select(PRODUCT_REVISIONS.STATUS, PRODUCT_REVISIONS.PRODUCT_MODEL_ID)
            .from(PRODUCT_REVISIONS)
            .where(PRODUCT_REVISIONS.ID.eq(revisionId))
            .fetchOne()
            ?.let { (it.value1() as String) to it.value2() }

    fun userExists(id: Long): Boolean =
        dsl.fetchExists(dsl.selectOne().from(USERS).where(USERS.ID.eq(id)))

    fun departmentExists(id: Long): Boolean =
        dsl.fetchExists(dsl.selectOne().from(DEPARTMENTS).where(DEPARTMENTS.ID.eq(id)))

    fun insertOrder(
        number: String,
        projectId: Long,
        productModelId: Long,
        bomRevisionId: Long,
        plannedQuantity: Int,
        plannedStartAt: OffsetDateTime?,
        plannedEndAt: OffsetDateTime?,
        responsibleId: Long,
        departmentId: Long?,
        priority: String,
        createdBy: Long
    ): ProductionOrdersRecord {
        val record = dsl.newRecord(PRODUCTION_ORDERS)
        record.number = number
        record.projectId = projectId
        record.productModelId = productModelId
        record.bomRevisionId = bomRevisionId
        record.plannedQuantity = plannedQuantity
        record.plannedStartAt = plannedStartAt
        record.plannedEndAt = plannedEndAt
        record.responsibleId = responsibleId
        record.departmentId = departmentId
        record.priority = priority
        record.createdBy = createdBy
        record.store()
        return findOrderById(record.id)!!
    }

    fun listStages(orderId: Long): List<ProductionOrderStagesRecord> =
        dsl.selectFrom(PRODUCTION_ORDER_STAGES)
            .where(PRODUCTION_ORDER_STAGES.ORDER_ID.eq(orderId))
            .orderBy(PRODUCTION_ORDER_STAGES.SEQUENCE.asc())
            .fetch()

    fun findStageById(id: Long): ProductionOrderStagesRecord? =
        dsl.selectFrom(PRODUCTION_ORDER_STAGES).where(PRODUCTION_ORDER_STAGES.ID.eq(id)).fetchOne()

    fun lockStageById(id: Long): ProductionOrderStagesRecord? =
        dsl.selectFrom(PRODUCTION_ORDER_STAGES).where(PRODUCTION_ORDER_STAGES.ID.eq(id)).forUpdate().fetchOne()

    fun insertStage(
        orderId: Long,
        stageType: String,
        name: String,
        sequence: Int,
        responsibleId: Long?,
        plannedStartAt: OffsetDateTime?,
        plannedEndAt: OffsetDateTime?,
        createdBy: Long
    ): ProductionOrderStagesRecord {
        val record = dsl.newRecord(PRODUCTION_ORDER_STAGES)
        record.orderId = orderId
        record.stageType = stageType
        record.name = name
        record.sequence = sequence
        record.responsibleId = responsibleId
        record.plannedStartAt = plannedStartAt
        record.plannedEndAt = plannedEndAt
        record.createdBy = createdBy
        record.store()
        return record
    }

    fun listAssignees(stageIds: List<Long>): List<ProductionStageAssigneesRecord> {
        if (stageIds.isEmpty()) return emptyList()
        return dsl.selectFrom(PRODUCTION_STAGE_ASSIGNEES)
            .where(PRODUCTION_STAGE_ASSIGNEES.STAGE_ID.`in`(stageIds))
            .fetch()
    }

    // Переходы статусов заказа — условные UPDATE по id + version.
    fun updateOrderStatus(
        id: Long,
        expectedVersion: Long,
        fromStatuses: List<String>,
        newStatus: String,
        actualStartAt: OffsetDateTime? = null,
        actualEndAt: OffsetDateTime? = null,
        actualQuantity: Int? = null
    ): ProductionOrdersRecord? {
        var update = dsl.update(PRODUCTION_ORDERS)
            .set(PRODUCTION_ORDERS.STATUS, newStatus)
            .set(PRODUCTION_ORDERS.UPDATED_AT, OffsetDateTime.now())
            .set(PRODUCTION_ORDERS.VERSION, PRODUCTION_ORDERS.VERSION.plus(1))
        actualStartAt?.let { update = update.set(PRODUCTION_ORDERS.ACTUAL_START_AT, it) }
        actualEndAt?.let { update = update.set(PRODUCTION_ORDERS.ACTUAL_END_AT, it) }
        actualQuantity?.let { update = update.set(PRODUCTION_ORDERS.ACTUAL_QUANTITY, it) }
        return update
            .where(PRODUCTION_ORDERS.ID.eq(id))
            .and(PRODUCTION_ORDERS.VERSION.eq(expectedVersion))
            .and(PRODUCTION_ORDERS.STATUS.`in`(fromStatuses))
            .returning()
            .fetchOne()
    }

    fun updateStageStatus(
        id: Long,
        expectedVersion: Long,
        fromStatuses: List<String>,
        newStatus: String,
        actualStartAt: OffsetDateTime? = null,
        actualEndAt: OffsetDateTime? = null,
        skipReason: String? = null
    ): ProductionOrderStagesRecord? {
        var update = dsl.update(PRODUCTION_ORDER_STAGES)
            .set(PRODUCTION_ORDER_STAGES.STATUS, newStatus)
            .set(PRODUCTION_ORDER_STAGES.VERSION, PRODUCTION_ORDER_STAGES.VERSION.plus(1))
        actualStartAt?.let { update = update.set(PRODUCTION_ORDER_STAGES.ACTUAL_START_AT, it) }
        actualEndAt?.let { update = update.set(PRODUCTION_ORDER_STAGES.ACTUAL_END_AT, it) }
        skipReason?.let { update = update.set(PRODUCTION_ORDER_STAGES.SKIP_REASON, it) }
        return update
            .where(PRODUCTION_ORDER_STAGES.ID.eq(id))
            .and(PRODUCTION_ORDER_STAGES.VERSION.eq(expectedVersion))
            .and(PRODUCTION_ORDER_STAGES.STATUS.`in`(fromStatuses))
            .returning()
            .fetchOne()
    }

    fun countOpenStages(orderId: Long): Int =
        dsl.fetchCount(
            PRODUCTION_ORDER_STAGES,
            PRODUCTION_ORDER_STAGES.ORDER_ID.eq(orderId)
                .and(PRODUCTION_ORDER_STAGES.STATUS.`in`("PENDING", "IN_PROGRESS"))
        )

    // Экземпляры изделия создаются только при завершении выпуска.
    fun insertInstance(
        orderId: Long,
        projectId: Long,
        productModelId: Long,
        bomRevisionId: Long,
        serialNumber: String,
        createdBy: Long
    ): ProductInstancesRecord {
        val record = dsl.newRecord(PRODUCT_INSTANCES)
        record.orderId = orderId
        record.projectId = projectId
        record.productModelId = productModelId
        record.bomRevisionId = bomRevisionId
        record.serialNumber = serialNumber
        record.createdBy = createdBy
        record.store()
        return record
    }

    fun listInstances(orderId: Long): List<ProductInstancesRecord> =
        dsl.selectFrom(PRODUCT_INSTANCES)
            .where(PRODUCT_INSTANCES.ORDER_ID.eq(orderId))
            .orderBy(PRODUCT_INSTANCES.ID.asc())
            .fetch()

    fun serialNumberExists(serialNumber: String): Boolean =
        dsl.fetchExists(
            dsl.selectOne().from(PRODUCT_INSTANCES)
                .where(PRODUCT_INSTANCES.SERIAL_NUMBER.eq(serialNumber))
        )

    fun generateNumber(): String {
        val year = OffsetDateTime.now().year
        dsl.fetch("SELECT pg_advisory_xact_lock(?)", year * 10000L + 15L)
        val prefix = "PRO-$year-"
        val maxNumber = dsl.select(PRODUCTION_ORDERS.NUMBER)
            .from(PRODUCTION_ORDERS)
            .where(PRODUCTION_ORDERS.NUMBER.like("$prefix%"))
            .orderBy(PRODUCTION_ORDERS.NUMBER.desc())
            .limit(1)
            .fetchOne(PRODUCTION_ORDERS.NUMBER)
        val nextIndex = maxNumber?.removePrefix(prefix)?.toIntOrNull()?.plus(1) ?: 1
        return String.format("$prefix%05d", nextIndex)
    }
}
