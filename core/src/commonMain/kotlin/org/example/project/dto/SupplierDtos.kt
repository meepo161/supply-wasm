package org.example.project.dto

import kotlinx.serialization.Serializable

// Поставщики и коммерческие предложения (ТЗ §9.5–9.8, §17; D-27, D-28).
// Денежные суммы и количества — строки: NUMERIC(19,4) нельзя терять в Double.

@Serializable
enum class SupplierKind {
    SUPPLIER,
    CONTRACTOR,
    MANUFACTURER
}

/** Статус проверки контрагента (§17: «статус проверки» в карточке). */
@Serializable
enum class SupplierVerificationStatus {
    NOT_VERIFIED,
    IN_REVIEW,
    VERIFIED,
    REJECTED
}

@Serializable
enum class SupplierAddressKind {
    LEGAL,
    ACTUAL,
    POSTAL,
    WAREHOUSE,
    DELIVERY
}

@Serializable
enum class SupplierDocumentKind {
    CONTRACT,
    CERTIFICATE,
    LICENSE,
    AGREEMENT,
    OTHER
}

@Serializable
data class SupplierContactDto(
    val id: Long,
    val fullName: String,
    val position: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val messenger: String? = null,
    val isPrimary: Boolean = false,
    val note: String? = null
)

@Serializable
data class SupplierContactInput(
    val fullName: String,
    val position: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val messenger: String? = null,
    val isPrimary: Boolean = false,
    val note: String? = null
)

@Serializable
data class SupplierAddressDto(
    val id: Long,
    val kind: SupplierAddressKind,
    val address: String,
    val note: String? = null
)

@Serializable
data class SupplierAddressInput(
    val kind: SupplierAddressKind = SupplierAddressKind.LEGAL,
    val address: String,
    val note: String? = null
)

@Serializable
data class SupplierCategoryDto(
    val id: Long,
    val code: String,
    val name: String,
    val description: String? = null
)

@Serializable
data class CreateSupplierCategoryRequest(
    val code: String,
    val name: String,
    val description: String? = null
)

@Serializable
data class SupplierDocumentDto(
    val id: Long,
    val kind: SupplierDocumentKind,
    val title: String,
    val documentNo: String? = null,
    val validFrom: String? = null,
    val validTo: String? = null,
    val attachmentId: Long? = null,
    val note: String? = null
)

@Serializable
data class SupplierDocumentInput(
    val kind: SupplierDocumentKind,
    val title: String,
    val documentNo: String? = null,
    val validFrom: String? = null,
    val validTo: String? = null,
    val attachmentId: Long? = null,
    val note: String? = null
)

@Serializable
data class SupplierDto(
    val id: Long,
    val number: String,
    val name: String,
    val fullName: String? = null,
    val kind: SupplierKind = SupplierKind.SUPPLIER,
    val inn: String? = null,
    val kpp: String? = null,
    val ogrn: String? = null,
    val okpo: String? = null,
    val country: String? = null,
    val legalAddress: String? = null,
    val site: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val responsibleId: Long? = null,
    val verificationStatus: SupplierVerificationStatus = SupplierVerificationStatus.NOT_VERIFIED,
    val verificationNote: String? = null,
    val verifiedBy: Long? = null,
    val verifiedAt: String? = null,
    val note: String? = null,
    val archived: Boolean = false,
    val createdAt: String? = null,
    val createdBy: Long,
    val updatedAt: String? = null,
    val updatedBy: Long? = null,
    val version: Long = 0
)

/** Карточка поставщика целиком: реквизиты, контакты, адреса, категории, документы. */
@Serializable
data class SupplierCardDto(
    val supplier: SupplierDto,
    val contacts: List<SupplierContactDto> = emptyList(),
    val addresses: List<SupplierAddressDto> = emptyList(),
    val categories: List<SupplierCategoryDto> = emptyList(),
    val documents: List<SupplierDocumentDto> = emptyList()
)

