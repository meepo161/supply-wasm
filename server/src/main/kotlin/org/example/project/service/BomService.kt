package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.repository.BomRepository
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

private val HUNDRED = BigDecimal("100")

class BomService(
    private val bomRepository: BomRepository,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    private val calculator = BomExplosionCalculator(bomRepository)

    // --- Product models ---

    fun createProductModel(request: CreateProductModelRequest, currentUser: CurrentUser): ProductModelDto {
        currentUser.requirePermission("bom", "create")
        if (request.code.isBlank()) {
            throw ValidationException("Product model code is required", mapOf("code" to "required"))
        }
        if (request.name.isBlank()) {
            throw ValidationException("Product model name is required", mapOf("name" to "required"))
        }
        val code = request.code.trim()
        if (bomRepository.existsProductModelByCode(code)) {
            throw ConflictException("Product model code already exists: $code")
        }
        request.designerId?.let { ensureUserExists(it, "designerId") }
        request.technologistId?.let { ensureUserExists(it, "technologistId") }
        val status = (request.status ?: ProductModelStatus.DEVELOPMENT).name

        return dsl.transactionResult(TransactionalCallable {
            val model = bomRepository.createProductModel(
                code = code,
                name = request.name.trim(),
                description = request.description?.trim(),
                status = status,
                designerId = request.designerId,
                technologistId = request.technologistId,
                createdBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId, "CREATE", "PRODUCT_MODEL", model.id,
                mapOf("code" to model.code, "name" to model.name)
            )
            model.toDto()
        })
    }

    fun getProductModel(id: Long, currentUser: CurrentUser): ProductModelDto {
        currentUser.requirePermission("bom", "read_any")
        return bomRepository.findProductModelById(id)?.toDto()
            ?: throw NotFoundException("product_model", id)
    }

    fun listProductModels(currentUser: CurrentUser): List<ProductModelDto> {
        currentUser.requirePermission("bom", "read_any")
        return bomRepository.listProductModels().map { it.toDto() }
    }

    fun updateProductModel(
        id: Long,
        request: UpdateProductModelRequest,
        currentUser: CurrentUser
    ): ProductModelDto {
        currentUser.requirePermission("bom", "update")
        val existing = bomRepository.findProductModelById(id)
            ?: throw NotFoundException("product_model", id)
        if (existing.version != request.version) {
            throw ConflictException("Product model was modified concurrently")
        }
        request.name?.let { if (it.isBlank()) throw ValidationException("Name must not be blank", mapOf("name" to "blank")) }
        request.designerId?.let { ensureUserExists(it, "designerId") }
        request.technologistId?.let { ensureUserExists(it, "technologistId") }
        val status = request.status?.name

        return dsl.transactionResult(TransactionalCallable {
            val updated = bomRepository.updateProductModel(
                id = id,
                expectedVersion = request.version,
                name = request.name?.trim(),
                description = request.description?.trim(),
                status = status,
                designerId = request.designerId,
                technologistId = request.technologistId
            ) ?: throw ConflictException("Product model was modified concurrently")
            auditService.log(
                currentUser.userId, "UPDATE", "PRODUCT_MODEL", id,
                mapOf("code" to updated.code)
            )
            updated.toDto()
        })
    }

    // --- Revisions ---

    fun createRevision(
        modelId: Long,
        request: CreateBomRevisionRequest,
        currentUser: CurrentUser
    ): BomRevisionDto {
        currentUser.requirePermission("bom", "create")
        if (bomRepository.findProductModelById(modelId) == null) {
            throw NotFoundException("product_model", modelId)
        }
        if (request.revisionNumber.isBlank()) {
            throw ValidationException("Revision number is required", mapOf("revisionNumber" to "required"))
        }
        val number = request.revisionNumber.trim()
        if (bomRepository.findRevisionByModelAndNumber(modelId, number) != null) {
            throw ConflictException("Revision number already exists for this model: $number")
        }
        return dsl.transactionResult(TransactionalCallable {
            val revision = bomRepository.createRevision(
                modelId = modelId,
                revisionNumber = number,
                changeReason = request.changeReason?.trim(),
                createdBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId, "CREATE", "BOM_REVISION", revision.id,
                mapOf("productModelId" to modelId, "revisionNumber" to number)
            )
            revision.toDto()
        })
    }

    fun copyRevision(
        sourceRevisionId: Long,
        request: CopyBomRevisionRequest,
        currentUser: CurrentUser
    ): BomRevisionDto {
        currentUser.requirePermission("bom", "create")
        if (request.revisionNumber.isBlank()) {
            throw ValidationException("Revision number is required", mapOf("revisionNumber" to "required"))
        }
        if (request.reason.isBlank()) {
            throw ValidationException("Change reason is required", mapOf("reason" to "required"))
        }
        val source = bomRepository.findRevisionById(sourceRevisionId)
            ?: throw NotFoundException("bom_revision", sourceRevisionId)
        val number = request.revisionNumber.trim()
        if (bomRepository.findRevisionByModelAndNumber(source.productModelId, number) != null) {
            throw ConflictException("Revision number already exists for this model: $number")
        }
        return dsl.transactionResult(TransactionalCallable {
            val copy = bomRepository.copyRevision(
                sourceRevisionId = sourceRevisionId,
                modelId = source.productModelId,
                newRevisionNumber = number,
                reason = request.reason.trim(),
                createdBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId, "COPY", "BOM_REVISION", copy.id,
                mapOf(
                    "sourceRevisionId" to sourceRevisionId,
                    "sourceRevisionNumber" to source.revisionNumber,
                    "newRevisionNumber" to number,
                    "reason" to request.reason.trim()
                )
            )
            copy.toDto()
        })
    }

    fun getRevision(id: Long, currentUser: CurrentUser): BomRevisionDto {
        currentUser.requirePermission("bom", "read_any")
        return bomRepository.findRevisionById(id)?.toDto()
            ?: throw NotFoundException("bom_revision", id)
    }

    fun listRevisions(modelId: Long, currentUser: CurrentUser): List<BomRevisionSummaryDto> {
        currentUser.requirePermission("bom", "read_any")
        if (bomRepository.findProductModelById(modelId) == null) {
            throw NotFoundException("product_model", modelId)
        }
        return bomRepository.listRevisionsByModel(modelId).map { it.toSummaryDto() }
    }

    fun approveRevision(
        id: Long,
        request: ApproveBomRevisionRequest,
        currentUser: CurrentUser
    ): BomRevisionDto {
        currentUser.requirePermission("bom", "approve")
        return dsl.transactionResult(TransactionalCallable {
            val revision = bomRepository.findRevisionById(id)
                ?: throw NotFoundException("bom_revision", id)
            if (revision.version != request.version) {
                throw ConflictException("BOM revision was modified concurrently")
            }
            if (revision.status != "DRAFT") {
                throw ConflictException("Only DRAFT revision can be approved")
            }
            validateRevisionLinesForApproval(id)
            val approved = bomRepository.updateRevisionStatus(
                id = id,
                expectedVersion = request.version,
                fromStatus = "DRAFT",
                newStatus = "APPROVED",
                approvedBy = currentUser.userId,
                approvedAt = OffsetDateTime.now()
            ) ?: throw ConflictException("BOM revision was modified concurrently")
            auditService.log(
                currentUser.userId, "APPROVE", "BOM_REVISION", id,
                mapOf("revisionNumber" to approved.revisionNumber, "status" to "APPROVED")
            )
            approved.toDto()
        })
    }

    fun activateRevision(
        id: Long,
        request: TransitionBomRevisionRequest,
        currentUser: CurrentUser
    ): BomRevisionDto {
        currentUser.requirePermission("bom", "approve")
        return dsl.transactionResult(TransactionalCallable {
            val revision = bomRepository.findRevisionById(id)
                ?: throw NotFoundException("bom_revision", id)
            if (revision.version != request.version) {
                throw ConflictException("BOM revision was modified concurrently")
            }
            if (revision.status != "APPROVED") {
                throw ConflictException("Only APPROVED revision can be activated")
            }
            bomRepository.setActiveRevisionReplacing(
                modelId = revision.productModelId,
                activeRevisionId = id
            )
            val activated = bomRepository.updateRevisionStatus(
                id = id,
                expectedVersion = request.version,
                fromStatus = "APPROVED",
                newStatus = "ACTIVE"
            ) ?: throw ConflictException("BOM revision was modified concurrently")
            auditService.log(
                currentUser.userId, "ACTIVATE", "BOM_REVISION", id,
                mapOf("revisionNumber" to activated.revisionNumber, "status" to "ACTIVE")
            )
            activated.toDto()
        })
    }

    fun replaceRevision(
        id: Long,
        request: TransitionBomRevisionRequest,
        currentUser: CurrentUser
    ): BomRevisionDto {
        currentUser.requirePermission("bom", "approve")
        return dsl.transactionResult(TransactionalCallable {
            val revision = bomRepository.findRevisionById(id)
                ?: throw NotFoundException("bom_revision", id)
            if (revision.version != request.version) {
                throw ConflictException("BOM revision was modified concurrently")
            }
            if (revision.status != "ACTIVE") {
                throw ConflictException("Only ACTIVE revision can be replaced")
            }
            val replaced = bomRepository.updateRevisionStatus(
                id = id,
                expectedVersion = request.version,
                fromStatus = "ACTIVE",
                newStatus = "REPLACED"
            ) ?: throw ConflictException("BOM revision was modified concurrently")
            auditService.log(
                currentUser.userId, "REPLACE", "BOM_REVISION", id,
                mapOf("revisionNumber" to replaced.revisionNumber, "status" to "REPLACED")
            )
            replaced.toDto()
        })
    }

    fun cancelRevision(
        id: Long,
        request: TransitionBomRevisionRequest,
        currentUser: CurrentUser
    ): BomRevisionDto {
        currentUser.requirePermission("bom", "delete_draft")
        return dsl.transactionResult(TransactionalCallable {
            val revision = bomRepository.findRevisionById(id)
                ?: throw NotFoundException("bom_revision", id)
            if (revision.version != request.version) {
                throw ConflictException("BOM revision was modified concurrently")
            }
            if (revision.status != "DRAFT") {
                throw ConflictException("Only DRAFT revision can be cancelled")
            }
            val cancelled = bomRepository.updateRevisionStatus(
                id = id,
                expectedVersion = request.version,
                fromStatus = "DRAFT",
                newStatus = "CANCELLED"
            ) ?: throw ConflictException("BOM revision was modified concurrently")
            auditService.log(
                currentUser.userId, "CANCEL", "BOM_REVISION", id,
                mapOf("revisionNumber" to cancelled.revisionNumber, "status" to "CANCELLED")
            )
            cancelled.toDto()
        })
    }

    // --- BOM lines ---

    fun createBomLine(
        revisionId: Long,
        request: CreateBomLineRequest,
        currentUser: CurrentUser
    ): BomLineDto {
        currentUser.requirePermission("bom", "update")
        val revision = bomRepository.findRevisionById(revisionId)
            ?: throw NotFoundException("bom_revision", revisionId)
        if (revision.status != "DRAFT") {
            throw ConflictException("BOM lines can only be edited in DRAFT revision")
        }
        validateBomLineRequest(revisionId, request)
        if (request.unit.isBlank()) {
            throw ValidationException("Unit is required", mapOf("unit" to "required"))
        }
        val quantity = parseQuantity(request.quantity, "quantity")
        val lossPct = request.lossPct?.let { parseLossPct(it) } ?: BigDecimal.ZERO
        val consumptionRate = request.consumptionRate?.let { parseConsumptionRate(it) }
        val level = resolveLevel(revisionId, request.parentLineId, request.level)

        return dsl.transactionResult(TransactionalCallable {
            val line = bomRepository.createBomLine(
                revisionId = revisionId,
                parentLineId = request.parentLineId,
                parentNomenclatureId = request.parentNomenclatureId,
                childNomenclatureId = request.childNomenclatureId,
                quantity = quantity,
                unit = request.unit.trim(),
                position = request.position?.trim(),
                level = level,
                mandatory = request.mandatory ?: true,
                substituteOfLineId = request.substituteOfLineId,
                consumptionRate = consumptionRate,
                lossPct = lossPct,
                createdBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId, "CREATE", "BOM_LINE", line.id,
                mapOf("revisionId" to revisionId, "childNomenclatureId" to request.childNomenclatureId)
            )
            line.toDto()
        })
    }

    fun updateBomLine(
        lineId: Long,
        request: UpdateBomLineRequest,
        currentUser: CurrentUser
    ): BomLineDto {
        currentUser.requirePermission("bom", "update")
        val line = bomRepository.findBomLineById(lineId)
            ?: throw NotFoundException("bom_line", lineId)
        val revision = bomRepository.findRevisionById(line.revisionId)
            ?: throw NotFoundException("bom_revision", line.revisionId)
        if (revision.status != "DRAFT") {
            throw ConflictException("BOM lines can only be edited in DRAFT revision")
        }
        request.unit?.let { if (it.isBlank()) throw ValidationException("Unit must not be blank", mapOf("unit" to "blank")) }
        if (request.quantity == null && request.unit == null && request.position == null &&
            request.level == null && request.mandatory == null &&
            request.consumptionRate == null && request.lossPct == null
        ) {
            throw ValidationException("No fields to update", mapOf("request" to "empty"))
        }
        val quantity = request.quantity?.let { parseQuantity(it, "quantity") }
        val lossPct = request.lossPct?.let { parseLossPct(it) }
        val consumptionRate = request.consumptionRate?.let { parseConsumptionRate(it) }
        if (lossPct != null && lossPct.compareTo(HUNDRED) == 0) {
            throw ValidationException("loss_pct cannot be 100%", mapOf("lossPct" to "forbidden_100"))
        }

        return dsl.transactionResult(TransactionalCallable {
            val updated = bomRepository.updateBomLine(
                id = lineId,
                quantity = quantity,
                unit = request.unit?.trim(),
                position = request.position?.trim(),
                level = request.level?.let { resolveLevel(line.revisionId, line.parentLineId, it) },
                mandatory = request.mandatory,
                consumptionRate = consumptionRate,
                lossPct = lossPct
            ) ?: throw NotFoundException("bom_line", lineId)
            auditService.log(
                currentUser.userId, "UPDATE", "BOM_LINE", lineId,
                mapOf("revisionId" to line.revisionId)
            )
            updated.toDto()
        })
    }

    fun deleteBomLine(lineId: Long, currentUser: CurrentUser) {
        currentUser.requirePermission("bom", "update")
        val line = bomRepository.findBomLineById(lineId)
            ?: throw NotFoundException("bom_line", lineId)
        val revision = bomRepository.findRevisionById(line.revisionId)
            ?: throw NotFoundException("bom_revision", line.revisionId)
        if (revision.status != "DRAFT") {
            throw ConflictException("BOM lines can only be deleted in DRAFT revision")
        }
        dsl.transaction { _ ->
            if (!bomRepository.deleteBomLine(lineId)) {
                throw NotFoundException("bom_line", lineId)
            }
            auditService.log(
                currentUser.userId, "DELETE", "BOM_LINE", lineId,
                mapOf("revisionId" to line.revisionId)
            )
        }
    }

    fun listBomLines(revisionId: Long, currentUser: CurrentUser): List<BomLineDto> {
        currentUser.requirePermission("bom", "read_any")
        if (bomRepository.findRevisionById(revisionId) == null) {
            throw NotFoundException("bom_revision", revisionId)
        }
        return bomRepository.listBomLinesByRevision(revisionId).map { it.toDto() }
    }

    fun diffRevisions(leftId: Long, rightId: Long, currentUser: CurrentUser): BomRevisionDiffDto {
        currentUser.requirePermission("bom", "read_any")
        val leftRev = bomRepository.findRevisionById(leftId) ?: throw NotFoundException("bom_revision", leftId)
        val rightRev = bomRepository.findRevisionById(rightId) ?: throw NotFoundException("bom_revision", rightId)
        val leftLines = bomRepository.listBomLinesByRevision(leftId).associateBy { it.childNomenclatureId }
        val rightLines = bomRepository.listBomLinesByRevision(rightId).associateBy { it.childNomenclatureId }
        val allIds = (leftLines.keys + rightLines.keys).distinct()
        val names = bomRepository.loadNomenclatureNames(allIds.toSet())
        val items = allIds.map { nomId ->
            val l = leftLines[nomId]
            val r = rightLines[nomId]
            val info = names[nomId] ?: ("?" to "?")
            val lQty = l?.quantity?.stripTrailingZeros()?.toPlainString()
            val rQty = r?.quantity?.stripTrailingZeros()?.toPlainString()
            val kind = when {
                l == null -> BomDiffKind.ADDED
                r == null -> BomDiffKind.REMOVED
                lQty != rQty || l.position != r.position -> BomDiffKind.CHANGED
                else -> BomDiffKind.UNCHANGED
            }
            BomRevisionDiffLineDto(
                kind = kind,
                childNomenclatureId = nomId,
                code = info.first,
                name = info.second,
                unit = (r ?: l)!!.unit,
                leftQuantity = lQty,
                rightQuantity = rQty,
                leftPosition = l?.position,
                rightPosition = r?.position
            )
        }
        return BomRevisionDiffDto(
            leftRevisionId = leftRev.id,
            leftRevisionNumber = leftRev.revisionNumber,
            rightRevisionId = rightRev.id,
            rightRevisionNumber = rightRev.revisionNumber,
            items = items
        )
    }

    fun whereUsed(nomenclatureId: Long, currentUser: CurrentUser): List<BomWhereUsedItemDto> {
        currentUser.requirePermission("bom", "read_any")
        val bl = org.example.project.generated.jooq.tables.BomLines.BOM_LINES
        val pr = org.example.project.generated.jooq.tables.ProductRevisions.PRODUCT_REVISIONS
        val pm = org.example.project.generated.jooq.tables.ProductModels.PRODUCT_MODELS
        return dsl.select(
            pm.ID, pm.CODE, pm.NAME,
            pr.ID, pr.REVISION_NUMBER, pr.STATUS,
            bl.ID, bl.QUANTITY, bl.UNIT, bl.LEVEL, bl.POSITION
        )
            .from(bl)
            .join(pr).on(pr.ID.eq(bl.REVISION_ID))
            .join(pm).on(pm.ID.eq(pr.PRODUCT_MODEL_ID))
            .where(bl.CHILD_NOMENCLATURE_ID.eq(nomenclatureId))
            .orderBy(pm.CODE.asc(), pr.ID.desc())
            .fetch { row ->
                BomWhereUsedItemDto(
                    productModelId = row.get(pm.ID)!!,
                    modelCode = row.get(pm.CODE)!!,
                    modelName = row.get(pm.NAME)!!,
                    revisionId = row.get(pr.ID)!!,
                    revisionNumber = row.get(pr.REVISION_NUMBER)!!,
                    revisionStatus = BomRevisionStatus.valueOf(row.get(pr.STATUS)!!),
                    lineId = row.get(bl.ID)!!,
                    quantity = row.get(bl.QUANTITY)!!.stripTrailingZeros().toPlainString(),
                    unit = row.get(bl.UNIT)!!,
                    level = row.get(bl.LEVEL)!!,
                    position = row.get(bl.POSITION)
                )
            }
    }

    // --- Explosion ---

    fun explodeRevision(
        revisionId: Long,
        request: BomExplodeRequest,
        currentUser: CurrentUser
    ): BomExplodeReportDto {
        currentUser.requirePermission("bom", "explode")
        val revision = bomRepository.findRevisionById(revisionId)
            ?: throw NotFoundException("bom_revision", revisionId)
        if (revision.status != "APPROVED" && revision.status != "ACTIVE") {
            throw ConflictException("Explosion is allowed only for APPROVED or ACTIVE revisions")
        }
        val releaseQuantity = parseQuantity(request.releaseQuantity, "releaseQuantity")
        val model = bomRepository.findProductModelById(revision.productModelId)
            ?: throw NotFoundException("product_model", revision.productModelId)

        val explosion = calculator.explode(revisionId, releaseQuantity)
        val totals = explosion.totalRequired
        val names = bomRepository.loadNomenclatureNames(totals.keys)

        val explodeLines = explosion.lines.map { line ->
            val info = names[line.nomenclatureId] ?: ("?" to "?")
            BomExplodeLineDto(
                id = line.lineId,
                lineId = line.lineId,
                parentLineId = line.parentLineId,
                level = line.level,
                nomenclatureId = line.nomenclatureId,
                code = info.first,
                name = info.second,
                unit = line.unit,
                quantityPerParent = line.quantityPerParent.toPlainString(),
                parentQuantity = line.parentQuantity.toPlainString(),
                requiredQuantity = line.requiredQuantity.toPlainString(),
                consumptionRate = line.consumptionRate?.toPlainString(),
                lossPct = line.lossPct.toPlainString()
            )
        }

        val stock = bomRepository.stockSummary(totals.keys, request.warehouseId)
        val expected = bomRepository.expectedReceipts(totals.keys)
        val summary = totals.map { (nomenclatureId, totalRequired) ->
            val info = names[nomenclatureId] ?: ("?" to "?")
            val stockInfo = stock[nomenclatureId]
            val available = stockInfo?.availableQty ?: BigDecimal.ZERO
            val reserved = stockInfo?.reservedQty ?: BigDecimal.ZERO
            val expectedReceipt = expected[nomenclatureId] ?: BigDecimal.ZERO
            val deficit = totalRequired.subtract(available).max(BigDecimal.ZERO)
            val recommendedPurchase = deficit.subtract(expectedReceipt).max(BigDecimal.ZERO)
            val unit = explodeLines.firstOrNull { it.nomenclatureId == nomenclatureId }?.unit ?: "pcs"
            BomMaterialSummaryDto(
                nomenclatureId = nomenclatureId,
                code = info.first,
                name = info.second,
                unit = unit,
                totalRequired = totalRequired.toPlainString(),
                stockAvailable = available.toPlainString(),
                stockReserved = reserved.toPlainString(),
                expectedReceipt = expectedReceipt.toPlainString(),
                deficit = deficit.toPlainString(),
                recommendedPurchase = recommendedPurchase.toPlainString()
            )
        }

        return BomExplodeReportDto(
            revisionId = revisionId,
            revisionNumber = revision.revisionNumber,
            productModelId = model.id,
            releaseQuantity = releaseQuantity.toPlainString(),
            lines = explodeLines,
            summary = summary
        )
    }

    // --- Validation helpers ---

    private fun validateBomLineRequest(revisionId: Long, request: CreateBomLineRequest) {
        val parentLineId = request.parentLineId
        val substituteOfLineId = request.substituteOfLineId
        val lossPct = request.lossPct
        if (parentLineId != null) {
            val parent = bomRepository.findBomLineById(parentLineId)
                ?: throw NotFoundException("bom_line", parentLineId)
            if (parent.revisionId != revisionId) {
                throw ValidationException("Parent line belongs to another revision", mapOf("parentLineId" to "other_revision"))
            }
        }
        if (substituteOfLineId != null) {
            val target = bomRepository.findBomLineById(substituteOfLineId)
                ?: throw NotFoundException("bom_line", substituteOfLineId)
            if (target.revisionId != revisionId) {
                throw ValidationException("Substitute target belongs to another revision", mapOf("substituteOfLineId" to "other_revision"))
            }
        }
        if (!bomRepository.nomenclatureExists(request.parentNomenclatureId)) {
            throw NotFoundException("nomenclature", request.parentNomenclatureId)
        }
        if (!bomRepository.nomenclatureExists(request.childNomenclatureId)) {
            throw NotFoundException("nomenclature", request.childNomenclatureId)
        }
        if (request.parentNomenclatureId == request.childNomenclatureId) {
            throw ValidationException("Parent and child nomenclature must differ", mapOf("childNomenclatureId" to "same_as_parent"))
        }
        if (lossPct != null && parseLossPct(lossPct).compareTo(HUNDRED) == 0) {
            throw ValidationException("loss_pct cannot be 100%", mapOf("lossPct" to "forbidden_100"))
        }
        if (parentLineId != null) {
            val lines = bomRepository.listBomLinesByRevision(revisionId)
            val byId = lines.associateBy { it.id }
            if (wouldCreateCycle(byId, -1L, parentLineId)) {
                throw ValidationException("Cycle detected in BOM hierarchy", mapOf("parentLineId" to "cycle"))
            }
        }
    }

    private fun validateRevisionLinesForApproval(revisionId: Long) {
        calculator.validate(revisionId)
    }

    private fun wouldCreateCycle(
        byId: Map<Long, org.example.project.generated.jooq.tables.records.BomLinesRecord>,
        newLineId: Long,
        parentLineId: Long
    ): Boolean {
        val visited = mutableSetOf<Long>()
        var current: Long? = parentLineId
        while (current != null) {
            if (current == newLineId) return true
            if (!visited.add(current)) return true
            current = byId[current]?.parentLineId
        }
        return false
    }

    private fun resolveLevel(revisionId: Long, parentLineId: Long?, requestedLevel: Int?): Int {
        if (requestedLevel != null) {
            if (parentLineId == null && requestedLevel != 1) {
                throw ValidationException("Root line level must be 1", mapOf("level" to "root_must_be_1"))
            }
            if (parentLineId != null) {
                val parent = bomRepository.findBomLineById(parentLineId)
                    ?: throw NotFoundException("bom_line", parentLineId)
                if (requestedLevel != parent.level + 1) {
                    throw ValidationException("Level must be parent.level + 1", mapOf("level" to "inconsistent"))
                }
            }
            return requestedLevel
        }
        return if (parentLineId == null) 1 else {
            val parent = bomRepository.findBomLineById(parentLineId)
                ?: throw NotFoundException("bom_line", parentLineId)
            parent.level + 1
        }
    }

    private fun ensureUserExists(id: Long, field: String) {
        if (!bomRepository.userExists(id)) {
            throw NotFoundException("user", id)
        }
    }

    private fun parseQuantity(value: String, field: String): BigDecimal {
        val parsed = value.trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()
            ?: throw ValidationException("Invalid quantity: $value", mapOf(field to "invalid"))
        if (parsed.signum() <= 0) {
            throw ValidationException("Quantity must be positive", mapOf(field to "not_positive"))
        }
        return parsed
    }

    private fun parseLossPct(value: String): BigDecimal {
        val parsed = value.trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()
            ?: throw ValidationException("Invalid loss_pct: $value", mapOf("lossPct" to "invalid"))
        if (parsed.signum() < 0 || parsed.compareTo(HUNDRED) > 0) {
            throw ValidationException("loss_pct must be between 0 and 100", mapOf("lossPct" to "out_of_range"))
        }
        return parsed
    }

    private fun parseConsumptionRate(value: String): BigDecimal {
        val parsed = value.trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()
            ?: throw ValidationException("Invalid consumption_rate: $value", mapOf("consumptionRate" to "invalid"))
        if (parsed.signum() < 0) {
            throw ValidationException("consumption_rate must be non-negative", mapOf("consumptionRate" to "negative"))
        }
        return parsed
    }
}

