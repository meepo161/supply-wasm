package org.example.project.repository

import org.example.project.generated.jooq.tables.DemandLines.DEMAND_LINES
import org.example.project.generated.jooq.tables.Demands.DEMANDS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.SupplierAddresses.SUPPLIER_ADDRESSES
import org.example.project.generated.jooq.tables.SupplierCategories.SUPPLIER_CATEGORIES
import org.example.project.generated.jooq.tables.SupplierCategoryLinks.SUPPLIER_CATEGORY_LINKS
import org.example.project.generated.jooq.tables.SupplierContacts.SUPPLIER_CONTACTS
import org.example.project.generated.jooq.tables.SupplierDocuments.SUPPLIER_DOCUMENTS
import org.example.project.generated.jooq.tables.SupplierIdempotencyKeys.SUPPLIER_IDEMPOTENCY_KEYS
import org.example.project.generated.jooq.tables.SupplierOfferHistory.SUPPLIER_OFFER_HISTORY
import org.example.project.generated.jooq.tables.SupplierOfferLines.SUPPLIER_OFFER_LINES
import org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS
import org.example.project.generated.jooq.tables.Suppliers.SUPPLIERS
import org.example.project.generated.jooq.tables.records.SupplierAddressesRecord
import org.example.project.generated.jooq.tables.records.SupplierCategoriesRecord
import org.example.project.generated.jooq.tables.records.SupplierContactsRecord
import org.example.project.generated.jooq.tables.records.SupplierDocumentsRecord
import org.example.project.generated.jooq.tables.records.SupplierIdempotencyKeysRecord
import org.example.project.generated.jooq.tables.records.SupplierOfferHistoryRecord
import org.example.project.generated.jooq.tables.records.SupplierOfferLinesRecord
import org.example.project.generated.jooq.tables.records.SupplierOffersRecord
import org.example.project.generated.jooq.tables.records.SuppliersRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

class SupplierRepository(private val dsl: DSLContext) {

    data class NewContact(
        val fullName: String,
        val position: String?,
        val email: String?,
        val phone: String?,
        val messenger: String?,
        val isPrimary: Boolean,
        val note: String?
    )

    data class NewAddress(
        val kind: String,
        val address: String,
        val note: String?
    )

    data class NewDocument(
        val kind: String,
        val title: String,
        val documentNo: String?,
        val validFrom: LocalDate?,
        val validTo: LocalDate?,
        val attachmentId: Long?,
        val note: String?
    )

    data class NewOfferLine(
        val lineNo: Int,
        val demandLineId: Long,
        val nomenclatureId: Long,
        val quantity: BigDecimal,
        val unit: String,
        val unitPrice: BigDecimal,
        val vatRate: BigDecimal,
        val note: String?
    )

    // --- Поставщики ---

    fun findSupplierById(id: Long): SuppliersRecord? =
        dsl.selectFrom(SUPPLIERS).where(SUPPLIERS.ID.eq(id)).fetchOne()

    fun lockSupplierById(id: Long): SuppliersRecord? =
        dsl.selectFrom(SUPPLIERS).where(SUPPLIERS.ID.eq(id)).forUpdate().fetchOne()

    fun listSuppliers(
        search: String?,
        status: String?,
        archived: Boolean?
    ): List<SuppliersRecord> {
        val conditions = mutableListOf<Condition>()
        search?.takeIf { it.isNotBlank() }?.let { term ->
            val pattern = "%${term.trim().lowercase()}%"
            conditions += DSL.lower(SUPPLIERS.NAME).like(pattern)
                .or(DSL.lower(SUPPLIERS.NUMBER).like(pattern))
                .or(DSL.lower(DSL.coalesce(SUPPLIERS.INN, "")).like(pattern))
        }
        status?.let { conditions += SUPPLIERS.VERIFICATION_STATUS.eq(it) }
        archived?.let { conditions += SUPPLIERS.ARCHIVED.eq(it) }
        return dsl.selectFrom(SUPPLIERS)
            .where(conditions)
            .orderBy(SUPPLIERS.NAME.asc())
            .fetch()
    }

