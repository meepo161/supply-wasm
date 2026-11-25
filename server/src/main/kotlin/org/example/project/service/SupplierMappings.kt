package org.example.project.service

import org.example.project.dto.SupplierAddressDto
import org.example.project.dto.SupplierAddressKind
import org.example.project.dto.SupplierCategoryDto
import org.example.project.dto.SupplierContactDto
import org.example.project.dto.SupplierDocumentDto
import org.example.project.dto.SupplierDocumentKind
import org.example.project.dto.SupplierDocumentInput
import org.example.project.dto.SupplierDto
import org.example.project.dto.SupplierKind
import org.example.project.dto.SupplierOfferCompliance
import org.example.project.dto.SupplierOfferDto
import org.example.project.dto.SupplierOfferLineDto
import org.example.project.dto.SupplierOfferStatus
import org.example.project.dto.SupplierOfferTotalsDto
import org.example.project.dto.SupplierVerificationStatus
import org.example.project.generated.jooq.tables.records.SupplierAddressesRecord
import org.example.project.generated.jooq.tables.records.SupplierCategoriesRecord
import org.example.project.generated.jooq.tables.records.SupplierContactsRecord
import org.example.project.generated.jooq.tables.records.SupplierDocumentsRecord
import org.example.project.generated.jooq.tables.records.SupplierOfferLinesRecord
import org.example.project.generated.jooq.tables.records.SupplierOffersRecord
import org.example.project.generated.jooq.tables.records.SuppliersRecord
import org.example.project.repository.SupplierRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal fun SuppliersRecord.toDto(): SupplierDto = SupplierDto(
    id = this.id,
    number = this.number,
    name = this.name,
    fullName = this.fullName,
    kind = SupplierKind.valueOf(this.kind),
    inn = this.inn,
    kpp = this.kpp,
    ogrn = this.ogrn,
    okpo = this.okpo,
    country = this.country,
    legalAddress = this.legalAddress,
    site = this.site,
    email = this.email,
    phone = this.phone,
    responsibleId = this.responsibleId,
    verificationStatus = SupplierVerificationStatus.valueOf(this.verificationStatus),
    verificationNote = this.verificationNote,
    verifiedBy = this.verifiedBy,
    verifiedAt = this.verifiedAt?.toString(),
    note = this.note,
    archived = this.archived,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    updatedAt = this.updatedAt?.toString(),
    updatedBy = this.updatedBy,
    version = this.version
)

internal fun SupplierContactsRecord.toDto(): SupplierContactDto = SupplierContactDto(
    id = this.id,
    fullName = this.fullName,
    position = this.position,
    email = this.email,
    phone = this.phone,
    messenger = this.messenger,
    isPrimary = this.isPrimary,
    note = this.note
)

internal fun SupplierAddressesRecord.toDto(): SupplierAddressDto = SupplierAddressDto(
    id = this.id,
    kind = SupplierAddressKind.valueOf(this.kind),
    address = this.address,
    note = this.note
)

internal fun SupplierCategoriesRecord.toDto(): SupplierCategoryDto = SupplierCategoryDto(
    id = this.id,
    code = this.code,
    name = this.name,
    description = this.description
)

internal fun SupplierDocumentsRecord.toDto(): SupplierDocumentDto = SupplierDocumentDto(
    id = this.id,
    kind = SupplierDocumentKind.valueOf(this.kind),
    title = this.title,
    documentNo = this.documentNo,
    validFrom = this.validFrom?.toString(),
    validTo = this.validTo?.toString(),
    attachmentId = this.attachmentId,
    note = this.note
)

internal fun SupplierDocumentInput.toNewDocument(): SupplierRepository.NewDocument =
    SupplierRepository.NewDocument(
        kind = this.kind.name,
        title = this.title.trim(),
        documentNo = this.documentNo?.trim(),
        validFrom = this.validFrom?.let { parseSupplierDate(it, "validFrom") },
        validTo = this.validTo?.let { parseSupplierDate(it, "validTo") },
        attachmentId = this.attachmentId,
        note = this.note?.trim()
    )

private fun parseSupplierDate(value: String, field: String): LocalDate? {
    if (value.isBlank()) return null
    return try {
        LocalDate.parse(value.trim())
    } catch (_: DateTimeParseException) {
        throw org.example.project.exception.ValidationException(
            "Invalid ISO date",
            mapOf(field to "format")
        )
    }
}

internal fun SupplierOfferLinesRecord.toDto(
    nomenclatureCode: String? = null,
    nomenclatureName: String? = null
): SupplierOfferLineDto = SupplierOfferLineDto(
    id = this.id,
    lineNo = this.lineNo,
    demandLineId = this.demandLineId,
    nomenclatureId = this.nomenclatureId,
    quantity = this.quantity.toPlainString(),
    unit = this.unit,
    unitPrice = this.unitPrice.toPlainString(),
    vatRate = this.vatRate.toPlainString(),
    lineTotal = this.quantity.multiply(this.unitPrice).toPlainString(),
    note = this.note,
    nomenclatureCode = nomenclatureCode,
    nomenclatureName = nomenclatureName
)

internal fun SupplierOffersRecord.toDto(
    totals: Triple<BigDecimal, BigDecimal, BigDecimal>?,
    supplierName: String?,
    lines: List<SupplierOfferLinesRecord> = emptyList(),
    nomenclature: Map<Long, Pair<String?, String?>> = emptyMap(),
    invoiceFileName: String? = null
): SupplierOfferDto = SupplierOfferDto(
    id = this.id,
    number = this.number,
    demandId = this.demandId,
    supplierId = this.supplierId,
    supplierName = supplierName,
    status = SupplierOfferStatus.valueOf(this.status),
    currency = this.currency,
    compliance = SupplierOfferCompliance.valueOf(this.compliance),
    complianceNote = this.complianceNote,
    documentsComplete = this.documentsComplete,
    deliveryDays = this.deliveryDays,
    deliveryTerms = this.deliveryTerms,
    deliveryCost = this.deliveryCost?.toPlainString(),
    paymentTerms = this.paymentTerms,
    warrantyMonths = this.warrantyMonths,
    validUntil = this.validUntil.toString(),
    validUntilPassed = this.validUntil.isBefore(LocalDate.now()),
    supplierContactName = this.supplierContactName,
    supplierContactChannel = this.supplierContactChannel,
    note = this.note,
    requestedBy = this.requestedBy,
    requestedAt = this.requestedAt?.toString(),
    selectedBy = this.selectedBy,
    selectedAt = this.selectedAt?.toString(),
    selectionReason = this.selectionReason,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version,
    lines = lines.map { line ->
        val item = nomenclature[line.nomenclatureId]
        line.toDto(item?.first, item?.second)
    },
    totals = totals?.let { SupplierOfferTotalsDto(it.first.toPlainString(), it.second.toPlainString(), it.third.toPlainString()) },
    invoiceDocumentId = this.invoiceDocumentId,
    invoiceNumber = this.invoiceNumber,
    invoiceDate = this.invoiceDate?.toString(),
    invoiceFileName = invoiceFileName,
    revisionNote = this.revisionNote
)