private fun org.example.project.generated.jooq.tables.records.ProductModelsRecord.toDto(): ProductModelDto = ProductModelDto(
    id = this.id,
    code = this.code,
    name = this.name,
    description = this.description,
    status = ProductModelStatus.valueOf(this.developmentStatus),
    designerId = this.designerId,
    technologistId = this.technologistId,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0
)

private fun org.example.project.generated.jooq.tables.records.ProductRevisionsRecord.toDto(): BomRevisionDto = BomRevisionDto(
    id = this.id,
    productModelId = this.productModelId,
    revisionNumber = this.revisionNumber,
    status = BomRevisionStatus.valueOf(this.status),
    changeReason = this.changeReason,
    approvedBy = this.approvedBy,
    approvedAt = this.approvedAt?.toString(),
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0
)

private fun org.example.project.generated.jooq.tables.records.ProductRevisionsRecord.toSummaryDto(): BomRevisionSummaryDto = BomRevisionSummaryDto(
    id = this.id,
    revisionNumber = this.revisionNumber,
    status = BomRevisionStatus.valueOf(this.status),
    approvedBy = this.approvedBy,
    approvedAt = this.approvedAt?.toString(),
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0
)

private fun org.example.project.generated.jooq.tables.records.BomLinesRecord.toDto(): BomLineDto = BomLineDto(
    id = this.id,
    revisionId = this.revisionId,
    parentLineId = this.parentLineId,
    parentNomenclatureId = this.parentNomenclatureId,
    childNomenclatureId = this.childNomenclatureId,
    quantity = this.quantity.toPlainString(),
    unit = this.unit,
    position = this.position,
    level = this.level,
    mandatory = this.mandatory ?: true,
    substituteOfLineId = this.substituteOfLineId,
    consumptionRate = this.consumptionRate?.toPlainString(),
    lossPct = this.lossPct.toPlainString(),
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy
)