    // Дубль проверяется по ИНН и по точному названию: ТЗ §17 требует
    // предупреждать о дублях, а не молча создавать вторую карточку.
    fun findDuplicate(inn: String?, name: String, excludeId: Long? = null): SuppliersRecord? {
        val conditions = mutableListOf<Condition>()
        val byInn = inn?.let { SUPPLIERS.INN.eq(it) }
        if (byInn != null) {
            conditions += byInn
        } else {
            conditions += DSL.lower(SUPPLIERS.NAME).eq(name.trim().lowercase())
        }
        excludeId?.let { conditions += SUPPLIERS.ID.ne(it) }
        conditions += SUPPLIERS.ARCHIVED.eq(false)
        return dsl.selectFrom(SUPPLIERS).where(conditions).fetchOne()
    }

    fun generateNumber(): String {
        dsl.execute("SELECT pg_advisory_xact_lock(?)", 20_000_000L + 25)
        val year = LocalDate.now().year
        val count = dsl.fetchCount(
            SUPPLIERS,
            SUPPLIERS.NUMBER.like("SUP-$year-%")
        )
        return "SUP-$year-${(count + 1).toString().padStart(5, '0')}"
    }

    fun insertSupplier(
        number: String,
        name: String,
        fullName: String?,
        kind: String,
        inn: String?,
        kpp: String?,
        ogrn: String?,
        okpo: String?,
        country: String?,
        legalAddress: String?,
        site: String?,
        email: String?,
        phone: String?,
        responsibleId: Long?,
        note: String?,
        createdBy: Long
    ): SuppliersRecord {
        val record = dsl.newRecord(SUPPLIERS)
        record.number = number
        record.name = name
        record.fullName = fullName
        record.kind = kind
        record.inn = inn
        record.kpp = kpp
        record.ogrn = ogrn
        record.okpo = okpo
        record.country = country
        record.legalAddress = legalAddress
        record.site = site
        record.email = email
        record.phone = phone
        record.responsibleId = responsibleId
        record.note = note
        record.createdBy = createdBy
        record.store()
        return findSupplierById(record.id)!!
    }

    fun updateSupplier(
        id: Long,
        expectedVersion: Long,
        name: String?,
        fullName: String?,
        kind: String?,
        inn: String?,
        kpp: String?,
        ogrn: String?,
        okpo: String?,
        country: String?,
        legalAddress: String?,
        site: String?,
        email: String?,
        phone: String?,
        responsibleId: Long?,
        note: String?,
        updatedBy: Long
    ): SuppliersRecord? {
        val set = mutableMapOf<org.jooq.Field<*>, Any?>()
        name?.let { set[SUPPLIERS.NAME] = it }
        fullName?.let { set[SUPPLIERS.FULL_NAME] = it }
        kind?.let { set[SUPPLIERS.KIND] = it }
        inn?.let { set[SUPPLIERS.INN] = it }
        kpp?.let { set[SUPPLIERS.KPP] = it }
        ogrn?.let { set[SUPPLIERS.OGRN] = it }
        okpo?.let { set[SUPPLIERS.OKPO] = it }
        country?.let { set[SUPPLIERS.COUNTRY] = it }
        legalAddress?.let { set[SUPPLIERS.LEGAL_ADDRESS] = it }
        site?.let { set[SUPPLIERS.SITE] = it }
        email?.let { set[SUPPLIERS.EMAIL] = it }
        phone?.let { set[SUPPLIERS.PHONE] = it }
        responsibleId?.let { set[SUPPLIERS.RESPONSIBLE_ID] = it }
        note?.let { set[SUPPLIERS.NOTE] = it }
        set[SUPPLIERS.UPDATED_AT] = OffsetDateTime.now()
        set[SUPPLIERS.UPDATED_BY] = updatedBy
        set[SUPPLIERS.VERSION] = SUPPLIERS.VERSION.plus(1)
        val changed = dsl.update(SUPPLIERS).set(set)
            .where(SUPPLIERS.ID.eq(id).and(SUPPLIERS.VERSION.eq(expectedVersion)))
            .execute()
        if (changed == 0) return null
        return dsl.selectFrom(SUPPLIERS).where(SUPPLIERS.ID.eq(id)).fetchOne()
    }