@Serializable
data class CreateSupplierRequest(
    val name: String,
    val fullName: String? = null,
    val kind: SupplierKind = SupplierKind.SUPPLIER,
    val inn: String? = null,
    val kpp: String? = null,
    val ogrn: String? = null,
    val okpo: String? = null,
    val country: String? = null,
    val legalAddress: String? = null,
    val site: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val responsibleId: Long? = null,
    val note: String? = null,
    val contacts: List<SupplierContactInput> = emptyList(),
    val addresses: List<SupplierAddressInput> = emptyList(),
    val categoryIds: List<Long> = emptyList(),
    val documents: List<SupplierDocumentInput> = emptyList(),
    val idempotencyKey: String? = null
)

@Serializable
data class UpdateSupplierRequest(
    val name: String? = null,
    val fullName: String? = null,
    val kind: SupplierKind? = null,
    val inn: String? = null,
    val kpp: String? = null,
    val ogrn: String? = null,
    val okpo: String? = null,
    val country: String? = null,
    val legalAddress: String? = null,
    val site: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val responsibleId: Long? = null,
    val note: String? = null,
    val version: Long
)

@Serializable
data class SupplierVerificationRequest(
    val status: SupplierVerificationStatus,
    val note: String? = null,
    val version: Long,
    val idempotencyKey: String? = null
)

@Serializable
data class AddSupplierContactRequest(
    val contact: SupplierContactInput,
    val version: Long
)

@Serializable
data class AddSupplierAddressRequest(
    val address: SupplierAddressInput,
    val version: Long
)

@Serializable
data class AddSupplierDocumentRequest(
    val document: SupplierDocumentInput,
    val version: Long
)

@Serializable
data class SetSupplierCategoriesRequest(
    val categoryIds: List<Long>,
    val version: Long
)

// ---------------------------------------------------------------------------
// Предложения поставщиков
// ---------------------------------------------------------------------------

@Serializable
enum class SupplierOfferStatus {
    RECEIVED,
    SHORTLISTED,
    SELECTED,
    REJECTED,
    WITHDRAWN,
    EXPIRED
}

/** Соответствие техническим требованиям потребности (§9.6). */
@Serializable
enum class SupplierOfferCompliance {
    UNKNOWN,
    FULL,
    PARTIAL,
    DEVIATION
}

@Serializable
data class SupplierOfferLineDto(
    val id: Long,
    val lineNo: Int,
    val demandLineId: Long,
    val nomenclatureId: Long,
    val quantity: String,
    val unit: String,
    val unitPrice: String,
    val vatRate: String,
    val lineTotal: String,
    val note: String? = null
)

/** Итоги считает сервер (view supplier_offer_totals), клиент их не пересчитывает. */
@Serializable
data class SupplierOfferTotalsDto(
    val netTotal: String,
    val vatTotal: String,
    val grossTotal: String
)

@Serializable
data class SupplierOfferDto(
    val id: Long,
    val number: String,
    val demandId: Long,
    val supplierId: Long,
    val supplierName: String? = null,
    val status: SupplierOfferStatus = SupplierOfferStatus.RECEIVED,
    val currency: String = "RUB",
    val compliance: SupplierOfferCompliance = SupplierOfferCompliance.UNKNOWN,
    val complianceNote: String? = null,
    val documentsComplete: Boolean = false,
    val deliveryDays: Int? = null,
    val deliveryTerms: String? = null,
    val deliveryCost: String? = null,
    val paymentTerms: String? = null,
    val warrantyMonths: Int? = null,
    val validUntil: String,
    val validUntilPassed: Boolean = false,
    val supplierContactName: String? = null,
    val supplierContactChannel: String? = null,
    val note: String? = null,
    val requestedBy: Long,
    val requestedAt: String? = null,
    val selectedBy: Long? = null,
    val selectedAt: String? = null,
    val selectionReason: String? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val lines: List<SupplierOfferLineDto> = emptyList(),
    val totals: SupplierOfferTotalsDto? = null
)

@Serializable
data class SupplierOfferLineInput(
    val demandLineId: Long,
    val quantity: String,
    val unitPrice: String,
    val vatRate: String = "20",
    val note: String? = null
)

