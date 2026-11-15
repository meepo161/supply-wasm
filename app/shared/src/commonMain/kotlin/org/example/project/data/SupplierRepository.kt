package org.example.project.data

import org.example.project.dto.AddSupplierAddressRequest
import org.example.project.dto.AddSupplierContactRequest
import org.example.project.dto.AddSupplierDocumentRequest
import org.example.project.dto.CreateSupplierCategoryRequest
import org.example.project.dto.CreateSupplierOfferRequest
import org.example.project.dto.CreateSupplierRequest
import org.example.project.dto.SelectSupplierOfferRequest
import org.example.project.dto.SetSupplierCategoriesRequest
import org.example.project.dto.SupplierCardDto
import org.example.project.dto.SupplierCategoryDto
import org.example.project.dto.SupplierDto
import org.example.project.dto.SupplierOfferComparisonDto
import org.example.project.dto.SupplierOfferDto
import org.example.project.dto.SupplierOfferHistoryDto
import org.example.project.dto.SupplierOfferStatus
import org.example.project.dto.SupplierOfferStatusRequest
import org.example.project.dto.SupplierVerificationRequest
import org.example.project.dto.SupplierVerificationStatus
import org.example.project.dto.UpdateSupplierOfferRequest
import org.example.project.dto.UpdateSupplierRequest

interface SupplierRepository {
    suspend fun listSuppliers(
        search: String? = null,
        verificationStatus: SupplierVerificationStatus? = null,
        archived: Boolean? = null
    ): ApiResult<List<SupplierDto>>

    suspend fun getSupplier(id: Long): ApiResult<SupplierCardDto>
    suspend fun createSupplier(request: CreateSupplierRequest): ApiResult<SupplierCardDto>
    suspend fun updateSupplier(id: Long, request: UpdateSupplierRequest): ApiResult<SupplierDto>
    suspend fun verifySupplier(id: Long, request: SupplierVerificationRequest): ApiResult<SupplierDto>
    suspend fun archiveSupplier(id: Long, version: Long, archived: Boolean): ApiResult<SupplierDto>
    suspend fun addContact(id: Long, request: AddSupplierContactRequest): ApiResult<SupplierCardDto>
    suspend fun addAddress(id: Long, request: AddSupplierAddressRequest): ApiResult<SupplierCardDto>
    suspend fun addDocument(id: Long, request: AddSupplierDocumentRequest): ApiResult<SupplierCardDto>
    suspend fun setCategories(id: Long, request: SetSupplierCategoriesRequest): ApiResult<SupplierCardDto>

    suspend fun listCategories(): ApiResult<List<SupplierCategoryDto>>
    suspend fun createCategory(request: CreateSupplierCategoryRequest): ApiResult<SupplierCategoryDto>

    suspend fun listOffers(
        demandId: Long? = null,
        supplierId: Long? = null,
        status: SupplierOfferStatus? = null
    ): ApiResult<List<SupplierOfferDto>>

    suspend fun getOffer(id: Long): ApiResult<SupplierOfferDto>
    suspend fun createOffer(request: CreateSupplierOfferRequest): ApiResult<SupplierOfferDto>
    suspend fun updateOffer(id: Long, request: UpdateSupplierOfferRequest): ApiResult<SupplierOfferDto>
    suspend fun changeOfferStatus(id: Long, request: SupplierOfferStatusRequest): ApiResult<SupplierOfferDto>
    suspend fun selectOffer(id: Long, request: SelectSupplierOfferRequest): ApiResult<SupplierOfferDto>
    suspend fun clearSelection(id: Long, version: Long): ApiResult<SupplierOfferDto>
    suspend fun offerHistory(id: Long): ApiResult<List<SupplierOfferHistoryDto>>
    suspend fun compareOffers(demandId: Long): ApiResult<SupplierOfferComparisonDto>
}