    fun updateVerification(
        id: Long,
        expectedVersion: Long,
        status: String,
        note: String?,
        verifierId: Long?
    ): SuppliersRecord? {
        val set = mutableMapOf<org.jooq.Field<*>, Any?>()
        set[SUPPLIERS.VERIFICATION_STATUS] = status
        set[SUPPLIERS.VERIFICATION_NOTE] = note
        set[SUPPLIERS.VERIFIED_BY] = verifierId
        set[SUPPLIERS.VERIFIED_AT] = if (verifierId != null) OffsetDateTime.now() else null
        set[SUPPLIERS.UPDATED_AT] = OffsetDateTime.now()
        set[SUPPLIERS.UPDATED_BY] = verifierId
        set[SUPPLIERS.VERSION] = SUPPLIERS.VERSION.plus(1)
        val changed = dsl.update(SUPPLIERS).set(set)
            .where(SUPPLIERS.ID.eq(id).and(SUPPLIERS.VERSION.eq(expectedVersion)))
            .execute()
        if (changed == 0) return null
        return dsl.selectFrom(SUPPLIERS).where(SUPPLIERS.ID.eq(id)).fetchOne()
    }

    fun setArchived(id: Long, expectedVersion: Long, archived: Boolean, updatedBy: Long): SuppliersRecord? {
        val changed = dsl.update(SUPPLIERS)
            .set(SUPPLIERS.ARCHIVED, archived)
            .set(SUPPLIERS.UPDATED_AT, OffsetDateTime.now())
            .set(SUPPLIERS.UPDATED_BY, updatedBy)
            .set(SUPPLIERS.VERSION, SUPPLIERS.VERSION.plus(1))
            .where(SUPPLIERS.ID.eq(id).and(SUPPLIERS.VERSION.eq(expectedVersion)))
            .execute()
        if (changed == 0) return null
        return dsl.selectFrom(SUPPLIERS).where(SUPPLIERS.ID.eq(id)).fetchOne()
    }

    fun touchVersion(id: Long, updatedBy: Long) {
        dsl.update(SUPPLIERS)
            .set(SUPPLIERS.UPDATED_AT, OffsetDateTime.now())
            .set(SUPPLIERS.UPDATED_BY, updatedBy)
            .set(SUPPLIERS.VERSION, SUPPLIERS.VERSION.plus(1))
            .where(SUPPLIERS.ID.eq(id))
            .execute()
    }

    // --- Контакты, адреса, категории, документы ---

    fun listContacts(supplierId: Long): List<SupplierContactsRecord> =
        dsl.selectFrom(SUPPLIER_CONTACTS)
            .where(SUPPLIER_CONTACTS.SUPPLIER_ID.eq(supplierId))
            .orderBy(SUPPLIER_CONTACTS.IS_PRIMARY.desc(), SUPPLIER_CONTACTS.ID.asc())
            .fetch()

    fun insertContact(supplierId: Long, contact: NewContact, createdBy: Long): SupplierContactsRecord {
        val record = dsl.newRecord(SUPPLIER_CONTACTS)
        record.supplierId = supplierId
        record.fullName = contact.fullName
        record.position = contact.position
        record.email = contact.email
        record.phone = contact.phone
        record.messenger = contact.messenger
        record.isPrimary = contact.isPrimary
        record.note = contact.note
        record.createdBy = createdBy
        record.store()
        return record
    }

    fun listAddresses(supplierId: Long): List<SupplierAddressesRecord> =
        dsl.selectFrom(SUPPLIER_ADDRESSES)
            .where(SUPPLIER_ADDRESSES.SUPPLIER_ID.eq(supplierId))
            .orderBy(SUPPLIER_ADDRESSES.ID.asc())
            .fetch()

    fun insertAddress(supplierId: Long, address: NewAddress, createdBy: Long): SupplierAddressesRecord {
        val record = dsl.newRecord(SUPPLIER_ADDRESSES)
        record.supplierId = supplierId
        record.kind = address.kind
        record.address = address.address
        record.note = address.note
        record.createdBy = createdBy
        record.store()
        return record
    }

    fun listCategories(): List<SupplierCategoriesRecord> =
        dsl.selectFrom(SUPPLIER_CATEGORIES).orderBy(SUPPLIER_CATEGORIES.NAME.asc()).fetch()

    fun findCategoryById(id: Long): SupplierCategoriesRecord? =
        dsl.selectFrom(SUPPLIER_CATEGORIES).where(SUPPLIER_CATEGORIES.ID.eq(id)).fetchOne()

