package org.example.project.ui.supplier

import org.example.project.data.AttachmentFileData
import org.example.project.dto.ApiError
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.SupplierCardDto
import org.example.project.dto.SupplierCategoryDto
import org.example.project.dto.SupplierClaimDto
import org.example.project.dto.SupplierClaimType
import org.example.project.dto.SupplierDto
import org.example.project.dto.SupplierKind
import org.example.project.dto.SupplierOfferComparisonDto
import org.example.project.dto.SupplierOfferCompliance
import org.example.project.dto.SupplierOfferDto
import org.example.project.dto.SupplierVerificationStatus
import org.example.project.dto.UserDto

data class SupplierUiState(
    val suppliers: List<SupplierDto> = emptyList(),
    val selectedCard: SupplierCardDto? = null,
    val categories: List<SupplierCategoryDto> = emptyList(),
    val users: List<UserDto> = emptyList(),
    val demands: List<DemandHeaderDto> = emptyList(),
    val nomenclature: List<NomenclatureDto> = emptyList(),
    val offers: List<SupplierOfferDto> = emptyList(),
    val claims: List<SupplierClaimDto> = emptyList(),
    val comparison: SupplierOfferComparisonDto? = null,
    val search: String = "",
    val statusFilter: SupplierVerificationStatus? = null,
    val showArchived: Boolean = false,
    val selectedTab: SupplierTab = SupplierTab.SUPPLIERS,
    val isSuppliersLoading: Boolean = false,
    val isCardLoading: Boolean = false,
    val isOffersLoading: Boolean = false,
    val isClaimsLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val operationError: ApiError? = null,
    val successMessage: String? = null,
    val activeDialog: SupplierDialog? = null,
    val createForm: CreateSupplierForm = CreateSupplierForm(),
    val editForm: EditSupplierForm? = null,
    val contactForm: ContactForm = ContactForm(),
    val addressForm: AddressForm = AddressForm(),
    val documentForm: DocumentForm = DocumentForm(),
    val categorySelection: Set<Long> = emptySet(),
    val offerForm: CreateOfferForm = CreateOfferForm(),
    val claimForm: CreateClaimForm = CreateClaimForm(),
    val selectionReason: String = "",
    val verificationNote: String = "",
    val pendingSelectionOfferId: Long? = null,
    val comparisonDemandId: Long? = null,
    /** Фильтр списка предложений по потребности (выбирается в списке, не строкой таблицы). */
    val offersDemandFilter: Long? = null,
    val editOfferForm: EditOfferForm? = null
)

enum class SupplierTab(val title: String) {
    SUPPLIERS("Поставщики"),
    OFFERS("Предложения"),
    CLAIMS("Рекламации")
}

data class CreateClaimForm(
    val supplierId: Long? = null,
    val claimType: SupplierClaimType = SupplierClaimType.DEFECT,
    val description: String = "",
    val claimAmount: String = "0"
)

sealed class SupplierDialog {
    data object CreateSupplier : SupplierDialog()
    data object EditSupplier : SupplierDialog()
    data object AddContact : SupplierDialog()
    data object AddAddress : SupplierDialog()
    data object AddDocument : SupplierDialog()
    data object EditCategories : SupplierDialog()
    data object Verify : SupplierDialog()
    data object CreateOffer : SupplierDialog()
    data object EditOffer : SupplierDialog()
    data class SelectOffer(val offerId: Long, val offerNumber: String) : SupplierDialog()
    data object CreateClaim : SupplierDialog()
}

data class CreateSupplierForm(
    val name: String = "",
    val fullName: String = "",
    val kind: SupplierKind = SupplierKind.SUPPLIER,
    val inn: String = "",
    val kpp: String = "",
    val ogrn: String = "",
    val country: String = "Россия",
    val legalAddress: String = "",
    val site: String = "",
    val email: String = "",
    val phone: String = "",
    val responsibleId: Long? = null,
    val note: String = "",
    val contactName: String = "",
    val contactPosition: String = "",
    val contactEmail: String = "",
    val contactPhone: String = ""
)

data class EditSupplierForm(
    val supplierId: Long,
    val version: Long,
    val name: String = "",
    val fullName: String = "",
    val kind: SupplierKind = SupplierKind.SUPPLIER,
    val inn: String = "",
    val kpp: String = "",
    val ogrn: String = "",
    val country: String = "",
    val legalAddress: String = "",
    val site: String = "",
    val email: String = "",
    val phone: String = "",
    val note: String = ""
)

data class ContactForm(
    val fullName: String = "",
    val position: String = "",
    val email: String = "",
    val phone: String = "",
    val messenger: String = "",
    val isPrimary: Boolean = false
)

data class AddressForm(
    val kind: String = "LEGAL",
    val address: String = "",
    val note: String = ""
)

data class DocumentForm(
    val kind: String = "CONTRACT",
    val title: String = "",
    val documentNo: String = "",
    val validFrom: String = "",
    val validTo: String = "",
    val note: String = ""
)

data class CreateOfferForm(
    val demandId: Long? = null,
    /** Позиции потребности загружаются с сервера, а не задаются в UI вручную. */
    val demandLines: List<DemandLineOption> = emptyList(),
    val supplierId: Long? = null,
    val currency: String = "RUB",
    val compliance: SupplierOfferCompliance = SupplierOfferCompliance.FULL,
    val complianceNote: String = "",
    val documentsComplete: Boolean = true,
    val deliveryDays: String = "",
    val deliveryTerms: String = "",
    val deliveryCost: String = "",
    val paymentTerms: String = "",
    val warrantyMonths: String = "",
    val validUntil: String = "",
    val supplierContactName: String = "",
    val supplierContactChannel: String = "",
    val note: String = "",
    val pendingInvoiceFile: AttachmentFileData? = null,
    val invoiceDocumentId: Long? = null,
    val invoiceDocumentFileName: String? = null,
    val invoiceNumber: String = "",
    val invoiceDate: String = "",
    val lines: List<OfferLineForm> = listOf(OfferLineForm())
) {
    val hasInvoiceFile: Boolean get() = pendingInvoiceFile != null || invoiceDocumentId != null
    val invoiceFileLabel: String? get() = pendingInvoiceFile?.fileName ?: invoiceDocumentFileName
}

data class DemandLineOption(
    val demandLineId: Long,
    val nomenclatureId: Long,
    val label: String,
    val deficit: String
)

/** Строка предложения: количество и цена вводятся строками, как и на сервере. */
data class OfferLineForm(
    val demandLineId: Long? = null,
    val quantity: String = "",
    val unitPrice: String = "",
    val vatRate: String = "20"
)

/** Правка внесённого предложения (S-04): те же поля, что при вводе, кроме поставщика и потребности. */
data class EditOfferForm(
    val offerId: Long,
    val version: Long,
    val number: String,
    val supplierName: String,
    val compliance: SupplierOfferCompliance = SupplierOfferCompliance.UNKNOWN,
    val documentsComplete: Boolean = false,
    val deliveryDays: String = "",
    val deliveryTerms: String = "",
    val paymentTerms: String = "",
    val warrantyMonths: String = "",
    val validUntil: String = "",
    val note: String = "",
    val lines: List<EditOfferLineForm> = emptyList()
)

data class EditOfferLineForm(
    val demandLineId: Long,
    val label: String,
    val unit: String,
    val quantity: String,
    val unitPrice: String,
    val vatRate: String
)