@Serializable
data class CreateSupplierOfferRequest(
    val demandId: Long,
    val supplierId: Long,
    val currency: String = "RUB",
    val compliance: SupplierOfferCompliance = SupplierOfferCompliance.UNKNOWN,
    val complianceNote: String? = null,
    val documentsComplete: Boolean = false,
    val deliveryDays: Int? = null,
    val deliveryTerms: String? = null,
    val deliveryCost: String? = null,
    val paymentTerms: String? = null,
    val warrantyMonths: Int? = null,
    val validUntil: String,
    val supplierContactName: String? = null,
    val supplierContactChannel: String? = null,
    val note: String? = null,
    val lines: List<SupplierOfferLineInput>,
    val idempotencyKey: String? = null
)

@Serializable
data class UpdateSupplierOfferRequest(
    val compliance: SupplierOfferCompliance? = null,
    val complianceNote: String? = null,
    val documentsComplete: Boolean? = null,
    val deliveryDays: Int? = null,
    val deliveryTerms: String? = null,
    val deliveryCost: String? = null,
    val paymentTerms: String? = null,
    val warrantyMonths: Int? = null,
    val validUntil: String? = null,
    val note: String? = null,
    val lines: List<SupplierOfferLineInput>? = null,
    val version: Long
)

/**
 * Выбор предпочтительного предложения инициатором (D-28).
 * Обоснование обязательно: выбор не может быть «просто минимальная цена».
 */
@Serializable
data class SelectSupplierOfferRequest(
    val reason: String,
    val version: Long,
    val idempotencyKey: String? = null
)

@Serializable
data class SupplierOfferStatusRequest(
    val status: SupplierOfferStatus,
    val reason: String? = null,
    val version: Long,
    val idempotencyKey: String? = null
)

/**
 * Строка сравнения предложений по одной позиции потребности.
 * null/пустое значение означает «поставщик это не указал» — это факт, а не ноль.
 */
@Serializable
data class SupplierOfferComparisonLineDto(
    val demandLineId: Long,
    val nomenclatureId: Long,
    val nomenclatureCode: String? = null,
    val nomenclatureName: String? = null,
    val requiredQuantity: String,
    val offeredQuantity: String? = null,
    val unitPrice: String? = null,
    val lineTotal: String? = null
)

@Serializable
data class SupplierOfferComparisonColumnDto(
    val offerId: Long,
    val number: String,
    val supplierId: Long,
    val supplierName: String,
    val status: SupplierOfferStatus,
    val currency: String,
    val compliance: SupplierOfferCompliance,
    val documentsComplete: Boolean,
    val deliveryDays: Int? = null,
    val paymentTerms: String? = null,
    val warrantyMonths: Int? = null,
    val validUntil: String,
    val validUntilPassed: Boolean,
    val netTotal: String,
    val grossTotal: String,
    val supplierVerified: Boolean,
    val lines: List<SupplierOfferComparisonLineDto> = emptyList()
)

/** Предупреждения сравнения: то, что нельзя игнорировать при выборе. */
@Serializable
enum class SupplierOfferWarningType {
    EXPIRED,
    NOT_COMPLIANT,
    COMPLIANCE_UNKNOWN,
    DOCUMENTS_MISSING,
    SUPPLIER_NOT_VERIFIED,
    INCOMPLETE_COVERAGE,
    DELIVERY_UNKNOWN,
    DELIVERY_TOO_LONG
}

@Serializable
data class SupplierOfferWarningDto(
    val offerId: Long,
    val type: SupplierOfferWarningType,
    val message: String
)

/**
 * Сводка сравнения. cheapestOfferId честно отделено от recommendedOfferId:
 * рекомендация учитывает соответствие, документы, срок действия и срок поставки,
 * а не только цену.
 */
@Serializable
data class SupplierOfferComparisonDto(
    val demandId: Long,
    val demandNumber: String,
    val demandStatus: String,
    val requiredDate: String? = null,
    val columns: List<SupplierOfferComparisonColumnDto> = emptyList(),
    val warnings: List<SupplierOfferWarningDto> = emptyList(),
    val cheapestOfferId: Long? = null,
    val recommendedOfferId: Long? = null,
    val selectedOfferId: Long? = null,
    val canSelect: Boolean = false,
    val selectionBlockedReason: String? = null
)

@Serializable
data class SupplierOfferHistoryDto(
    val id: Long,
    val action: String,
    val fromStatus: String? = null,
    val toStatus: String? = null,
    val reason: String? = null,
    val changedBy: Long,
    val changedAt: String? = null
)
