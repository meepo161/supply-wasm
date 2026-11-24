package org.example.project.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.records.SupplierOffersRecord
import org.example.project.generated.jooq.tables.records.SuppliersRecord
import org.example.project.model.CurrentUser
import org.example.project.repository.DemandRepository
import org.example.project.repository.SupplierRepository
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Поставщики и предложения по потребности (ТЗ §9.5–9.6, §17; D-27, D-28).
 *
 * Границы объёма:
 * - заказы поставщику, платежи, претензии и автоматическая закупка не реализуются;
 * - пороги согласования из §9.7 остаются открытым вопросом и не подменяются
 *   выдуманными числами, поэтому выбор предложения здесь не запускает согласование.
 *
 * Ключевое правило §9.6: нельзя выбрать предложение только по минимальной цене.
 * Сервер требует, чтобы выбранное предложение было действительно пригодным:
 * срок действия не истёк, соответствие техническим требованиям подтверждено,
 * комплект документов отмечен, срок поставки известен и укладывается в требуемую
 * дату, поставщик проверен. Обоснование выбора обязательно и сохраняется.
 */
class SupplierService(
    private val supplierRepository: SupplierRepository,
    private val demandRepository: DemandRepository,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    data class CommandResponse(val status: HttpStatusCode, val body: String)

    private val json = Json { encodeDefaults = true }

    // -----------------------------------------------------------------------
    // Поставщики
    // -----------------------------------------------------------------------

    fun listSuppliers(
        search: String?,
        status: SupplierVerificationStatus?,
        archived: Boolean?,
        currentUser: CurrentUser
    ): List<SupplierDto> {
        currentUser.requirePermission("suppliers", "read")
        return supplierRepository.listSuppliers(search, status?.name, archived).map { it.toDto() }
    }

    fun getSupplier(id: Long, currentUser: CurrentUser): SupplierCardDto {
        currentUser.requirePermission("suppliers", "read")
        val supplier = supplierRepository.findSupplierById(id)
            ?: throw NotFoundException("supplier", id)
        return buildCard(supplier)
    }

    fun createSupplier(request: CreateSupplierRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("suppliers", "create")
        validateSupplierFields(
            name = request.name,
            inn = request.inn,
            kpp = request.kpp,
            ogrn = request.ogrn,
            email = request.email
        )
        val payload = json.encodeToString(CreateSupplierRequest.serializer(), request)
        return executeIdempotent(
            "SUPPLIER_CREATE", request.idempotencyKey, payload, HttpStatusCode.Created, currentUser
        ) {
            supplierRepository.findDuplicate(request.inn, request.name)?.let { duplicate ->
                    throw ConflictException(
                        "Supplier already exists: ${duplicate.number} ${duplicate.name}"
                    )
                }
                val number = supplierRepository.generateNumber()
                val supplier = supplierRepository.insertSupplier(
                    number = number,
                    name = request.name.trim(),
                    fullName = request.fullName?.trim()?.takeIf { it.isNotEmpty() },
                    kind = request.kind.name,
                    inn = request.inn?.trim()?.takeIf { it.isNotEmpty() },
                    kpp = request.kpp?.trim()?.takeIf { it.isNotEmpty() },
                    ogrn = request.ogrn?.trim()?.takeIf { it.isNotEmpty() },
                    okpo = request.okpo?.trim()?.takeIf { it.isNotEmpty() },
                    country = request.country?.trim()?.takeIf { it.isNotEmpty() },
                    legalAddress = request.legalAddress?.trim()?.takeIf { it.isNotEmpty() },
                    site = request.site?.trim()?.takeIf { it.isNotEmpty() },
                    email = request.email?.trim()?.takeIf { it.isNotEmpty() },
                    phone = request.phone?.trim()?.takeIf { it.isNotEmpty() },
                    responsibleId = request.responsibleId,
                    note = request.note?.trim()?.takeIf { it.isNotEmpty() },
                    createdBy = currentUser.userId
                )
                request.contacts.forEach { contact ->
                    supplierRepository.insertContact(
                        supplier.id,
                        SupplierRepository.NewContact(
                            fullName = contact.fullName.trim(),
                            position = contact.position?.trim(),
                            email = contact.email?.trim(),
                            phone = contact.phone?.trim(),
                            messenger = contact.messenger?.trim(),
                            isPrimary = contact.isPrimary,
                            note = contact.note?.trim()
                        ),
                        currentUser.userId
                    )
                }
                request.addresses.forEach { address ->
                    supplierRepository.insertAddress(
                        supplier.id,
                        SupplierRepository.NewAddress(
                            kind = address.kind.name,
                            address = address.address.trim(),
                            note = address.note?.trim()
                        ),
                        currentUser.userId
                    )
                }
                request.documents.forEach { document ->
                    supplierRepository.insertDocument(
                        supplier.id,
                        document.toNewDocument(),
                        currentUser.userId
                    )
                }
                if (request.categoryIds.isNotEmpty()) {
                    request.categoryIds.forEach { categoryId ->
                        supplierRepository.findCategoryById(categoryId)
                            ?: throw NotFoundException("supplier_category", categoryId)
                    }
                    supplierRepository.replaceSupplierCategories(
                        supplier.id, request.categoryIds, currentUser.userId
                    )
                }
                auditService.log(
                    currentUser.userId, "CREATE", "SUPPLIER", supplier.id,
                    mapOf("number" to number, "name" to supplier.name, "inn" to supplier.inn)
                )
                val card = buildCard(supplier)
                supplier.id to json.encodeToString(SupplierCardDto.serializer(), card)
        }
    }

    fun updateSupplier(id: Long, request: UpdateSupplierRequest, currentUser: CurrentUser): SupplierDto {
        currentUser.requirePermission("suppliers", "update")
        validateSupplierFields(
            name = request.name,
            inn = request.inn,
            kpp = request.kpp,
            ogrn = request.ogrn,
            email = request.email
        )
        val existing = supplierRepository.findSupplierById(id) ?: throw NotFoundException("supplier", id)
        if (existing.archived) {
            throw ConflictException("Archived supplier cannot be changed")
        }
        request.inn?.let { inn ->
            if (inn.isNotBlank()) {
                supplierRepository.findDuplicate(inn, request.name ?: existing.name, excludeId = id)
                    ?.let { throw ConflictException("Another supplier already uses INN $inn") }
            }
        }
        val updated = supplierRepository.updateSupplier(
            id = id,
            expectedVersion = request.version,
            name = request.name?.trim(),
            fullName = request.fullName?.trim(),
            kind = request.kind?.name,
            inn = request.inn?.trim(),
            kpp = request.kpp?.trim(),
            ogrn = request.ogrn?.trim(),
            okpo = request.okpo?.trim(),
            country = request.country?.trim(),
            legalAddress = request.legalAddress?.trim(),
            site = request.site?.trim(),
            email = request.email?.trim(),
            phone = request.phone?.trim(),
            responsibleId = request.responsibleId,
            note = request.note?.trim(),
            updatedBy = currentUser.userId
        ) ?: throw ConflictException("Supplier was modified concurrently, reload the card")
        auditService.log(
            currentUser.userId, "UPDATE", "SUPPLIER", id,
            mapOf("number" to updated.number, "fields" to request.toString())
        )
        return updated.toDto()
    }

    fun verifySupplier(
        id: Long,
        request: SupplierVerificationRequest,
        currentUser: CurrentUser
    ): CommandResponse {
        currentUser.requirePermission("suppliers", "verify")
        if (request.status == SupplierVerificationStatus.REJECTED &&
            request.note.isNullOrBlank()
        ) {
            throw ValidationException("Rejection reason is required", mapOf("note" to "required"))
        }
        // Проверить контрагента может не тот, кто его завёл: иначе «проверка»
        // превращается в самоподтверждение.
        val existing = supplierRepository.findSupplierById(id) ?: throw NotFoundException("supplier", id)
        if (existing.createdBy == currentUser.userId &&
            !currentUser.hasPermission("suppliers", "verify_any")
        ) {
            throw org.example.project.exception.ForbiddenException(
                "Supplier verification requires suppliers:verify_any when verifying own card"
            )
        }
        val verifierId = if (request.status == SupplierVerificationStatus.VERIFIED ||
            request.status == SupplierVerificationStatus.REJECTED
        ) {
            currentUser.userId
        } else {
            null
        }
        val payload = json.encodeToString(SupplierVerificationRequest.serializer(), request)
        return executeIdempotent(
            "SUPPLIER_VERIFY", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser
        ) {
            val updated = supplierRepository.updateVerification(
                id = id,
                expectedVersion = request.version,
                status = request.status.name,
                note = request.note?.trim(),
                verifierId = verifierId
            ) ?: throw ConflictException("Supplier was modified concurrently, reload the card")
            auditService.log(
                currentUser.userId, "VERIFY", "SUPPLIER", id,
                mapOf("status" to request.status.name, "note" to request.note)
            )
            id to json.encodeToString(SupplierDto.serializer(), updated.toDto())
        }
    }

    fun archiveSupplier(
        id: Long,
        version: Long,
        archived: Boolean,
        currentUser: CurrentUser
    ): SupplierDto {
        currentUser.requirePermission("suppliers", "update")
        val updated = supplierRepository.setArchived(id, version, archived, currentUser.userId)
            ?: throw ConflictException("Supplier was modified concurrently, reload the card")
        auditService.log(
            currentUser.userId, if (archived) "ARCHIVE" else "RESTORE", "SUPPLIER", id,
            mapOf("number" to updated.number)
        )
        return updated.toDto()
    }

    fun addContact(id: Long, request: AddSupplierContactRequest, currentUser: CurrentUser): SupplierCardDto {
        currentUser.requirePermission("suppliers", "update")
        val supplier = requireSupplier(id)
        if (request.contact.fullName.isBlank()) {
            throw ValidationException("Contact name is required", mapOf("fullName" to "required"))
        }
        requireVersion(supplier, request.version)
        supplierRepository.insertContact(
            id,
            SupplierRepository.NewContact(
                fullName = request.contact.fullName.trim(),
                position = request.contact.position?.trim(),
                email = request.contact.email?.trim(),
                phone = request.contact.phone?.trim(),
                messenger = request.contact.messenger?.trim(),
                isPrimary = request.contact.isPrimary,
                note = request.contact.note?.trim()
            ),
            currentUser.userId
        )
        supplierRepository.touchVersion(id, currentUser.userId)
        auditService.log(currentUser.userId, "ADD_CONTACT", "SUPPLIER", id, mapOf("name" to request.contact.fullName))
        return buildCard(requireSupplier(id))
    }

    fun addAddress(id: Long, request: AddSupplierAddressRequest, currentUser: CurrentUser): SupplierCardDto {
        currentUser.requirePermission("suppliers", "update")
        val supplier = requireSupplier(id)
        if (request.address.address.isBlank()) {
            throw ValidationException("Address is required", mapOf("address" to "required"))
        }
        requireVersion(supplier, request.version)
        supplierRepository.insertAddress(
            id,
            SupplierRepository.NewAddress(
                kind = request.address.kind.name,
                address = request.address.address.trim(),
                note = request.address.note?.trim()
            ),
            currentUser.userId
        )
        supplierRepository.touchVersion(id, currentUser.userId)
        auditService.log(currentUser.userId, "ADD_ADDRESS", "SUPPLIER", id, mapOf("kind" to request.address.kind.name))
        return buildCard(requireSupplier(id))
    }

    fun addDocument(id: Long, request: AddSupplierDocumentRequest, currentUser: CurrentUser): SupplierCardDto {
        currentUser.requirePermission("suppliers", "update")
        val supplier = requireSupplier(id)
        if (request.document.title.isBlank()) {
            throw ValidationException("Document title is required", mapOf("title" to "required"))
        }
        requireVersion(supplier, request.version)
        supplierRepository.insertDocument(id, request.document.toNewDocument(), currentUser.userId)
        supplierRepository.touchVersion(id, currentUser.userId)
        auditService.log(
            currentUser.userId, "ADD_DOCUMENT", "SUPPLIER", id,
            mapOf("kind" to request.document.kind.name, "title" to request.document.title)
        )
        return buildCard(requireSupplier(id))
    }

    fun setCategories(
        id: Long,
        request: SetSupplierCategoriesRequest,
        currentUser: CurrentUser
    ): SupplierCardDto {
        currentUser.requirePermission("suppliers", "update")
        val supplier = requireSupplier(id)
        requireVersion(supplier, request.version)
        request.categoryIds.forEach { categoryId ->
            supplierRepository.findCategoryById(categoryId)
                ?: throw NotFoundException("supplier_category", categoryId)
        }
        supplierRepository.replaceSupplierCategories(id, request.categoryIds, currentUser.userId)
        supplierRepository.touchVersion(id, currentUser.userId)
        auditService.log(
            currentUser.userId, "SET_CATEGORIES", "SUPPLIER", id,
            mapOf("categoryIds" to request.categoryIds)
        )
        return buildCard(requireSupplier(id))
    }

    fun listCategories(currentUser: CurrentUser): List<SupplierCategoryDto> {
        currentUser.requirePermission("suppliers", "read")
        return supplierRepository.listCategories().map { it.toDto() }
    }

    fun createCategory(
        request: CreateSupplierCategoryRequest,
        currentUser: CurrentUser
    ): SupplierCategoryDto {
        currentUser.requirePermission("suppliers", "update")
        if (request.code.isBlank() || request.name.isBlank()) {
            throw ValidationException(
                "Category code and name are required",
                mapOf("code" to "required", "name" to "required")
            )
        }
        val existing = supplierRepository.findCategoryByCode(request.code.trim())
        if (existing != null) {
            throw ConflictException("Category ${request.code} already exists")
        }
        val created = supplierRepository.insertCategory(
            code = request.code.trim(),
            name = request.name.trim(),
            description = request.description?.trim(),
            createdBy = currentUser.userId
        )
        auditService.log(
            currentUser.userId, "CREATE", "SUPPLIER_CATEGORY", created.id,
            mapOf("code" to created.code)
        )
        return created.toDto()
    }

    // -----------------------------------------------------------------------
    // Предложения
    // -----------------------------------------------------------------------

    fun listOffers(
        demandId: Long?,
        supplierId: Long?,
        status: SupplierOfferStatus?,
        currentUser: CurrentUser
    ): List<SupplierOfferDto> {
        currentUser.requirePermission("suppliers", "read")
        val offers = supplierRepository.listOffers(demandId, supplierId, status?.name)
        if (offers.isEmpty()) return emptyList()
        val totals = supplierRepository.offerTotalsBatch(offers.map { it.id })
        return offers.map { offer ->
            val supplierName = supplierRepository.findSupplierById(offer.supplierId)?.name
            offer.toDto(totals[offer.id], supplierName)
        }
    }

    fun getOffer(id: Long, currentUser: CurrentUser): SupplierOfferDto {
        currentUser.requirePermission("suppliers", "read")
        val offer = supplierRepository.findOfferById(id) ?: throw NotFoundException("supplier_offer", id)
        return buildOffer(offer)
    }

    fun listOfferHistory(id: Long, currentUser: CurrentUser): List<SupplierOfferHistoryDto> {
        currentUser.requirePermission("suppliers", "read")
        supplierRepository.findOfferById(id) ?: throw NotFoundException("supplier_offer", id)
        return supplierRepository.listOfferHistory(id).map {
            SupplierOfferHistoryDto(
                id = it.id!!,
                action = it.action,
                fromStatus = it.fromStatus,
                toStatus = it.toStatus,
                reason = it.reason,
                changedBy = it.changedBy,
                changedAt = it.changedAt?.toString()
            )
        }
    }

    fun createOffer(request: CreateSupplierOfferRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("suppliers", "offer")
        if (request.lines.isEmpty()) {
            throw ValidationException("Offer must contain at least one line", mapOf("lines" to "required"))
        }
        validateCurrency(request.currency)
        validateDelivery(request.deliveryDays, request.deliveryCost)
        val validUntil = parseDate(request.validUntil, "validUntil")
            ?: throw ValidationException("Offer validity date is required", mapOf("validUntil" to "required"))
        if (validUntil.isBefore(LocalDate.now())) {
            throw ValidationException(
                "Offer validity date is already in the past",
                mapOf("validUntil" to "in_past")
            )
        }
        val payload = json.encodeToString(CreateSupplierOfferRequest.serializer(), request)
        return executeIdempotent(
            "SUPPLIER_OFFER_CREATE", request.idempotencyKey, payload, HttpStatusCode.Created, currentUser
        ) {
                val demand = supplierRepository.findDemandById(request.demandId)
                    ?: throw NotFoundException("demand", request.demandId)
                if (demand.status == "CANCELLED" || demand.status == "COVERED") {
                    throw ConflictException("Demand ${demand.number} is closed for offers (${demand.status})")
                }
                val supplier = supplierRepository.findSupplierById(request.supplierId)
                    ?: throw NotFoundException("supplier", request.supplierId)
                if (supplier.archived) {
                    throw ConflictException("Archived supplier cannot receive new offers")
                }
                val demandLines = supplierRepository.listDemandLines(request.demandId).associateBy { it.id!! }
                val normalized = request.lines.mapIndexed { index, line ->
                    val demandLine = demandLines[line.demandLineId]
                        ?: throw NotFoundException("demand_line", line.demandLineId)
                    val quantity = parseQuantity(line.quantity, "lines[$index].quantity")
                    if (quantity <= BigDecimal.ZERO) {
                        throw ValidationException(
                            "Offered quantity must be positive",
                            mapOf("lines[$index].quantity" to "not_positive")
                        )
                    }
                    val deficit = demandLine.deficitQuantity ?: demandLine.quantity
                    if (quantity > deficit) {
                        throw ValidationException(
                            "Offered quantity exceeds the open deficit of the demand line",
                            mapOf("lines[$index].quantity" to "exceeds_deficit")
                        )
                    }
                    val price = parseQuantity(line.unitPrice, "lines[$index].unitPrice")
                    if (price < BigDecimal.ZERO) {
                        throw ValidationException(
                            "Unit price must not be negative",
                            mapOf("lines[$index].unitPrice" to "negative")
                        )
                    }
                    val vat = parseQuantity(line.vatRate, "lines[$index].vatRate")
                    if (vat < BigDecimal.ZERO || vat > BigDecimal.valueOf(100)) {
                        throw ValidationException(
                            "VAT rate must be between 0 and 100",
                            mapOf("lines[$index].vatRate" to "out_of_range")
                        )
                    }
                    SupplierRepository.NewOfferLine(
                        lineNo = index + 1,
                        demandLineId = demandLine.id!!,
                        nomenclatureId = demandLine.nomenclatureId,
                        quantity = quantity,
                        unit = demandLine.unit,
                        unitPrice = price,
                        vatRate = vat,
                        note = line.note?.trim()
                    )
                }
                if (normalized.map { it.demandLineId }.distinct().size != normalized.size) {
                    throw ValidationException(
                        "Each demand line may appear only once in an offer",
                        mapOf("lines" to "duplicate_demand_line")
                    )
                }
                val number = supplierRepository.generateOfferNumber()
                val offer = supplierRepository.insertOffer(
                    number = number,
                    demandId = request.demandId,
                    supplierId = request.supplierId,
                    currency = request.currency.trim().uppercase(),
                    compliance = request.compliance.name,
                    complianceNote = request.complianceNote?.trim(),
                    documentsComplete = request.documentsComplete,
                    deliveryDays = request.deliveryDays,
                    deliveryTerms = request.deliveryTerms?.trim(),
                    deliveryCost = request.deliveryCost?.let { parseQuantity(it, "deliveryCost") },
                    paymentTerms = request.paymentTerms?.trim(),
                    warrantyMonths = request.warrantyMonths,
                    validUntil = validUntil,
                    supplierContactName = request.supplierContactName?.trim(),
                    supplierContactChannel = request.supplierContactChannel?.trim(),
                    note = request.note?.trim(),
                    requestedBy = currentUser.userId,
                    createdBy = currentUser.userId
                )
                supplierRepository.replaceOfferLines(offer.id, normalized, currentUser.userId)
                supplierRepository.insertOfferHistory(
                    offer.id, "CREATED", null, "RECEIVED", null, currentUser.userId
                )
                if (demand.status == "APPROVED") {
                    // Регламент §9.3: согласованная потребность передаётся снабжению.
                    demandRepository.transitionStatus(
                        id = demand.id,
                        expectedVersion = demand.version,
                        fromStatus = "APPROVED",
                        newStatus = "IN_PROCUREMENT"
                    ) ?: throw ConflictException("Demand was modified concurrently")
                    demandRepository.insertDemandHistory(
                        demandId = demand.id,
                        action = "STATUS_CHANGED",
                        fromStatus = "APPROVED",
                        toStatus = "IN_PROCUREMENT",
                        reason = "Supplier offer ${offer.number} created",
                        changedBy = currentUser.userId
                    )
                    auditService.log(
                        currentUser.userId, "STATUS_CHANGED", "DEMAND", demand.id,
                        mapOf("number" to demand.number, "offerId" to offer.id)
                    )
                }
                auditService.log(
                    currentUser.userId, "CREATE", "SUPPLIER_OFFER", offer.id,
                    mapOf(
                        "number" to number,
                        "demandId" to request.demandId,
                        "supplierId" to request.supplierId
                    )
                )
                offer.id to json.encodeToString(SupplierOfferDto.serializer(), buildOffer(offer))
        }
    }

    fun updateOffer(id: Long, request: UpdateSupplierOfferRequest, currentUser: CurrentUser): SupplierOfferDto {
        currentUser.requirePermission("suppliers", "offer")
        val offer = supplierRepository.lockOfferById(id) ?: throw NotFoundException("supplier_offer", id)
        if (offer.status == "SELECTED") {
            throw ConflictException("Selected offer is locked; withdraw the selection first")
        }
        if (offer.status == "WITHDRAWN" || offer.status == "EXPIRED") {
            throw ConflictException("Offer cannot be changed in status ${offer.status}")
        }
        validateDelivery(request.deliveryDays, request.deliveryCost)
        return dsl.transactionResult(TransactionalCallable {
            val demandLines = supplierRepository.listDemandLines(offer.demandId).associateBy { it.id!! }
            request.lines?.let { lines ->
                if (lines.isEmpty()) {
                    throw ValidationException("Offer must contain at least one line", mapOf("lines" to "required"))
                }
                val normalized = lines.mapIndexed { index, line ->
                    val demandLine = demandLines[line.demandLineId]
                        ?: throw NotFoundException("demand_line", line.demandLineId)
                    val quantity = parseQuantity(line.quantity, "lines[$index].quantity")
                    if (quantity <= BigDecimal.ZERO) {
                        throw ValidationException(
                            "Offered quantity must be positive",
                            mapOf("lines[$index].quantity" to "not_positive")
                        )
                    }
                    val deficit = demandLine.deficitQuantity ?: demandLine.quantity
                    if (quantity > deficit) {
                        throw ValidationException(
                            "Offered quantity exceeds the open deficit of the demand line",
                            mapOf("lines[$index].quantity" to "exceeds_deficit")
                        )
                    }
                    val price = parseQuantity(line.unitPrice, "lines[$index].unitPrice")
                    if (price < BigDecimal.ZERO) {
                        throw ValidationException(
                            "Unit price must not be negative",
                            mapOf("lines[$index].unitPrice" to "negative")
                        )
                    }
                    val vat = parseQuantity(line.vatRate, "lines[$index].vatRate")
                    if (vat < BigDecimal.ZERO || vat > BigDecimal.valueOf(100)) {
                        throw ValidationException(
                            "VAT rate must be between 0 and 100",
                            mapOf("lines[$index].vatRate" to "out_of_range")
                        )
                    }
                    SupplierRepository.NewOfferLine(
                        lineNo = index + 1,
                        demandLineId = demandLine.id!!,
                        nomenclatureId = demandLine.nomenclatureId,
                        quantity = quantity,
                        unit = demandLine.unit,
                        unitPrice = price,
                        vatRate = vat,
                        note = line.note?.trim()
                    )
                }
                if (normalized.map { it.demandLineId }.distinct().size != normalized.size) {
                    throw ValidationException(
                        "Each demand line may appear only once in an offer",
                        mapOf("lines" to "duplicate_demand_line")
                    )
                }
                supplierRepository.replaceOfferLines(id, normalized, currentUser.userId)
            }
            val updated = supplierRepository.updateOfferDetails(
                id = id,
                expectedVersion = request.version,
                compliance = request.compliance?.name,
                complianceNote = request.complianceNote?.trim(),
                documentsComplete = request.documentsComplete,
                deliveryDays = request.deliveryDays,
                deliveryTerms = request.deliveryTerms?.trim(),
                deliveryCost = request.deliveryCost?.let { parseQuantity(it, "deliveryCost") },
                paymentTerms = request.paymentTerms?.trim(),
                warrantyMonths = request.warrantyMonths,
                validUntil = request.validUntil?.let { parseDate(it, "validUntil") },
                note = request.note?.trim(),
                updatedBy = currentUser.userId
            ) ?: throw ConflictException("Offer was modified concurrently, reload it")
            supplierRepository.insertOfferHistory(
                id, "UPDATED", offer.status, updated.status, null, currentUser.userId
            )
            auditService.log(
                currentUser.userId, "UPDATE", "SUPPLIER_OFFER", id,
                mapOf("number" to offer.number)
            )
            buildOffer(updated)
        })
    }

    fun changeOfferStatus(
        id: Long,
        request: SupplierOfferStatusRequest,
        currentUser: CurrentUser
    ): CommandResponse {
        currentUser.requirePermission("suppliers", "offer")
        if (request.status == SupplierOfferStatus.SELECTED) {
            throw ValidationException(
                "Use the selection command to make an offer preferred",
                mapOf("status" to "use_select_endpoint")
            )
        }
        val allowed = when (request.status) {
            SupplierOfferStatus.SHORTLISTED -> listOf("RECEIVED", "SHORTLISTED")
            SupplierOfferStatus.REJECTED -> listOf("RECEIVED", "SHORTLISTED")
            SupplierOfferStatus.WITHDRAWN -> listOf("RECEIVED", "SHORTLISTED", "REJECTED")
            else -> throw ValidationException(
                "Unsupported status transition",
                mapOf("status" to "unsupported")
            )
        }
        if (request.status == SupplierOfferStatus.REJECTED && request.reason.isNullOrBlank()) {
            throw ValidationException("Rejection reason is required", mapOf("reason" to "required"))
        }
        val payload = json.encodeToString(SupplierOfferStatusRequest.serializer(), request)
        return executeIdempotent(
            "SUPPLIER_OFFER_STATUS", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser
        ) {
            val existing = supplierRepository.findOfferById(id) ?: throw NotFoundException("supplier_offer", id)
            val updated = supplierRepository.updateOfferStatus(
                id = id,
                expectedVersion = request.version,
                fromStatuses = allowed,
                newStatus = request.status.name,
                updatedBy = currentUser.userId
            ) ?: throw ConflictException("Offer was modified concurrently, reload it")
            supplierRepository.insertOfferHistory(
                id, "STATUS_CHANGED", existing.status, updated.status,
                request.reason?.trim(), currentUser.userId
            )
            auditService.log(
                currentUser.userId, request.status.name, "SUPPLIER_OFFER", id,
                mapOf("number" to existing.number, "reason" to request.reason)
            )
            id to json.encodeToString(SupplierOfferDto.serializer(), buildOffer(updated))
        }
    }

    /**
     * Выбор предпочтительного предложения инициатором потребности (D-28).
     *
     * Именно здесь реализовано требование §9.6: выбор невозможен только по цене.
     * Проверки выполняются на сервере, одна транзакция, версия и идемпотентность.
     */
    fun selectOffer(id: Long, request: SelectSupplierOfferRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("suppliers", "select")
        if (request.reason.trim().length < 10) {
            throw ValidationException(
                "Selection reason must be at least 10 characters",
                mapOf("reason" to "too_short")
            )
        }
        val payload = json.encodeToString(SelectSupplierOfferRequest.serializer(), request)
        return executeIdempotent(
            "SUPPLIER_OFFER_SELECT", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser
        ) {
                val offer = supplierRepository.lockOfferById(id)
                    ?: throw NotFoundException("supplier_offer", id)
                if (offer.version != request.version) {
                    throw ConflictException("Offer was modified concurrently, reload it")
                }
                if (offer.status == "SELECTED") {
                    throw ConflictException("Offer ${offer.number} is already selected")
                }
                if (offer.status != "RECEIVED" && offer.status != "SHORTLISTED") {
                    throw ConflictException("Offer cannot be selected in status ${offer.status}")
                }
                val demand = supplierRepository.findDemandById(offer.demandId)
                    ?: throw NotFoundException("demand", offer.demandId)
                // D-28: выбирает инициатор потребности. Снабженец с расширенным
                // полномочием может сделать это за него, и это видно в аудите.
                val isInitiator = demand.initiatorId == currentUser.userId
                if (!isInitiator && !currentUser.hasPermission("suppliers", "select_any")) {
                    throw org.example.project.exception.ForbiddenException(
                        "Only the demand initiator can select the preferred offer"
                    )
                }
                if (demand.status == "CANCELLED") {
                    throw ConflictException("Demand is cancelled, offer selection is not allowed")
                }
                val supplier = supplierRepository.findSupplierById(offer.supplierId)
                    ?: throw NotFoundException("supplier", offer.supplierId)
                validateOfferEligibility(offer, supplier, demand.requiredDate)

                val alreadySelected = supplierRepository.listOffersForDemand(offer.demandId)
                    .firstOrNull { it.status == "SELECTED" }
                if (alreadySelected != null) {
                    throw ConflictException(
                        "Demand already has a preferred offer ${alreadySelected.number}; " +
                            "withdraw the selection first"
                    )
                }
                val selected = supplierRepository.updateOfferStatus(
                    id = id,
                    expectedVersion = request.version,
                    fromStatuses = listOf("RECEIVED", "SHORTLISTED"),
                    newStatus = "SELECTED",
                    selectedBy = currentUser.userId,
                    selectedAt = OffsetDateTime.now(),
                    selectionReason = request.reason.trim(),
                    updatedBy = currentUser.userId
                ) ?: throw ConflictException("Offer was modified concurrently, reload it")
                supplierRepository.insertOfferHistory(
                    id, "SELECTED", offer.status, "SELECTED", request.reason.trim(), currentUser.userId
                )
                auditService.log(
                    currentUser.userId, "SELECT", "SUPPLIER_OFFER", id,
                    mapOf(
                        "number" to offer.number,
                        "demandId" to offer.demandId,
                        "supplierId" to offer.supplierId,
                        "reason" to request.reason.trim(),
                        "onBehalfOfInitiator" to !isInitiator
                    )
                )
                id to json.encodeToString(SupplierOfferDto.serializer(), buildOffer(selected))
        }
    }

    fun clearSelection(id: Long, version: Long, currentUser: CurrentUser): SupplierOfferDto {
        currentUser.requirePermission("suppliers", "select")
        return dsl.transactionResult(TransactionalCallable {
            val offer = supplierRepository.lockOfferById(id) ?: throw NotFoundException("supplier_offer", id)
            if (offer.status != "SELECTED") {
                throw ConflictException("Offer is not selected")
            }
            val demand = supplierRepository.findDemandById(offer.demandId)
                ?: throw NotFoundException("demand", offer.demandId)
            if (demand.initiatorId != currentUser.userId &&
                !currentUser.hasPermission("suppliers", "select_any")
            ) {
                throw org.example.project.exception.ForbiddenException(
                    "Only the demand initiator can withdraw the preferred offer"
                )
            }
            val updated = dsl.update(
                org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS
            )
                .set(org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS.STATUS, "SHORTLISTED")
                .set(org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS.SELECTED_BY, null as Long?)
                .set(org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS.SELECTED_AT, null as OffsetDateTime?)
                .set(org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS.SELECTION_REASON, null as String?)
                .set(org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS.UPDATED_AT, OffsetDateTime.now())
                .set(org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS.UPDATED_BY, currentUser.userId)
                .set(
                    org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS.VERSION,
                    org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS.VERSION.plus(1)
                )
                .where(
                    org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS.ID.eq(id)
                        .and(org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS.VERSION.eq(version))
                )
                .execute()
            if (updated == 0) throw ConflictException("Offer was modified concurrently, reload it")
            supplierRepository.insertOfferHistory(
                id, "SELECTION_CLEARED", "SELECTED", "SHORTLISTED", null, currentUser.userId
            )
            auditService.log(
                currentUser.userId, "CLEAR_SELECTION", "SUPPLIER_OFFER", id,
                mapOf("number" to offer.number)
            )
            buildOffer(supplierRepository.findOfferById(id)!!)
        })
    }

    /**
     * Сравнение предложений по потребности (§9.6).
     *
     * Сервер отдельно сообщает «самое дешёвое» и «рекомендуемое»: рекомендация
     * учитывает соответствие, документы, срок действия и срок поставки.
     * Итоговое решение принимает инициатор, но выбрать непригодное предложение
     * нельзя — это и есть запрет выбора только по минимальной цене.
     */
    fun compareOffers(demandId: Long, currentUser: CurrentUser): SupplierOfferComparisonDto {
        currentUser.requirePermission("suppliers", "read")
        val demand = supplierRepository.findDemandById(demandId)
            ?: throw NotFoundException("demand", demandId)
        val offers = supplierRepository.listOffersForDemand(demandId)
        val demandLines = supplierRepository.listDemandLines(demandId)
        val nomenclatureCache = demandLines.associate { line ->
            line.nomenclatureId to supplierRepository.findNomenclatureById(line.nomenclatureId)
        }
        val offerLines = supplierRepository.listOfferLinesForOffers(offers.map { it.id })
            .groupBy { it.offerId }
        val totals = supplierRepository.offerTotalsBatch(offers.map { it.id })
        val requiredDate = demand.requiredDate
        val today = LocalDate.now()

        val columns = offers.map { offer ->
            val supplier = supplierRepository.findSupplierById(offer.supplierId)
            val lines = offerLines[offer.id].orEmpty()
            val lineDtos = demandLines.map { demandLine ->
                val offered = lines.firstOrNull { it.demandLineId == demandLine.id }
                val nomenclature = nomenclatureCache[demandLine.nomenclatureId]
                SupplierOfferComparisonLineDto(
                    demandLineId = demandLine.id!!,
                    nomenclatureId = demandLine.nomenclatureId,
                    nomenclatureCode = nomenclature?.code,
                    nomenclatureName = nomenclature?.name,
                    requiredQuantity = demandLine.quantity.toPlainString(),
                    offeredQuantity = offered?.quantity?.toPlainString(),
                    unitPrice = offered?.unitPrice?.toPlainString(),
                    lineTotal = offered?.let { lineTotal(it)?.toPlainString() }
                )
            }
            val totalsEntry = totals[offer.id]
            SupplierOfferComparisonColumnDto(
                offerId = offer.id,
                number = offer.number,
                supplierId = offer.supplierId,
                supplierName = supplier?.name ?: "—",
                status = SupplierOfferStatus.valueOf(offer.status),
                currency = offer.currency,
                compliance = SupplierOfferCompliance.valueOf(offer.compliance),
                documentsComplete = offer.documentsComplete,
                deliveryDays = offer.deliveryDays,
                paymentTerms = offer.paymentTerms,
                warrantyMonths = offer.warrantyMonths,
                validUntil = offer.validUntil.toString(),
                validUntilPassed = offer.validUntil.isBefore(today),
                netTotal = totalsEntry?.first?.toPlainString() ?: "0",
                grossTotal = totalsEntry?.third?.toPlainString() ?: "0",
                supplierVerified = supplier?.verificationStatus == "VERIFIED",
                lines = lineDtos
            )
        }

        val warnings = mutableListOf<SupplierOfferWarningDto>()
        columns.forEach { column ->
            if (column.validUntilPassed) {
                warnings += SupplierOfferWarningDto(
                    column.offerId, SupplierOfferWarningType.EXPIRED,
                    "Срок действия предложения истёк ${column.validUntil}"
                )
            }
            when (column.compliance) {
                SupplierOfferCompliance.DEVIATION, SupplierOfferCompliance.PARTIAL ->
                    warnings += SupplierOfferWarningDto(
                        column.offerId, SupplierOfferWarningType.NOT_COMPLIANT,
                        "Предложение не соответствует техническим требованиям полностью"
                    )
                SupplierOfferCompliance.UNKNOWN -> warnings += SupplierOfferWarningDto(
                    column.offerId, SupplierOfferWarningType.COMPLIANCE_UNKNOWN,
                    "Соответствие техническим требованиям не проверено"
                )
                SupplierOfferCompliance.FULL -> Unit
            }
            if (!column.documentsComplete) {
                warnings += SupplierOfferWarningDto(
                    column.offerId, SupplierOfferWarningType.DOCUMENTS_MISSING,
                    "Комплект документов не подтверждён"
                )
            }
            if (!column.supplierVerified) {
                warnings += SupplierOfferWarningDto(
                    column.offerId, SupplierOfferWarningType.SUPPLIER_NOT_VERIFIED,
                    "Поставщик не прошёл проверку"
                )
            }
            val deliveryDays = column.deliveryDays
            if (deliveryDays == null) {
                warnings += SupplierOfferWarningDto(
                    column.offerId, SupplierOfferWarningType.DELIVERY_UNKNOWN,
                    "Срок поставки не указан"
                )
            }
            val missing = column.lines.count { it.offeredQuantity == null }
            if (missing > 0) {
                warnings += SupplierOfferWarningDto(
                    column.offerId, SupplierOfferWarningType.INCOMPLETE_COVERAGE,
                    "Позиций вне предложения: $missing из ${column.lines.size}"
                )
            }
            if (requiredDate != null && deliveryDays != null &&
                today.plusDays(deliveryDays.toLong()).isAfter(requiredDate)
            ) {
                warnings += SupplierOfferWarningDto(
                    column.offerId, SupplierOfferWarningType.DELIVERY_TOO_LONG,
                    "Срок поставки позже требуемой даты $requiredDate"
                )
            }
        }

        val eligible = offers.filter { it.status == "RECEIVED" || it.status == "SHORTLISTED" }
        val cheapest = eligible
            .filter { totals[it.id] != null }
            .minByOrNull { totals[it.id]!!.third }
            ?.id
        val eligibleIds = eligible.map { it.id }.toSet()
        val recommended = offers
            .filter { it.id in eligibleIds }
            .filter { offer ->
                warnings.none { warning ->
                    warning.offerId == offer.id && warning.type in setOf(
                        SupplierOfferWarningType.EXPIRED,
                        SupplierOfferWarningType.NOT_COMPLIANT,
                        SupplierOfferWarningType.COMPLIANCE_UNKNOWN,
                        SupplierOfferWarningType.DOCUMENTS_MISSING,
                        SupplierOfferWarningType.DELIVERY_UNKNOWN,
                        SupplierOfferWarningType.DELIVERY_TOO_LONG
                    )
                }
            }
            .minByOrNull { totals[it.id]?.third ?: BigDecimal.valueOf(Long.MAX_VALUE) }
            ?.id

        val selected = offers.firstOrNull { it.status == "SELECTED" }
        val canSelect = demand.status != "CANCELLED" && eligible.isNotEmpty() && selected == null
        val blockedReason = when {
            demand.status == "CANCELLED" -> "Потребность отменена"
            selected != null -> "Предпочтительное предложение уже выбрано: ${selected.number}"
            eligible.isEmpty() -> "Нет предложений, доступных к выбору"
            recommended == null -> "Ни одно предложение не прошло проверку соответствия и сроков"
            else -> null
        }

        return SupplierOfferComparisonDto(
            demandId = demandId,
            demandNumber = demand.number,
            demandStatus = demand.status,
            requiredDate = requiredDate?.toString(),
            columns = columns,
            warnings = warnings,
            cheapestOfferId = cheapest,
            recommendedOfferId = recommended,
            selectedOfferId = selected?.id,
            canSelect = canSelect,
            selectionBlockedReason = blockedReason
        )
    }

    // -----------------------------------------------------------------------
    // Проверки пригодности и служебные методы
    // -----------------------------------------------------------------------

    /**
     * Пригодность предложения к выбору. Проверки намеренно строгие и явные:
     * каждое отклонение имеет отдельное сообщение, чтобы инициатор понимал,
     * чего не хватает, а не получал «нельзя».
     */
    private fun validateOfferEligibility(
        offer: SupplierOffersRecord,
        supplier: SuppliersRecord,
        requiredDate: LocalDate?
    ) {
        if (offer.validUntil.isBefore(LocalDate.now())) {
            throw ConflictException(
                "Offer validity expired on ${offer.validUntil}; request an updated offer"
            )
        }
        when (offer.compliance) {
            "FULL" -> Unit
            "PARTIAL", "DEVIATION" -> throw ConflictException(
                "Offer does not fully comply with technical requirements; " +
                    "a deviation must be resolved, not ignored"
            )
            else -> throw ConflictException(
                "Technical compliance is not confirmed; mark the offer as compliant explicitly"
            )
        }
        if (!offer.documentsComplete) {
            throw ConflictException("Offer documents are not confirmed as complete")
        }
        if (offer.deliveryDays == null) {
            throw ConflictException("Delivery time is unknown; cannot verify the required date")
        }
        if (requiredDate != null &&
            LocalDate.now().plusDays(offer.deliveryDays.toLong()).isAfter(requiredDate)
        ) {
            throw ConflictException(
                "Delivery takes ${offer.deliveryDays} days and misses the required date $requiredDate"
            )
        }
        if (supplier.verificationStatus != "VERIFIED") {
            throw ConflictException(
                "Supplier ${supplier.number} is not verified (${supplier.verificationStatus})"
            )
        }
        if (supplier.archived) {
            throw ConflictException("Supplier is archived")
        }
    }

    private fun buildCard(supplier: SuppliersRecord): SupplierCardDto = SupplierCardDto(
        supplier = supplier.toDto(),
        contacts = supplierRepository.listContacts(supplier.id).map { it.toDto() },
        addresses = supplierRepository.listAddresses(supplier.id).map { it.toDto() },
        categories = supplierRepository.listSupplierCategories(supplier.id).map { it.toDto() },
        documents = supplierRepository.listDocuments(supplier.id).map { it.toDto() }
    )

    private fun buildOffer(offer: SupplierOffersRecord): SupplierOfferDto {
        val totals = supplierRepository.offerTotals(offer.id)
        val supplier = supplierRepository.findSupplierById(offer.supplierId)
        return offer.toDto(totals, supplier?.name, supplierRepository.listOfferLines(offer.id))
    }

    private fun requireSupplier(id: Long): SuppliersRecord =
        supplierRepository.findSupplierById(id) ?: throw NotFoundException("supplier", id)

    private fun requireVersion(supplier: SuppliersRecord, expectedVersion: Long) {
        if (supplier.version != expectedVersion) {
            throw ConflictException("Supplier was modified concurrently, reload the card")
        }
    }

    private fun validateSupplierFields(
        name: String?,
        inn: String?,
        kpp: String?,
        ogrn: String?,
        email: String?
    ) {
        if (name != null && name.isBlank()) {
            throw ValidationException("Supplier name is required", mapOf("name" to "required"))
        }
        inn?.takeIf { it.isNotBlank() }?.let {
            if (!it.matches(Regex("^([0-9]{10}|[0-9]{12})$"))) {
                throw ValidationException(
                    "INN must contain 10 or 12 digits",
                    mapOf("inn" to "format")
                )
            }
        }
        kpp?.takeIf { it.isNotBlank() }?.let {
            if (!it.matches(Regex("^[0-9]{9}$"))) {
                throw ValidationException("KPP must contain 9 digits", mapOf("kpp" to "format"))
            }
        }
        ogrn?.takeIf { it.isNotBlank() }?.let {
            if (!it.matches(Regex("^([0-9]{13}|[0-9]{15})$"))) {
                throw ValidationException("OGRN must contain 13 or 15 digits", mapOf("ogrn" to "format"))
            }
        }
        email?.takeIf { it.isNotBlank() }?.let {
            if (!it.contains("@")) {
                throw ValidationException("Email format is invalid", mapOf("email" to "format"))
            }
        }
    }

    private fun validateCurrency(currency: String) {
        if (!currency.trim().uppercase().matches(Regex("^[A-Z]{3}$"))) {
            throw ValidationException(
                "Currency must be a three-letter code",
                mapOf("currency" to "format")
            )
        }
    }

    private fun validateDelivery(deliveryDays: Int?, deliveryCost: String?) {
        if (deliveryDays != null && deliveryDays < 0) {
            throw ValidationException(
                "Delivery days must not be negative",
                mapOf("deliveryDays" to "negative")
            )
        }
        deliveryCost?.let {
            if (parseQuantity(it, "deliveryCost") < BigDecimal.ZERO) {
                throw ValidationException(
                    "Delivery cost must not be negative",
                    mapOf("deliveryCost" to "negative")
                )
            }
        }
    }

    private fun parseQuantity(value: String, field: String): BigDecimal =
        try {
            BigDecimal(value.trim().replace(" ", "").replace(',', '.')).setScale(4, RoundingMode.HALF_UP)
        } catch (_: NumberFormatException) {
            throw ValidationException("Invalid decimal value", mapOf(field to "format"))
        }

    private fun parseDate(value: String, field: String): LocalDate? =
        try {
            LocalDate.parse(value.trim())
        } catch (_: DateTimeParseException) {
            throw ValidationException("Invalid ISO date", mapOf(field to "format"))
        }

    private fun lineTotal(line: org.example.project.generated.jooq.tables.records.SupplierOfferLinesRecord) =
        line.quantity.multiply(line.unitPrice).setScale(4, RoundingMode.HALF_UP)

    private fun executeIdempotent(
        operation: String,
        idempotencyKey: String?,
        requestPayload: String,
        successStatus: HttpStatusCode,
        currentUser: CurrentUser,
        work: () -> Pair<Long, String>
    ): CommandResponse {
        val key = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException(
                "Idempotency key is required",
                mapOf("idempotencyKey" to "required")
            )
        val requestHash = sha256("$operation|$requestPayload")
        try {
            return dsl.transactionResult(TransactionalCallable {
                val existing = supplierRepository.findIdempotency(currentUser.userId, operation, key)
                if (existing != null) {
                    if (existing.requestHash != requestHash) {
                        throw ConflictException("Idempotency key was already used with a different request")
                    }
                    return@TransactionalCallable CommandResponse(
                        HttpStatusCode.fromValue(existing.responseStatus),
                        existing.responseBody
                    )
                }
                val (entityId, dataJson) = work()
                val body = """{"success":true,"data":$dataJson}"""
                val entityType = if (operation.startsWith("SUPPLIER_OFFER")) "SUPPLIER_OFFER" else "SUPPLIER"
                supplierRepository.insertIdempotency(
                    currentUser.userId, operation, key, requestHash,
                    entityType, entityId, successStatus.value, body
                )
                CommandResponse(successStatus, body)
            })
        } catch (e: org.jooq.exception.DataAccessException) {
            if (e.sqlState() == "23505" && e.message?.contains("uq_supplier_idempotency") == true) {
                val existing = supplierRepository.findIdempotency(currentUser.userId, operation, key)
                if (existing != null && existing.requestHash == requestHash) {
                    return CommandResponse(HttpStatusCode.fromValue(existing.responseStatus), existing.responseBody)
                }
                throw ConflictException("Idempotency key was already used with a different request")
            }
            throw e
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
