package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.CreateNomenclatureAnalogRequest
import org.example.project.dto.CreateNomenclatureBarcodeRequest
import org.example.project.dto.CreateNomenclatureCategoryRequest
import org.example.project.dto.CreateUnitConversionRequest
import org.example.project.dto.DocumentDto
import org.example.project.dto.NomenclatureAnalogDto
import org.example.project.dto.NomenclatureCardDto
import org.example.project.dto.NomenclatureCategoryDto
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.NomenclatureHistoryEntryDto
import org.example.project.dto.NomenclatureMovementsPageDto
import org.example.project.dto.NomenclatureReservationRowDto
import org.example.project.dto.NomenclatureStockPointDto
import org.example.project.dto.NomenclatureUnitConversionDto
import org.example.project.dto.NomenclatureUsageDto
import org.example.project.dto.StockMovementType
import org.example.project.dto.UpdateNomenclatureCategoryRequest
import org.example.project.dto.UpdateNomenclatureRequest

/** API карточки номенклатуры (NSI-001, D-63). */
interface NomenclatureCardRepository {
    suspend fun getCard(id: Long): ApiResult<NomenclatureCardDto>
    suspend fun updateCard(id: Long, request: UpdateNomenclatureRequest): ApiResult<NomenclatureCardDto>
    suspend fun stockHistory(id: Long, days: Int): ApiResult<List<NomenclatureStockPointDto>>
    suspend fun movements(
        id: Long,
        type: StockMovementType? = null,
        before: Long? = null,
        limit: Int = 50
    ): ApiResult<NomenclatureMovementsPageDto>
    suspend fun reservations(id: Long): ApiResult<List<NomenclatureReservationRowDto>>
    suspend fun usage(id: Long): ApiResult<NomenclatureUsageDto>
    suspend fun history(id: Long): ApiResult<List<NomenclatureHistoryEntryDto>>
    suspend fun documents(id: Long): ApiResult<List<DocumentDto>>
    suspend fun archive(id: Long): ApiResult<NomenclatureDto>

    suspend fun addBarcode(id: Long, request: CreateNomenclatureBarcodeRequest): ApiResult<NomenclatureDto>
    suspend fun deleteBarcode(id: Long, barcodeId: Long): ApiResult<NomenclatureDto>
    suspend fun addUnitConversion(id: Long, request: CreateUnitConversionRequest): ApiResult<NomenclatureUnitConversionDto>
    suspend fun deleteUnitConversion(id: Long, conversionId: Long): ApiResult<List<NomenclatureUnitConversionDto>>
    suspend fun addAnalog(id: Long, request: CreateNomenclatureAnalogRequest): ApiResult<List<NomenclatureAnalogDto>>
    suspend fun deleteAnalog(id: Long, analogId: Long): ApiResult<List<NomenclatureAnalogDto>>

    suspend fun listCategories(): ApiResult<List<NomenclatureCategoryDto>>
    suspend fun createCategory(request: CreateNomenclatureCategoryRequest): ApiResult<NomenclatureCategoryDto>
    suspend fun updateCategory(id: Long, request: UpdateNomenclatureCategoryRequest): ApiResult<NomenclatureCategoryDto>
}

class NomenclatureCardApiClient(private val client: HttpClient) : NomenclatureCardRepository {

    private fun base(id: Long) = "/api/wms/nomenclature/$id"

    override suspend fun getCard(id: Long): ApiResult<NomenclatureCardDto> = execute { client.get("${base(id)}/card") }

    override suspend fun updateCard(id: Long, request: UpdateNomenclatureRequest): ApiResult<NomenclatureCardDto> =
        execute {
            client.put(base(id)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun stockHistory(id: Long, days: Int): ApiResult<List<NomenclatureStockPointDto>> =
        execute { client.get("${base(id)}/stock-history") { url { parameters.append("days", days.toString()) } } }

    override suspend fun movements(
        id: Long,
        type: StockMovementType?,
        before: Long?,
        limit: Int
    ): ApiResult<NomenclatureMovementsPageDto> =
        execute {
            client.get("${base(id)}/movements") {
                url {
                    type?.let { parameters.append("type", it.name) }
                    before?.let { parameters.append("before", it.toString()) }
                    parameters.append("limit", limit.toString())
                }
            }
        }

    override suspend fun reservations(id: Long): ApiResult<List<NomenclatureReservationRowDto>> =
        execute { client.get("${base(id)}/reservations") }

    override suspend fun usage(id: Long): ApiResult<NomenclatureUsageDto> = execute { client.get("${base(id)}/usage") }

    override suspend fun history(id: Long): ApiResult<List<NomenclatureHistoryEntryDto>> =
        execute { client.get("${base(id)}/history") }

    override suspend fun documents(id: Long): ApiResult<List<DocumentDto>> = execute { client.get("${base(id)}/documents") }

    override suspend fun archive(id: Long): ApiResult<NomenclatureDto> = execute { client.post("${base(id)}/archive") }

    override suspend fun addBarcode(id: Long, request: CreateNomenclatureBarcodeRequest): ApiResult<NomenclatureDto> =
        execute {
            client.post("${base(id)}/barcodes") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteBarcode(id: Long, barcodeId: Long): ApiResult<NomenclatureDto> =
        execute { client.delete("${base(id)}/barcodes/$barcodeId") }

    override suspend fun addUnitConversion(
        id: Long,
        request: CreateUnitConversionRequest
    ): ApiResult<NomenclatureUnitConversionDto> =
        execute {
            client.post("${base(id)}/unit-conversions") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteUnitConversion(id: Long, conversionId: Long): ApiResult<List<NomenclatureUnitConversionDto>> =
        execute { client.delete("${base(id)}/unit-conversions/$conversionId") }

    override suspend fun addAnalog(id: Long, request: CreateNomenclatureAnalogRequest): ApiResult<List<NomenclatureAnalogDto>> =
        execute {
            client.post("${base(id)}/analogs") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteAnalog(id: Long, analogId: Long): ApiResult<List<NomenclatureAnalogDto>> =
        execute { client.delete("${base(id)}/analogs/$analogId") }

    override suspend fun listCategories(): ApiResult<List<NomenclatureCategoryDto>> =
        execute { client.get("/api/wms/nomenclature-categories") }

    override suspend fun createCategory(request: CreateNomenclatureCategoryRequest): ApiResult<NomenclatureCategoryDto> =
        execute {
            client.post("/api/wms/nomenclature-categories") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateCategory(
        id: Long,
        request: UpdateNomenclatureCategoryRequest
    ): ApiResult<NomenclatureCategoryDto> =
        execute {
            client.put("/api/wms/nomenclature-categories/$id") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    private suspend inline fun <reified T> execute(block: () -> HttpResponse): ApiResult<T> =
        try {
            val response = block()
            val body: ApiResponse<T> = response.body()
            val data = body.data
            val error = body.error
            when {
                body.success && data != null -> ApiResult.Success(data)
                error != null -> ApiResult.Error(error, response.status.value)
                else -> ApiResult.Error(ApiError("UNKNOWN_ERROR", "Unexpected server response"), response.status.value)
            }
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(ApiError("NETWORK_ERROR", e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(ApiError("NETWORK_ERROR", e.message))
        } catch (e: Exception) {
            ApiResult.Error(ApiError("NETWORK_ERROR", e.message ?: "Unexpected error"))
        }
}