    fun findCategoryByCode(code: String): SupplierCategoriesRecord? =
        dsl.selectFrom(SUPPLIER_CATEGORIES).where(SUPPLIER_CATEGORIES.CODE.eq(code)).fetchOne()

    fun insertCategory(code: String, name: String, description: String?, createdBy: Long): SupplierCategoriesRecord {
        val record = dsl.newRecord(SUPPLIER_CATEGORIES)
        record.code = code
        record.name = name
        record.description = description
        record.createdBy = createdBy
        record.store()
        return record
    }

    fun listSupplierCategories(supplierId: Long): List<SupplierCategoriesRecord> =
        dsl.select(SUPPLIER_CATEGORIES.fields().toList())
            .from(SUPPLIER_CATEGORIES)
            .join(SUPPLIER_CATEGORY_LINKS)
            .on(SUPPLIER_CATEGORY_LINKS.CATEGORY_ID.eq(SUPPLIER_CATEGORIES.ID))
            .where(SUPPLIER_CATEGORY_LINKS.SUPPLIER_ID.eq(supplierId))
            .orderBy(SUPPLIER_CATEGORIES.NAME.asc())
            .fetchInto(SupplierCategoriesRecord::class.java)

    fun replaceSupplierCategories(supplierId: Long, categoryIds: List<Long>, createdBy: Long) {
        dsl.deleteFrom(SUPPLIER_CATEGORY_LINKS)
            .where(SUPPLIER_CATEGORY_LINKS.SUPPLIER_ID.eq(supplierId))
            .execute()
        categoryIds.distinct().forEach { categoryId ->
            dsl.insertInto(SUPPLIER_CATEGORY_LINKS)
                .set(SUPPLIER_CATEGORY_LINKS.SUPPLIER_ID, supplierId)
                .set(SUPPLIER_CATEGORY_LINKS.CATEGORY_ID, categoryId)
                .set(SUPPLIER_CATEGORY_LINKS.CREATED_BY, createdBy)
                .execute()
        }
    }

    fun listDocuments(supplierId: Long): List<SupplierDocumentsRecord> =
        dsl.selectFrom(SUPPLIER_DOCUMENTS)
            .where(SUPPLIER_DOCUMENTS.SUPPLIER_ID.eq(supplierId))
            .orderBy(SUPPLIER_DOCUMENTS.ID.desc())
            .fetch()

    fun insertDocument(
        supplierId: Long,
        document: NewDocument,
        createdBy: Long
    ): SupplierDocumentsRecord {
        val record = dsl.newRecord(SUPPLIER_DOCUMENTS)
        record.supplierId = supplierId
        record.kind = document.kind
        record.title = document.title
        record.documentNo = document.documentNo
        record.validFrom = document.validFrom
        record.validTo = document.validTo
        record.attachmentId = document.attachmentId
        record.note = document.note
        record.createdBy = createdBy
        record.store()
        return record
    }

    // --- Предложения ---

    fun generateOfferNumber(): String {
        dsl.execute("SELECT pg_advisory_xact_lock(?)", 20_000_000L + 26)
        val year = LocalDate.now().year
        val count = dsl.fetchCount(
            SUPPLIER_OFFERS,
            SUPPLIER_OFFERS.NUMBER.like("OFF-$year-%")
        )
        return "OFF-$year-${(count + 1).toString().padStart(5, '0')}"
    }

    fun findOfferById(id: Long): SupplierOffersRecord? =
        dsl.selectFrom(SUPPLIER_OFFERS).where(SUPPLIER_OFFERS.ID.eq(id)).fetchOne()

    fun lockOfferById(id: Long): SupplierOffersRecord? =
        dsl.selectFrom(SUPPLIER_OFFERS).where(SUPPLIER_OFFERS.ID.eq(id)).forUpdate().fetchOne()

    fun listOffers(demandId: Long?, supplierId: Long?, status: String?): List<SupplierOffersRecord> {
        val conditions = mutableListOf<Condition>()
        demandId?.let { conditions += SUPPLIER_OFFERS.DEMAND_ID.eq(it) }
        supplierId?.let { conditions += SUPPLIER_OFFERS.SUPPLIER_ID.eq(it) }
        status?.let { conditions += SUPPLIER_OFFERS.STATUS.eq(it) }
        return dsl.selectFrom(SUPPLIER_OFFERS)
            .where(conditions)
            .orderBy(SUPPLIER_OFFERS.ID.desc())
            .fetch()
    }

