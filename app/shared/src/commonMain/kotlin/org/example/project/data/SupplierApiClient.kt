package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.dto.AddSupplierAddressRequest
import org.example.project.dto.AddSupplierContactRequest
import org.example.project.dto.AddSupplierDocumentRequest
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
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

class SupplierApiClient(private val client: HttpClient) : SupplierRepository {

    override suspend fun listSuppliers(
        search: String?,
        verificationStatus: SupplierVerificationStatus?,
        archived: Boolean?
    ): ApiResult<List<SupplierDto>> {
        val params = buildMap {
            search?.takeIf { it.isNotBlank() }?.let { put("search", it) }
            verificationStatus?.let { put("verificationStatus", it.name) }
            archived?.let { put("archived", it.toString()) }
        }
        return getWithParams("/api/suppliers", params)
    }

    override suspend fun getSupplier(id: Long): ApiResult<SupplierCardDto> =
        get("/api/suppliers/$id")

    override suspend fun createSupplier(request: CreateSupplierRequest): ApiResult<SupplierCardDto> =
        post("/api/suppliers", request)

    override suspend fun updateSupplier(
        id: Long,
        request: UpdateSupplierRequest
    ): ApiResult<SupplierDto> = put("/api/suppliers/$id", request)

    override suspend fun verifySupplier(
        id: Long,
        request: SupplierVerificationRequest
    ): ApiResult<SupplierDto> = post("/api/suppliers/$id/verification", request)

    override suspend fun archiveSupplier(
        id: Long,
        version: Long,
        archived: Boolean
    ): ApiResult<SupplierDto> {
        val action = if (archived) "archive" else "restore"
        return post("/api/suppliers/$id/$action?version=$version")
    }

    override suspend fun addContact(
        id: Long,
        request: AddSupplierContactRequest
    ): ApiResult<SupplierCardDto> = post("/api/suppliers/$id/contacts", request)

    override suspend fun addAddress(
        id: Long,
        request: AddSupplierAddressRequest
    ): ApiResult<SupplierCardDto> = post("/api/suppliers/$id/addresses", request)

    override suspend fun addDocument(
        id: Long,
        request: AddSupplierDocumentRequest
    ): ApiResult<SupplierCardDto> = post("/api/suppliers/$id/documents", request)

    override suspend fun setCategories(
        id: Long,
        request: SetSupplierCategoriesRequest
    ): ApiResult<SupplierCardDto> = put("/api/suppliers/$id/categories", request)

    override suspend fun listCategories(): ApiResult<List<SupplierCategoryDto>> =
        get("/api/supplier-categories")

    override suspend fun createCategory(
        request: CreateSupplierCategoryRequest
    ): ApiResult<SupplierCategoryDto> = post("/api/supplier-categories", request)

    override suspend fun listOffers(
        demandId: Long?,
        supplierId: Long?,
        status: SupplierOfferStatus?
    ): ApiResult<List<SupplierOfferDto>> {
        val params = buildMap {
            demandId?.let { put("demandId", it.toString()) }
            supplierId?.let { put("supplierId", it.toString()) }
            status?.let { put("status", it.name) }
        }
        return getWithParams("/api/supplier-offers", params)
    }

    override suspend fun getOffer(id: Long): ApiResult<SupplierOfferDto> =
        get("/api/supplier-offers/$id")

    override suspend fun createOffer(
        request: CreateSupplierOfferRequest
    ): ApiResult<SupplierOfferDto> = post("/api/supplier-offers", request)

    override suspend fun updateOffer(
        id: Long,
        request: UpdateSupplierOfferRequest
    ): ApiResult<SupplierOfferDto> = put("/api/supplier-offers/$id", request)

    override suspend fun changeOfferStatus(
        id: Long,
        request: SupplierOfferStatusRequest
    ): ApiResult<SupplierOfferDto> = post("/api/supplier-offers/$id/status", request)

    override suspend fun selectOffer(
        id: Long,
        request: SelectSupplierOfferRequest
    ): ApiResult<SupplierOfferDto> = post("/api/supplier-offers/$id/select", request)

    override suspend fun clearSelection(id: Long, version: Long): ApiResult<SupplierOfferDto> =
        post("/api/supplier-offers/$id/clear-selection?version=$version")

    override suspend fun offerHistory(id: Long): ApiResult<List<SupplierOfferHistoryDto>> =
        get("/api/supplier-offers/$id/history")

    override suspend fun compareOffers(demandId: Long): ApiResult<SupplierOfferComparisonDto> =
        get("/api/demands/$demandId/supplier-offers/comparison")

    // --- служебное ---

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        execute { client.get(path) }

    private suspend inline fun <reified T> getWithParams(
        path: String,
        params: Map<String, String>
    ): ApiResult<T> = execute {
        client.get(path) {
            url { params.forEach { (key, value) -> parameters.append(key, value) } }
        }
    }

    private suspend inline fun <reified T> post(path: String): ApiResult<T> =
        execute { client.post(path) }

    private suspend inline fun <reified T> post(path: String, body: Any): ApiResult<T> =
        execute {
            client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend inline fun <reified T> put(path: String, body: Any): ApiResult<T> =
        execute {
            client.put(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend inline fun <reified T> execute(
        block: () -> io.ktor.client.statement.HttpResponse
    ): ApiResult<T> =
        try {
            val response = block()
            parseResponse(response.status.value, response.body())
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(networkError(e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(networkError(e.message))
        } catch (e: Exception) {
            ApiResult.Error(networkError(e.message ?: "Unexpected error"))
        }

    private inline fun <reified T> parseResponse(statusCode: Int, body: ApiResponse<T>): ApiResult<T> {
        val data = body.data
        val error = body.error
        return when {
            body.success && data != null -> ApiResult.Success(data)
            error != null -> ApiResult.Error(error, statusCode)
            statusCode in 200..299 -> ApiResult.Error(
                ApiError("EMPTY_RESPONSE", "Empty successful response"),
                statusCode
            )
            else -> ApiResult.Error(
                ApiError("UNKNOWN_ERROR", "Unexpected server response"),
                statusCode
            )
        }
    }

    private fun networkError(message: String): ApiError = ApiError("NETWORK_ERROR", message)
}
