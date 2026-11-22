package org.example.project.repository

import org.example.project.dto.ProductInstanceComponentDto
import org.example.project.dto.ProductInstanceDetailDto
import org.example.project.dto.ProductInstanceDocumentDto
import org.example.project.dto.ProductInstanceRemarkDto
import org.example.project.dto.ProductInstanceSoftwareDto
import org.example.project.dto.ProductInstanceTestDto
import org.example.project.generated.jooq.tables.Departments.DEPARTMENTS
import org.example.project.generated.jooq.tables.DocumentVersions.DOCUMENT_VERSIONS
import org.example.project.generated.jooq.tables.Documents.DOCUMENTS
import org.example.project.generated.jooq.tables.InstanceComponents.INSTANCE_COMPONENTS
import org.example.project.generated.jooq.tables.InstanceDocuments.INSTANCE_DOCUMENTS
import org.example.project.generated.jooq.tables.InstanceRemarks.INSTANCE_REMARKS
import org.example.project.generated.jooq.tables.InstanceSoftware.INSTANCE_SOFTWARE
import org.example.project.generated.jooq.tables.InstanceTests.INSTANCE_TESTS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
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

    fun findInstanceDetailById(instanceId: Long): ProductInstanceDetailDto? {
        val row = dsl.select(
            PRODUCT_INSTANCES.ID,
            PRODUCT_INSTANCES.ORDER_ID,
            PRODUCTION_ORDERS.NUMBER,
            PRODUCT_INSTANCES.PROJECT_ID,
            PRODUCT_INSTANCES.PRODUCT_MODEL_ID,
            PRODUCT_MODELS.CODE,
            PRODUCT_MODELS.NAME,
            PRODUCT_INSTANCES.BOM_REVISION_ID,
            PRODUCT_INSTANCES.SERIAL_NUMBER,
            PRODUCT_INSTANCES.STATUS,
            PRODUCT_INSTANCES.MANUFACTURED_AT,
            PRODUCT_INSTANCES.ACCEPTED_AT,
            PRODUCT_INSTANCES.SHIPPED_AT,
            PRODUCT_INSTANCES.VERSION
        )
            .from(PRODUCT_INSTANCES)
            .leftJoin(PRODUCTION_ORDERS).on(PRODUCTION_ORDERS.ID.eq(PRODUCT_INSTANCES.ORDER_ID))
            .leftJoin(PRODUCT_MODELS).on(PRODUCT_MODELS.ID.eq(PRODUCT_INSTANCES.PRODUCT_MODEL_ID))
            .where(PRODUCT_INSTANCES.ID.eq(instanceId))
            .fetchOne() ?: return null

        val components = dsl.select(
            INSTANCE_COMPONENTS.ID,
            INSTANCE_COMPONENTS.NOMENCLATURE_ID,
            NOMENCLATURE.CODE,
            NOMENCLATURE.NAME,
            INSTANCE_COMPONENTS.QUANTITY,
            INSTANCE_COMPONENTS.LOT_ID,
            INSTANCE_COMPONENTS.SERIAL_ID,
            INSTANCE_COMPONENTS.SUBSTITUTE_REASON,
            INSTANCE_COMPONENTS.INSTALLED_AT,
            INSTANCE_COMPONENTS.INSTALLED_BY,
            USERS.FULL_NAME
        )
            .from(INSTANCE_COMPONENTS)
            .leftJoin(NOMENCLATURE).on(NOMENCLATURE.ID.eq(INSTANCE_COMPONENTS.NOMENCLATURE_ID))
            .leftJoin(USERS).on(USERS.ID.eq(INSTANCE_COMPONENTS.INSTALLED_BY))
            .where(INSTANCE_COMPONENTS.INSTANCE_ID.eq(instanceId))
            .orderBy(INSTANCE_COMPONENTS.ID.asc())
            .fetch { r ->
                ProductInstanceComponentDto(
                    id = r[INSTANCE_COMPONENTS.ID],
                    nomenclatureId = r[INSTANCE_COMPONENTS.NOMENCLATURE_ID],
                    nomenclatureCode = r[NOMENCLATURE.CODE],
                    nomenclatureName = r[NOMENCLATURE.NAME],
                    quantity = r[INSTANCE_COMPONENTS.QUANTITY].stripTrailingZeros().toPlainString(),
                    lotId = r[INSTANCE_COMPONENTS.LOT_ID],
                    serialId = r[INSTANCE_COMPONENTS.SERIAL_ID],
                    substituteReason = r[INSTANCE_COMPONENTS.SUBSTITUTE_REASON],
                    installedAt = r[INSTANCE_COMPONENTS.INSTALLED_AT].toString(),
                    installedById = r[INSTANCE_COMPONENTS.INSTALLED_BY],
                    installedByName = r[USERS.FULL_NAME]
                )
            }

        val documents = dsl.select(
            INSTANCE_DOCUMENTS.ID,
            DOCUMENTS.ID,
            DOCUMENTS.NAME,
            INSTANCE_DOCUMENTS.DOCUMENT_VERSION_ID,
            DOCUMENT_VERSIONS.VERSION_NUMBER,
            INSTANCE_DOCUMENTS.DOC_KIND,
            INSTANCE_DOCUMENTS.CREATED_AT,
            INSTANCE_DOCUMENTS.CREATED_BY,
            USERS.FULL_NAME
        )
            .from(INSTANCE_DOCUMENTS)
            .leftJoin(DOCUMENT_VERSIONS).on(DOCUMENT_VERSIONS.ID.eq(INSTANCE_DOCUMENTS.DOCUMENT_VERSION_ID))
            .leftJoin(DOCUMENTS).on(DOCUMENTS.ID.eq(DOCUMENT_VERSIONS.DOCUMENT_ID))
            .leftJoin(USERS).on(USERS.ID.eq(INSTANCE_DOCUMENTS.CREATED_BY))
            .where(INSTANCE_DOCUMENTS.INSTANCE_ID.eq(instanceId))
            .orderBy(INSTANCE_DOCUMENTS.ID.asc())
            .fetch { r ->
                ProductInstanceDocumentDto(
                    id = r[INSTANCE_DOCUMENTS.ID],
                    documentId = r[DOCUMENTS.ID],
                    documentTitle = r[DOCUMENTS.NAME],
                    documentVersionId = r[INSTANCE_DOCUMENTS.DOCUMENT_VERSION_ID],
                    documentVersionNumber = r[DOCUMENT_VERSIONS.VERSION_NUMBER],
                    docKind = r[INSTANCE_DOCUMENTS.DOC_KIND],
                    createdAt = r[INSTANCE_DOCUMENTS.CREATED_AT].toString(),
                    createdById = r[INSTANCE_DOCUMENTS.CREATED_BY],
                    createdByName = r[USERS.FULL_NAME]
                )
            }

        val software = dsl.select(
            INSTANCE_SOFTWARE.ID,
            INSTANCE_SOFTWARE.NAME,
            INSTANCE_SOFTWARE.VERSION,
            INSTANCE_SOFTWARE.BUILD,
            INSTANCE_SOFTWARE.INSTALLED_AT,
            INSTANCE_SOFTWARE.INSTALLED_BY,
            USERS.FULL_NAME
        )
            .from(INSTANCE_SOFTWARE)
            .leftJoin(USERS).on(USERS.ID.eq(INSTANCE_SOFTWARE.INSTALLED_BY))
            .where(INSTANCE_SOFTWARE.INSTANCE_ID.eq(instanceId))
            .orderBy(INSTANCE_SOFTWARE.ID.asc())
            .fetch { r ->
                ProductInstanceSoftwareDto(
                    id = r[INSTANCE_SOFTWARE.ID],
                    name = r[INSTANCE_SOFTWARE.NAME],
                    version = r[INSTANCE_SOFTWARE.VERSION],
                    build = r[INSTANCE_SOFTWARE.BUILD],
                    installedAt = r[INSTANCE_SOFTWARE.INSTALLED_AT].toString(),
                    installedById = r[INSTANCE_SOFTWARE.INSTALLED_BY],
                    installedByName = r[USERS.FULL_NAME]
                )
            }

        val tests = dsl.select(
            INSTANCE_TESTS.ID,
            INSTANCE_TESTS.RESULT,
            INSTANCE_TESTS.NOTES,
            INSTANCE_TESTS.PROTOCOL_DOCUMENT_VERSION_ID,
            INSTANCE_TESTS.PERFORMED_AT,
            INSTANCE_TESTS.PERFORMED_BY,
            USERS.FULL_NAME,
            INSTANCE_TESTS.TECHNICAL_DECISION,
            INSTANCE_TESTS.TECHNICAL_DECISION_BY,
            INSTANCE_TESTS.TECHNICAL_DECISION_AT
        )
            .from(INSTANCE_TESTS)
            .leftJoin(USERS).on(USERS.ID.eq(INSTANCE_TESTS.PERFORMED_BY))
            .where(INSTANCE_TESTS.INSTANCE_ID.eq(instanceId))
            .orderBy(INSTANCE_TESTS.ID.asc())
            .fetch { r ->
                ProductInstanceTestDto(
                    id = r[INSTANCE_TESTS.ID],
                    result = r[INSTANCE_TESTS.RESULT],
                    notes = r[INSTANCE_TESTS.NOTES],
                    protocolDocumentVersionId = r[INSTANCE_TESTS.PROTOCOL_DOCUMENT_VERSION_ID],
                    performedAt = r[INSTANCE_TESTS.PERFORMED_AT].toString(),
                    performedById = r[INSTANCE_TESTS.PERFORMED_BY],
                    performedByName = r[USERS.FULL_NAME],
                    technicalDecision = r[INSTANCE_TESTS.TECHNICAL_DECISION],
                    technicalDecisionById = r[INSTANCE_TESTS.TECHNICAL_DECISION_BY],
                    technicalDecisionAt = r[INSTANCE_TESTS.TECHNICAL_DECISION_AT]?.toString()
                )
            }

        val remarks = dsl.select(
            INSTANCE_REMARKS.ID,
            INSTANCE_REMARKS.TEST_ID,
            INSTANCE_REMARKS.DESCRIPTION,
            INSTANCE_REMARKS.SEVERITY,
            INSTANCE_REMARKS.BLOCKS_SHIPMENT,
            INSTANCE_REMARKS.STATUS,
            INSTANCE_REMARKS.CREATED_BY,
            USERS.FULL_NAME,
            INSTANCE_REMARKS.ALLOWED_BY,
            INSTANCE_REMARKS.ALLOWED_AT,
            INSTANCE_REMARKS.CORRECTIVE_TASK_ID,
            INSTANCE_REMARKS.CREATED_AT
        )
            .from(INSTANCE_REMARKS)
            .leftJoin(USERS).on(USERS.ID.eq(INSTANCE_REMARKS.CREATED_BY))
            .where(INSTANCE_REMARKS.INSTANCE_ID.eq(instanceId))
            .orderBy(INSTANCE_REMARKS.ID.asc())
            .fetch { r ->
                ProductInstanceRemarkDto(
                    id = r[INSTANCE_REMARKS.ID],
                    testId = r[INSTANCE_REMARKS.TEST_ID],
                    description = r[INSTANCE_REMARKS.DESCRIPTION],
                    severity = r[INSTANCE_REMARKS.SEVERITY],
                    blocksShipment = r[INSTANCE_REMARKS.BLOCKS_SHIPMENT],
                    status = r[INSTANCE_REMARKS.STATUS],
                    createdById = r[INSTANCE_REMARKS.CREATED_BY],
                    createdByName = r[USERS.FULL_NAME],
                    allowedById = r[INSTANCE_REMARKS.ALLOWED_BY],
                    allowedAt = r[INSTANCE_REMARKS.ALLOWED_AT]?.toString(),
                    correctiveTaskId = r[INSTANCE_REMARKS.CORRECTIVE_TASK_ID],
                    createdAt = r[INSTANCE_REMARKS.CREATED_AT].toString()
                )
            }

        return ProductInstanceDetailDto(
            id = row[PRODUCT_INSTANCES.ID],
            orderId = row[PRODUCT_INSTANCES.ORDER_ID],
            orderNumber = row[PRODUCTION_ORDERS.NUMBER],
            projectId = row[PRODUCT_INSTANCES.PROJECT_ID],
            productModelId = row[PRODUCT_INSTANCES.PRODUCT_MODEL_ID],
            productModelCode = row[PRODUCT_MODELS.CODE],
            productModelName = row[PRODUCT_MODELS.NAME],
            bomRevisionId = row[PRODUCT_INSTANCES.BOM_REVISION_ID],
            serialNumber = row[PRODUCT_INSTANCES.SERIAL_NUMBER],
            status = row[PRODUCT_INSTANCES.STATUS],
            manufacturedAt = row[PRODUCT_INSTANCES.MANUFACTURED_AT]?.toString(),
            acceptedAt = row[PRODUCT_INSTANCES.ACCEPTED_AT]?.toString(),
            shippedAt = row[PRODUCT_INSTANCES.SHIPPED_AT]?.toString(),
            version = row[PRODUCT_INSTANCES.VERSION],
            components = components,
            documents = documents,
            software = software,
            tests = tests,
            remarks = remarks
        )
    }

    fun findInstanceOrderId(instanceId: Long): Long? =
        dsl.select(PRODUCT_INSTANCES.ORDER_ID)
            .from(PRODUCT_INSTANCES)
            .where(PRODUCT_INSTANCES.ID.eq(instanceId))
            .fetchOne(PRODUCT_INSTANCES.ORDER_ID)

    fun insertInstanceTest(
        instanceId: Long,
        orderId: Long?,
        result: String,
        notes: String?,
        protocolDocumentVersionId: Long?,
        technicalDecision: String,
        userId: Long
    ): Long =
        dsl.insertInto(INSTANCE_TESTS)
            .set(INSTANCE_TESTS.INSTANCE_ID, instanceId)
            .set(INSTANCE_TESTS.ORDER_ID, orderId)
            .set(INSTANCE_TESTS.PROTOCOL_DOCUMENT_VERSION_ID, protocolDocumentVersionId)
            .set(INSTANCE_TESTS.PERFORMED_BY, userId)
            .set(INSTANCE_TESTS.RESULT, result)
            .set(INSTANCE_TESTS.NOTES, notes)
            .set(INSTANCE_TESTS.TECHNICAL_DECISION, technicalDecision)
            .set(INSTANCE_TESTS.CREATED_BY, userId)
            .returning(INSTANCE_TESTS.ID)
            .fetchOne()!!
            .id

    fun insertInstanceRemark(
        instanceId: Long,
        testId: Long?,
        description: String,
        severity: String,
        blocksShipment: Boolean,
        userId: Long
    ): Long =
        dsl.insertInto(INSTANCE_REMARKS)
            .set(INSTANCE_REMARKS.INSTANCE_ID, instanceId)
            .set(INSTANCE_REMARKS.TEST_ID, testId)
            .set(INSTANCE_REMARKS.DESCRIPTION, description)
            .set(INSTANCE_REMARKS.SEVERITY, severity)
            .set(INSTANCE_REMARKS.BLOCKS_SHIPMENT, blocksShipment)
            .set(INSTANCE_REMARKS.CREATED_BY, userId)
            .returning(INSTANCE_REMARKS.ID)
            .fetchOne()!!
            .id

    fun insertInstanceSoftware(
        instanceId: Long,
        name: String,
        version: String,
        build: String?,
        userId: Long
    ): Long =
        dsl.insertInto(INSTANCE_SOFTWARE)
            .set(INSTANCE_SOFTWARE.INSTANCE_ID, instanceId)
            .set(INSTANCE_SOFTWARE.NAME, name)
            .set(INSTANCE_SOFTWARE.VERSION, version)
            .set(INSTANCE_SOFTWARE.BUILD, build)
            .set(INSTANCE_SOFTWARE.INSTALLED_BY, userId)
            .set(INSTANCE_SOFTWARE.CREATED_BY, userId)
            .returning(INSTANCE_SOFTWARE.ID)
            .fetchOne()!!
            .id

    fun resolveInstanceRemark(instanceId: Long, remarkId: Long, status: String, userId: Long): Int =
        dsl.update(INSTANCE_REMARKS)
            .set(INSTANCE_REMARKS.STATUS, status)
            .set(INSTANCE_REMARKS.ALLOWED_BY, userId)
            .set(INSTANCE_REMARKS.ALLOWED_AT, OffsetDateTime.now())
            .set(INSTANCE_REMARKS.VERSION, INSTANCE_REMARKS.VERSION.plus(1L))
            .where(
                INSTANCE_REMARKS.ID.eq(remarkId)
                    .and(INSTANCE_REMARKS.INSTANCE_ID.eq(instanceId))
                    .and(INSTANCE_REMARKS.STATUS.eq("OPEN"))
            )
            .execute()

    fun hasBlockingOpenRemarks(instanceId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(INSTANCE_REMARKS)
                .where(
                    INSTANCE_REMARKS.INSTANCE_ID.eq(instanceId)
                        .and(INSTANCE_REMARKS.BLOCKS_SHIPMENT.isTrue)
                        .and(INSTANCE_REMARKS.STATUS.eq("OPEN"))
                )
        )

    fun hasUnresolvedFailedTests(instanceId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(INSTANCE_TESTS)
                .where(
                    INSTANCE_TESTS.INSTANCE_ID.eq(instanceId)
                        .and(INSTANCE_TESTS.RESULT.eq("NOT_GOOD"))
                        .and(INSTANCE_TESTS.TECHNICAL_DECISION.ne("GOOD"))
                )
        )

    fun updateInstanceStatus(
        instanceId: Long,
        newStatus: String,
        acceptedAt: OffsetDateTime? = null,
        shippedAt: OffsetDateTime? = null
    ): Int {
        var step = dsl.update(PRODUCT_INSTANCES)
            .set(PRODUCT_INSTANCES.STATUS, newStatus)
            .set(PRODUCT_INSTANCES.VERSION, PRODUCT_INSTANCES.VERSION.plus(1L))
        if (acceptedAt != null) {
            step = step.set(PRODUCT_INSTANCES.ACCEPTED_AT, acceptedAt)
        }
        if (shippedAt != null) {
            step = step.set(PRODUCT_INSTANCES.SHIPPED_AT, shippedAt)
        }
        return step.where(PRODUCT_INSTANCES.ID.eq(instanceId)).execute()
    }
}