    fun listOffersForDemand(demandId: Long): List<SupplierOffersRecord> =
        dsl.selectFrom(SUPPLIER_OFFERS)
            .where(SUPPLIER_OFFERS.DEMAND_ID.eq(demandId))
            .orderBy(SUPPLIER_OFFERS.ID.asc())
            .fetch()

    fun insertOffer(
        number: String,
        demandId: Long,
        supplierId: Long,
        currency: String,
        compliance: String,
        complianceNote: String?,
        documentsComplete: Boolean,
        deliveryDays: Int?,
        deliveryTerms: String?,
        deliveryCost: BigDecimal?,
        paymentTerms: String?,
        warrantyMonths: Int?,
        validUntil: LocalDate,
        supplierContactName: String?,
        supplierContactChannel: String?,
        note: String?,
        requestedBy: Long,
        createdBy: Long
    ): SupplierOffersRecord {
        val record = dsl.newRecord(SUPPLIER_OFFERS)
        record.number = number
        record.demandId = demandId
        record.supplierId = supplierId
        record.status = "RECEIVED"
        record.currency = currency
        record.compliance = compliance
        record.complianceNote = complianceNote
        record.documentsComplete = documentsComplete
        record.deliveryDays = deliveryDays
        record.deliveryTerms = deliveryTerms
        record.deliveryCost = deliveryCost
        record.paymentTerms = paymentTerms
        record.warrantyMonths = warrantyMonths
        record.validUntil = validUntil
        record.supplierContactName = supplierContactName
        record.supplierContactChannel = supplierContactChannel
        record.note = note
        record.requestedBy = requestedBy
        record.createdBy = createdBy
        record.store()
        return findOfferById(record.id)!!
    }

    fun replaceOfferLines(offerId: Long, lines: List<NewOfferLine>, createdBy: Long) {
        dsl.deleteFrom(SUPPLIER_OFFER_LINES).where(SUPPLIER_OFFER_LINES.OFFER_ID.eq(offerId)).execute()
        lines.forEach { line ->
            dsl.insertInto(SUPPLIER_OFFER_LINES)
                .set(SUPPLIER_OFFER_LINES.OFFER_ID, offerId)
                .set(SUPPLIER_OFFER_LINES.LINE_NO, line.lineNo)
                .set(SUPPLIER_OFFER_LINES.DEMAND_LINE_ID, line.demandLineId)
                .set(SUPPLIER_OFFER_LINES.NOMENCLATURE_ID, line.nomenclatureId)
                .set(SUPPLIER_OFFER_LINES.QUANTITY, line.quantity)
                .set(SUPPLIER_OFFER_LINES.UNIT, line.unit)
                .set(SUPPLIER_OFFER_LINES.UNIT_PRICE, line.unitPrice)
                .set(SUPPLIER_OFFER_LINES.VAT_RATE, line.vatRate)
                .set(SUPPLIER_OFFER_LINES.NOTE, line.note)
                .set(SUPPLIER_OFFER_LINES.CREATED_BY, createdBy)
                .execute()
        }
    }

    fun listOfferLines(offerId: Long): List<SupplierOfferLinesRecord> =
        dsl.selectFrom(SUPPLIER_OFFER_LINES)
            .where(SUPPLIER_OFFER_LINES.OFFER_ID.eq(offerId))
            .orderBy(SUPPLIER_OFFER_LINES.LINE_NO.asc())
            .fetch()

    fun listOfferLinesForOffers(offerIds: List<Long>): List<SupplierOfferLinesRecord> =
        if (offerIds.isEmpty()) emptyList()
        else dsl.selectFrom(SUPPLIER_OFFER_LINES)
            .where(SUPPLIER_OFFER_LINES.OFFER_ID.`in`(offerIds))
            .orderBy(SUPPLIER_OFFER_LINES.OFFER_ID.asc(), SUPPLIER_OFFER_LINES.LINE_NO.asc())
            .fetch()

    /** Итоги читаются из view, чтобы клиент и сервис считали одинаково. */
    fun offerTotals(offerId: Long): Triple<BigDecimal, BigDecimal, BigDecimal> {
        val record = dsl.fetchOne(
            "SELECT net_total, vat_total, gross_total FROM supplier_offer_totals WHERE offer_id = ?",
            offerId
        )
        val net = record?.get("net_total", BigDecimal::class.java) ?: BigDecimal.ZERO
        val vat = record?.get("vat_total", BigDecimal::class.java) ?: BigDecimal.ZERO
        val gross = record?.get("gross_total", BigDecimal::class.java) ?: BigDecimal.ZERO
        return Triple(net, vat, gross)
    }

