package org.example.project.service

import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.generated.jooq.tables.Documents.DOCUMENTS
import org.example.project.generated.jooq.tables.ProductionOrders.PRODUCTION_ORDERS
import org.example.project.generated.jooq.tables.PurchaseOrders.PURCHASE_ORDERS
import org.example.project.model.CurrentUser
import org.jooq.DSLContext
import java.time.OffsetDateTime

const val APPROVAL_TARGET_PURCHASE_ORDER = "PURCHASE_ORDER"
const val APPROVAL_TARGET_DOCUMENT = "DOCUMENT"
const val APPROVAL_TARGET_PRODUCTION_ORDER = "PRODUCTION_ORDER"

class PurchaseOrderApprovalTarget(
    private val dsl: DSLContext,
    private val auditService: AuditService
) : ApprovalTargetPort {
    override val targetType: String = APPROVAL_TARGET_PURCHASE_ORDER

    override fun ensureStartAllowed(targetId: Long, currentUser: CurrentUser): Long {
        currentUser.requirePermission("purchase_orders", "create")
        val row = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(targetId)).fetchOne()
            ?: throw NotFoundException("purchase_order", targetId)
        return row.version ?: 0L
    }

    override fun ensureReadAllowed(targetId: Long, currentUser: CurrentUser) {
        currentUser.requirePermission("purchase_orders", "read")
        dsl.select(PURCHASE_ORDERS.ID).from(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(targetId)).fetchOne()
            ?: throw NotFoundException("purchase_order", targetId)
    }

    override fun currentTargetVersion(targetId: Long): Long =
        dsl.select(PURCHASE_ORDERS.VERSION).from(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(targetId))
            .fetchOne(PURCHASE_ORDERS.VERSION)
            ?: throw NotFoundException("purchase_order", targetId)

    override fun completeApproval(targetId: Long, expectedVersion: Long, decidedBy: Long) {
        val updated = dsl.update(PURCHASE_ORDERS)
            .set(PURCHASE_ORDERS.STATUS, "APPROVED")
            .set(PURCHASE_ORDERS.UPDATED_AT, OffsetDateTime.now())
            .set(PURCHASE_ORDERS.VERSION, PURCHASE_ORDERS.VERSION.plus(1))
            .where(PURCHASE_ORDERS.ID.eq(targetId))
            .and(PURCHASE_ORDERS.VERSION.eq(expectedVersion))
            .execute()
        if (updated == 0) throw ConflictException("Purchase order version conflict on approval")
        auditService.log(decidedBy, "PO_APPROVAL_COMPLETED", "PURCHASE_ORDER", targetId)
    }
}

class DocumentApprovalTarget(
    private val dsl: DSLContext,
    private val auditService: AuditService
) : ApprovalTargetPort {
    override val targetType: String = APPROVAL_TARGET_DOCUMENT

    override fun ensureStartAllowed(targetId: Long, currentUser: CurrentUser): Long {
        currentUser.requirePermission("documents", "read")
        val row = dsl.selectFrom(DOCUMENTS).where(DOCUMENTS.ID.eq(targetId)).fetchOne()
            ?: throw NotFoundException("document", targetId)
        return row.version ?: 0L
    }

    override fun ensureReadAllowed(targetId: Long, currentUser: CurrentUser) {
        currentUser.requirePermission("documents", "read")
        dsl.select(DOCUMENTS.ID).from(DOCUMENTS).where(DOCUMENTS.ID.eq(targetId)).fetchOne()
            ?: throw NotFoundException("document", targetId)
    }

    override fun currentTargetVersion(targetId: Long): Long =
        dsl.select(DOCUMENTS.VERSION).from(DOCUMENTS).where(DOCUMENTS.ID.eq(targetId))
            .fetchOne(DOCUMENTS.VERSION)
            ?: throw NotFoundException("document", targetId)

    override fun completeApproval(targetId: Long, expectedVersion: Long, decidedBy: Long) {
        val updated = dsl.update(DOCUMENTS)
            .set(DOCUMENTS.UPDATED_AT, OffsetDateTime.now())
            .set(DOCUMENTS.VERSION, DOCUMENTS.VERSION.plus(1))
            .where(DOCUMENTS.ID.eq(targetId))
            .and(DOCUMENTS.VERSION.eq(expectedVersion))
            .execute()
        if (updated == 0) throw ConflictException("Document version conflict on approval")
        auditService.log(decidedBy, "DOC_APPROVAL_COMPLETED", "DOCUMENT", targetId)
    }
}

class ProductionOrderApprovalTarget(
    private val dsl: DSLContext,
    private val auditService: AuditService
) : ApprovalTargetPort {
    override val targetType: String = APPROVAL_TARGET_PRODUCTION_ORDER

    override fun ensureStartAllowed(targetId: Long, currentUser: CurrentUser): Long {
        currentUser.requirePermission("production", "manage")
        val row = dsl.selectFrom(PRODUCTION_ORDERS).where(PRODUCTION_ORDERS.ID.eq(targetId)).fetchOne()
            ?: throw NotFoundException("production_order", targetId)
        return row.version ?: 0L
    }

    override fun ensureReadAllowed(targetId: Long, currentUser: CurrentUser) {
        currentUser.requirePermission("production", "read")
        dsl.select(PRODUCTION_ORDERS.ID).from(PRODUCTION_ORDERS).where(PRODUCTION_ORDERS.ID.eq(targetId)).fetchOne()
            ?: throw NotFoundException("production_order", targetId)
    }

    override fun currentTargetVersion(targetId: Long): Long =
        dsl.select(PRODUCTION_ORDERS.VERSION).from(PRODUCTION_ORDERS).where(PRODUCTION_ORDERS.ID.eq(targetId))
            .fetchOne(PRODUCTION_ORDERS.VERSION)
            ?: throw NotFoundException("production_order", targetId)

    override fun completeApproval(targetId: Long, expectedVersion: Long, decidedBy: Long) {
        val updated = dsl.update(PRODUCTION_ORDERS)
            .set(PRODUCTION_ORDERS.STATUS, "PLANNED")
            .set(PRODUCTION_ORDERS.UPDATED_AT, OffsetDateTime.now())
            .set(PRODUCTION_ORDERS.VERSION, PRODUCTION_ORDERS.VERSION.plus(1))
            .where(PRODUCTION_ORDERS.ID.eq(targetId))
            .and(PRODUCTION_ORDERS.VERSION.eq(expectedVersion))
            .execute()
        if (updated == 0) throw ConflictException("Production order version conflict on approval")
        auditService.log(decidedBy, "PROD_ORDER_APPROVAL_COMPLETED", "PRODUCTION_ORDER", targetId)
    }
}
