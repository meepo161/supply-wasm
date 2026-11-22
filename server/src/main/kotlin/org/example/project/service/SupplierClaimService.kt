package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.PurchaseOrders.PURCHASE_ORDERS
import org.example.project.generated.jooq.tables.SupplierClaims.SUPPLIER_CLAIMS
import org.example.project.generated.jooq.tables.Suppliers.SUPPLIERS
import org.example.project.model.CurrentUser
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.Year

class SupplierClaimService(
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    fun list(
        supplierId: Long?,
        purchaseOrderId: Long?,
        status: SupplierClaimStatus?,
        currentUser: CurrentUser
    ): List<SupplierClaimDto> {
        currentUser.requirePermission("suppliers", "read_any")
        var cond: org.jooq.Condition = DSL.trueCondition()
        if (supplierId != null) cond = cond.and(SUPPLIER_CLAIMS.SUPPLIER_ID.eq(supplierId))
        if (purchaseOrderId != null) cond = cond.and(SUPPLIER_CLAIMS.PURCHASE_ORDER_ID.eq(purchaseOrderId))
        if (status != null) cond = cond.and(SUPPLIER_CLAIMS.STATUS.eq(status.name))

        return dsl.select(
            SUPPLIER_CLAIMS.ID, SUPPLIER_CLAIMS.NUMBER, SUPPLIER_CLAIMS.SUPPLIER_ID,
            SUPPLIERS.NAME, SUPPLIER_CLAIMS.PURCHASE_ORDER_ID, PURCHASE_ORDERS.NUMBER,
            SUPPLIER_CLAIMS.PURCHASE_DELIVERY_ID, SUPPLIER_CLAIMS.RECEIPT_ID,
            SUPPLIER_CLAIMS.CLAIM_TYPE, SUPPLIER_CLAIMS.STATUS, SUPPLIER_CLAIMS.DESCRIPTION,
            SUPPLIER_CLAIMS.CLAIM_AMOUNT, SUPPLIER_CLAIMS.CURRENCY, SUPPLIER_CLAIMS.RESOLUTION,
            SUPPLIER_CLAIMS.CREATED_BY, SUPPLIER_CLAIMS.CREATED_AT,
            SUPPLIER_CLAIMS.RESOLVED_BY, SUPPLIER_CLAIMS.RESOLVED_AT, SUPPLIER_CLAIMS.VERSION
        )
            .from(SUPPLIER_CLAIMS)
            .join(SUPPLIERS).on(SUPPLIERS.ID.eq(SUPPLIER_CLAIMS.SUPPLIER_ID))
            .leftJoin(PURCHASE_ORDERS).on(PURCHASE_ORDERS.ID.eq(SUPPLIER_CLAIMS.PURCHASE_ORDER_ID))
            .where(cond)
            .orderBy(SUPPLIER_CLAIMS.ID.desc())
            .fetch { r ->
                SupplierClaimDto(
                    id = r.get(SUPPLIER_CLAIMS.ID)!!,
                    number = r.get(SUPPLIER_CLAIMS.NUMBER)!!,
                    supplierId = r.get(SUPPLIER_CLAIMS.SUPPLIER_ID)!!,
                    supplierName = r.get(SUPPLIERS.NAME)!!,
                    purchaseOrderId = r.get(SUPPLIER_CLAIMS.PURCHASE_ORDER_ID),
                    purchaseOrderNumber = r.get(PURCHASE_ORDERS.NUMBER),
                    purchaseDeliveryId = r.get(SUPPLIER_CLAIMS.PURCHASE_DELIVERY_ID),
                    receiptId = r.get(SUPPLIER_CLAIMS.RECEIPT_ID),
                    claimType = SupplierClaimType.valueOf(r.get(SUPPLIER_CLAIMS.CLAIM_TYPE)!!),
                    status = SupplierClaimStatus.valueOf(r.get(SUPPLIER_CLAIMS.STATUS)!!),
                    description = r.get(SUPPLIER_CLAIMS.DESCRIPTION)!!,
                    claimAmount = r.get(SUPPLIER_CLAIMS.CLAIM_AMOUNT)!!.stripTrailingZeros().toPlainString(),
                    currency = r.get(SUPPLIER_CLAIMS.CURRENCY)!!,
                    resolution = r.get(SUPPLIER_CLAIMS.RESOLUTION),
                    createdBy = r.get(SUPPLIER_CLAIMS.CREATED_BY)!!,
                    createdAt = r.get(SUPPLIER_CLAIMS.CREATED_AT)?.toString(),
                    resolvedBy = r.get(SUPPLIER_CLAIMS.RESOLVED_BY),
                    resolvedAt = r.get(SUPPLIER_CLAIMS.RESOLVED_AT)?.toString(),
                    version = r.get(SUPPLIER_CLAIMS.VERSION) ?: 0
                )
            }
    }

    fun get(id: Long, currentUser: CurrentUser): SupplierClaimDto {
        currentUser.requirePermission("suppliers", "read_any")
        return list(null, null, null, currentUser).find { it.id == id }
            ?: throw NotFoundException("supplier_claim", id)
    }

    fun create(request: CreateSupplierClaimRequest, currentUser: CurrentUser): SupplierClaimDto {
        currentUser.requirePermission("suppliers", "manage")
        if (request.description.isBlank()) {
            throw ValidationException("Claim description is required", mapOf("description" to "required"))
        }
        val amount = request.claimAmount.trim().toBigDecimalOrNull()
            ?: throw ValidationException("Invalid claimAmount", mapOf("claimAmount" to "invalid"))
        if (amount < BigDecimal.ZERO) {
            throw ValidationException("claimAmount must be >= 0", mapOf("claimAmount" to "negative"))
        }
        if (!dsl.fetchExists(dsl.selectOne().from(SUPPLIERS).where(SUPPLIERS.ID.eq(request.supplierId)))) {
            throw NotFoundException("supplier", request.supplierId)
        }
        val currency = request.currency.trim().ifEmpty { "RUB" }.uppercase()
        val id = dsl.transactionResult(TransactionalCallable {
            dsl.execute("SELECT pg_advisory_xact_lock(720017)")
            val year = Year.now().value
            val prefix = "CLM-$year-"
            val count = dsl.fetchCount(
                dsl.selectFrom(SUPPLIER_CLAIMS).where(SUPPLIER_CLAIMS.NUMBER.like("$prefix%"))
            ) + 1
            val number = "$prefix${count.toString().padStart(5, '0')}"
            val rec = dsl.insertInto(SUPPLIER_CLAIMS)
                .set(SUPPLIER_CLAIMS.NUMBER, number)
                .set(SUPPLIER_CLAIMS.SUPPLIER_ID, request.supplierId)
                .set(SUPPLIER_CLAIMS.PURCHASE_ORDER_ID, request.purchaseOrderId)
                .set(SUPPLIER_CLAIMS.PURCHASE_DELIVERY_ID, request.purchaseDeliveryId)
                .set(SUPPLIER_CLAIMS.RECEIPT_ID, request.receiptId)
                .set(SUPPLIER_CLAIMS.CLAIM_TYPE, request.claimType.name)
                .set(SUPPLIER_CLAIMS.STATUS, SupplierClaimStatus.OPEN.name)
                .set(SUPPLIER_CLAIMS.DESCRIPTION, request.description.trim())
                .set(SUPPLIER_CLAIMS.CLAIM_AMOUNT, amount)
                .set(SUPPLIER_CLAIMS.CURRENCY, currency)
                .set(SUPPLIER_CLAIMS.CREATED_BY, currentUser.userId)
                .returning(SUPPLIER_CLAIMS.ID)
                .fetchOne()!!
            auditService.log(
                currentUser.userId,
                "SUPPLIER_CLAIM_CREATE",
                "SUPPLIER_CLAIM",
                rec.id,
                mapOf("number" to number, "supplierId" to request.supplierId)
            )
            rec.id
        })
        return get(id, currentUser)
    }

    fun transition(id: Long, request: TransitionSupplierClaimRequest, currentUser: CurrentUser): SupplierClaimDto {
        currentUser.requirePermission("suppliers", "manage")
        val existing = dsl.selectFrom(SUPPLIER_CLAIMS).where(SUPPLIER_CLAIMS.ID.eq(id)).fetchOne()
            ?: throw NotFoundException("supplier_claim", id)
        if ((existing.version ?: 0L) != request.version) {
            throw ConflictException("Supplier claim version conflict")
        }
        val current = SupplierClaimStatus.valueOf(existing.status)
        if (current in setOf(SupplierClaimStatus.CLOSED, SupplierClaimStatus.CANCELLED)) {
            throw ConflictException("Closed or cancelled claim cannot be transitioned")
        }
        if (request.targetStatus in setOf(SupplierClaimStatus.RESOLVED, SupplierClaimStatus.CLOSED) &&
            request.resolution.isNullOrBlank() && existing.resolution.isNullOrBlank()
        ) {
            throw ValidationException("Resolution is required", mapOf("resolution" to "required"))
        }
        val resolvedNow = request.targetStatus in setOf(SupplierClaimStatus.RESOLVED, SupplierClaimStatus.CLOSED)
        dsl.transaction { _ ->
            val updated = dsl.update(SUPPLIER_CLAIMS)
                .set(SUPPLIER_CLAIMS.STATUS, request.targetStatus.name)
                .set(SUPPLIER_CLAIMS.RESOLUTION, request.resolution?.trim() ?: existing.resolution)
                .set(SUPPLIER_CLAIMS.RESOLVED_BY, if (resolvedNow) currentUser.userId else existing.resolvedBy)
                .set(SUPPLIER_CLAIMS.RESOLVED_AT, if (resolvedNow) OffsetDateTime.now() else existing.resolvedAt)
                .set(SUPPLIER_CLAIMS.VERSION, SUPPLIER_CLAIMS.VERSION.plus(1))
                .where(SUPPLIER_CLAIMS.ID.eq(id))
                .and(SUPPLIER_CLAIMS.VERSION.eq(request.version))
                .execute()
            if (updated == 0) throw ConflictException("Supplier claim version conflict")
            auditService.log(
                currentUser.userId,
                "SUPPLIER_CLAIM_TRANSITION",
                "SUPPLIER_CLAIM",
                id,
                mapOf("from" to current.name, "to" to request.targetStatus.name)
            )
        }
        return get(id, currentUser)
    }
}