    fun offerTotalsBatch(offerIds: List<Long>): Map<Long, Triple<BigDecimal, BigDecimal, BigDecimal>> {
        if (offerIds.isEmpty()) return emptyMap()
        val placeholders = offerIds.joinToString(",") { "?" }
        val result = dsl.fetch(
            "SELECT offer_id, net_total, vat_total, gross_total FROM supplier_offer_totals " +
                "WHERE offer_id IN ($placeholders)",
            *offerIds.toTypedArray()
        )
        return result.associate { record ->
            record.get("offer_id", Long::class.java) to Triple(
                record.get("net_total", BigDecimal::class.java) ?: BigDecimal.ZERO,
                record.get("vat_total", BigDecimal::class.java) ?: BigDecimal.ZERO,
                record.get("gross_total", BigDecimal::class.java) ?: BigDecimal.ZERO
            )
        }
    }

    fun updateOfferStatus(
        id: Long,
        expectedVersion: Long,
        fromStatuses: List<String>,
        newStatus: String,
        selectedBy: Long? = null,
        selectedAt: OffsetDateTime? = null,
        selectionReason: String? = null,
        updatedBy: Long
    ): SupplierOffersRecord? {
        val set = mutableMapOf<org.jooq.Field<*>, Any?>()
        set[SUPPLIER_OFFERS.STATUS] = newStatus
        set[SUPPLIER_OFFERS.UPDATED_AT] = OffsetDateTime.now()
        set[SUPPLIER_OFFERS.UPDATED_BY] = updatedBy
        set[SUPPLIER_OFFERS.VERSION] = SUPPLIER_OFFERS.VERSION.plus(1)
        if (selectedBy != null) {
            set[SUPPLIER_OFFERS.SELECTED_BY] = selectedBy
            set[SUPPLIER_OFFERS.SELECTED_AT] = selectedAt
            set[SUPPLIER_OFFERS.SELECTION_REASON] = selectionReason
        }
        val changed = dsl.update(SUPPLIER_OFFERS).set(set)
            .where(
                SUPPLIER_OFFERS.ID.eq(id)
                    .and(SUPPLIER_OFFERS.VERSION.eq(expectedVersion))
                    .and(SUPPLIER_OFFERS.STATUS.`in`(fromStatuses))
            )
            .execute()
        if (changed == 0) return null
        return dsl.selectFrom(SUPPLIER_OFFERS).where(SUPPLIER_OFFERS.ID.eq(id)).fetchOne()
    }

    fun updateOfferDetails(
        id: Long,
        expectedVersion: Long,
        compliance: String?,
        complianceNote: String?,
        documentsComplete: Boolean?,
        deliveryDays: Int?,
        deliveryTerms: String?,
        deliveryCost: BigDecimal?,
        paymentTerms: String?,
        warrantyMonths: Int?,
        validUntil: LocalDate?,
        note: String?,
        updatedBy: Long
    ): SupplierOffersRecord? {
        val set = mutableMapOf<org.jooq.Field<*>, Any?>()
        compliance?.let { set[SUPPLIER_OFFERS.COMPLIANCE] = it }
        complianceNote?.let { set[SUPPLIER_OFFERS.COMPLIANCE_NOTE] = it }
        documentsComplete?.let { set[SUPPLIER_OFFERS.DOCUMENTS_COMPLETE] = it }
        deliveryDays?.let { set[SUPPLIER_OFFERS.DELIVERY_DAYS] = it }
        deliveryTerms?.let { set[SUPPLIER_OFFERS.DELIVERY_TERMS] = it }
        deliveryCost?.let { set[SUPPLIER_OFFERS.DELIVERY_COST] = it }
        paymentTerms?.let { set[SUPPLIER_OFFERS.PAYMENT_TERMS] = it }
        warrantyMonths?.let { set[SUPPLIER_OFFERS.WARRANTY_MONTHS] = it }
        validUntil?.let { set[SUPPLIER_OFFERS.VALID_UNTIL] = it }
        note?.let { set[SUPPLIER_OFFERS.NOTE] = it }
        set[SUPPLIER_OFFERS.UPDATED_AT] = OffsetDateTime.now()
        set[SUPPLIER_OFFERS.UPDATED_BY] = updatedBy
        set[SUPPLIER_OFFERS.VERSION] = SUPPLIER_OFFERS.VERSION.plus(1)
        val changed = dsl.update(SUPPLIER_OFFERS).set(set)
            .where(SUPPLIER_OFFERS.ID.eq(id).and(SUPPLIER_OFFERS.VERSION.eq(expectedVersion)))
            .execute()
        if (changed == 0) return null
        return dsl.selectFrom(SUPPLIER_OFFERS).where(SUPPLIER_OFFERS.ID.eq(id)).fetchOne()
    }

    fun insertOfferHistory(
        offerId: Long,
        action: String,
        fromStatus: String?,
        toStatus: String?,
        reason: String?,
        changedBy: Long
    ) {
        val record = dsl.newRecord(SUPPLIER_OFFER_HISTORY)
        record.offerId = offerId
        record.action = action
        record.fromStatus = fromStatus
        record.toStatus = toStatus
        record.reason = reason
        record.changedBy = changedBy
        record.store()
    }

    fun listOfferHistory(offerId: Long): List<SupplierOfferHistoryRecord> =
        dsl.selectFrom(SUPPLIER_OFFER_HISTORY)
            .where(SUPPLIER_OFFER_HISTORY.OFFER_ID.eq(offerId))
            .orderBy(SUPPLIER_OFFER_HISTORY.ID.asc())
            .fetch()

    // --- Данные потребности для предложений ---

    fun findDemandById(demandId: Long) = dsl.selectFrom(DEMANDS)
        .where(DEMANDS.ID.eq(demandId))
        .fetchOne()

    fun lockDemandById(demandId: Long) = dsl.selectFrom(DEMANDS)
        .where(DEMANDS.ID.eq(demandId))
        .forUpdate()
        .fetchOne()

    fun findDemandLineById(lineId: Long) =
        dsl.selectFrom(DEMAND_LINES).where(DEMAND_LINES.ID.eq(lineId)).fetchOne()

    fun listDemandLines(demandId: Long) =
        dsl.selectFrom(DEMAND_LINES)
            .where(DEMAND_LINES.DEMAND_ID.eq(demandId))
            .orderBy(DEMAND_LINES.LINE_NO.asc())
            .fetch()

    fun findNomenclatureById(id: Long) =
        dsl.selectFrom(NOMENCLATURE).where(NOMENCLATURE.ID.eq(id)).fetchOne()

    // --- Идемпотентность ---

    fun findIdempotency(
        userId: Long,
        operation: String,
        key: String
    ): SupplierIdempotencyKeysRecord? =
        dsl.selectFrom(SUPPLIER_IDEMPOTENCY_KEYS)
            .where(
                SUPPLIER_IDEMPOTENCY_KEYS.USER_ID.eq(userId)
                    .and(SUPPLIER_IDEMPOTENCY_KEYS.OPERATION.eq(operation))
                    .and(SUPPLIER_IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY.eq(key))
            )
            .fetchOne()

    fun insertIdempotency(
        userId: Long,
        operation: String,
        key: String,
        requestHash: String,
        entityType: String,
        entityId: Long,
        responseStatus: Int,
        responseBody: String
    ) {
        dsl.insertInto(SUPPLIER_IDEMPOTENCY_KEYS)
            .set(SUPPLIER_IDEMPOTENCY_KEYS.USER_ID, userId)
            .set(SUPPLIER_IDEMPOTENCY_KEYS.OPERATION, operation)
            .set(SUPPLIER_IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY, key)
            .set(SUPPLIER_IDEMPOTENCY_KEYS.REQUEST_HASH, requestHash)
            .set(SUPPLIER_IDEMPOTENCY_KEYS.ENTITY_TYPE, entityType)
            .set(SUPPLIER_IDEMPOTENCY_KEYS.ENTITY_ID, entityId)
            .set(SUPPLIER_IDEMPOTENCY_KEYS.RESPONSE_STATUS, responseStatus)
            .set(SUPPLIER_IDEMPOTENCY_KEYS.RESPONSE_BODY, responseBody)
            .execute()
    }
}
