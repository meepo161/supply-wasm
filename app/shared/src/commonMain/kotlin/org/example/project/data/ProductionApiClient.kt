package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.dto.AddInstanceSoftwareRequest
import org.example.project.dto.AllocateProductionMaterialRequest
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.CancelProductionOrderRequest
import org.example.project.dto.CompleteProductionOrderRequest
import org.example.project.dto.ConsumeProductionMaterialRequest
import org.example.project.dto.CreateEngineeringChangeRequest
import org.example.project.dto.CreateNonconformityRequest
import org.example.project.dto.CreateProductionOrderRequest
import org.example.project.dto.DecideEngineeringChangeRequest
import org.example.project.dto.EngineeringChangeImpactDto
import org.example.project.dto.NonconformityDto
import org.example.project.dto.NonconformityStatus
import org.example.project.dto.ProductInstanceDetailDto
import org.example.project.dto.ProductInstanceDto
import org.example.project.dto.RecordInstanceRemarkRequest
import org.example.project.dto.RecordInstanceTestRequest
import org.example.project.dto.ProductionOrderActionRequest
import org.example.project.dto.ProductionOrderDto
import org.example.project.dto.ProductionOrderMaterialDto
import org.example.project.dto.ProductionOrderStatus
import org.example.project.dto.ProductionStageActionRequest
import org.example.project.dto.TransitionNonconformityRequest

class ProductionApiClient(private val client: HttpClient) : ProductionRepository {

    override suspend fun listOrders(
        projectId: Long?,
        productModelId: Long?,
        status: ProductionOrderStatus?
    ): ApiResult<List<ProductionOrderDto>> {
        val params = buildMap {
            projectId?.let { put("projectId", it.toString()) }
            productModelId?.let { put("productModelId", it.toString()) }
            status?.let { put("status", it.name) }
        }
        return getWithParams("/api/production/orders", params)
    }

    override suspend fun getOrder(id: Long): ApiResult<ProductionOrderDto> =
        get("/api/production/orders/$id")

    override suspend fun createOrder(request: CreateProductionOrderRequest): ApiResult<ProductionOrderDto> =
        post("/api/production/orders", request)

    override suspend fun planOrder(
        id: Long,
        request: ProductionOrderActionRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/plan", request)

    override suspend fun startOrder(
        id: Long,
        request: ProductionOrderActionRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/start", request)

    override suspend fun holdOrder(
        id: Long,
        request: ProductionOrderActionRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/hold", request)

    override suspend fun resumeOrder(
        id: Long,
        request: ProductionOrderActionRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/resume", request)

    override suspend fun completeOrder(
        id: Long,
        request: CompleteProductionOrderRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/complete", request)

    override suspend fun closeOrder(
        id: Long,
        request: ProductionOrderActionRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/close", request)

    override suspend fun cancelOrder(
        id: Long,
        request: CancelProductionOrderRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/cancel", request)

    override suspend fun startStage(
        orderId: Long,
        stageId: Long,
        request: ProductionStageActionRequest
    ): ApiResult<ProductionOrderDto> =
        post("/api/production/orders/$orderId/stages/$stageId/start", request)

    override suspend fun completeStage(
        orderId: Long,
        stageId: Long,
        request: ProductionStageActionRequest
    ): ApiResult<ProductionOrderDto> =
        post("/api/production/orders/$orderId/stages/$stageId/complete", request)

    override suspend fun skipStage(
        orderId: Long,
        stageId: Long,
        request: ProductionStageActionRequest
    ): ApiResult<ProductionOrderDto> =
        post("/api/production/orders/$orderId/stages/$stageId/skip", request)

    override suspend fun listInstances(orderId: Long): ApiResult<List<ProductInstanceDto>> =
        get("/api/production/orders/$orderId/instances")

    override suspend fun getInstance(id: Long): ApiResult<ProductInstanceDetailDto> =
        get("/api/production/instances/$id")

    override suspend fun recordInstanceTest(
        id: Long,
        request: RecordInstanceTestRequest
    ): ApiResult<ProductInstanceDetailDto> =
        post("/api/production/instances/$id/tests", request)

    override suspend fun recordInstanceRemark(
        id: Long,
        request: RecordInstanceRemarkRequest
    ): ApiResult<ProductInstanceDetailDto> =
        post("/api/production/instances/$id/remarks", request)

    override suspend fun addInstanceSoftware(
        id: Long,
        request: AddInstanceSoftwareRequest
    ): ApiResult<ProductInstanceDetailDto> =
        post("/api/production/instances/$id/software", request)

    override suspend fun resolveInstanceRemark(id: Long, remarkId: Long): ApiResult<ProductInstanceDetailDto> =
        post("/api/production/instances/$id/remarks/$remarkId/resolve", emptyMap<String, String>())

    override suspend fun acceptInstance(id: Long): ApiResult<ProductInstanceDetailDto> =
        post("/api/production/instances/$id/accept", emptyMap<String, String>())

    override suspend fun shipInstance(id: Long): ApiResult<ProductInstanceDetailDto> =
        post("/api/production/instances/$id/ship", emptyMap<String, String>())

    override suspend fun listMaterials(orderId: Long): ApiResult<List<ProductionOrderMaterialDto>> =
        get("/api/production/orders/$orderId/materials")

    override suspend fun initMaterialsFromBom(orderId: Long): ApiResult<List<ProductionOrderMaterialDto>> =
        post("/api/production/orders/$orderId/materials/init-from-bom", emptyMap<String, String>())

    override suspend fun allocateMaterial(
        orderId: Long,
        materialId: Long,
        request: AllocateProductionMaterialRequest
    ): ApiResult<ProductionOrderMaterialDto> =
        post("/api/production/orders/$orderId/materials/$materialId/allocate", request)

    override suspend fun consumeMaterial(
        orderId: Long,
        materialId: Long,
        request: ConsumeProductionMaterialRequest
    ): ApiResult<ProductionOrderMaterialDto> =
        post("/api/production/orders/$orderId/materials/$materialId/consume", request)

    override suspend fun listEngineeringChanges(orderId: Long): ApiResult<List<EngineeringChangeImpactDto>> =
        get("/api/production/orders/$orderId/engineering-changes")

    override suspend fun createEngineeringChange(
        orderId: Long,
        request: CreateEngineeringChangeRequest
    ): ApiResult<EngineeringChangeImpactDto> =
        post("/api/production/orders/$orderId/engineering-changes", request)

    override suspend fun decideEngineeringChange(
        changeId: Long,
        request: DecideEngineeringChangeRequest
    ): ApiResult<EngineeringChangeImpactDto> =
        post("/api/production/engineering-changes/$changeId/decide", request)

    override suspend fun listNonconformities(
        objectType: String?,
        objectId: Long?,
        status: NonconformityStatus?
    ): ApiResult<List<NonconformityDto>> {
        val params = buildMap {
            objectType?.let { put("objectType", it) }
            objectId?.let { put("objectId", it.toString()) }
            status?.let { put("status", it.name) }
        }
        return getWithParams("/api/production/nonconformities", params)
    }

    override suspend fun createNonconformity(request: CreateNonconformityRequest): ApiResult<NonconformityDto> =
        post("/api/production/nonconformities", request)

    override suspend fun transitionNonconformity(
        id: Long,
        request: TransitionNonconformityRequest
    ): ApiResult<NonconformityDto> =
        post("/api/production/nonconformities/$id/transition", request)

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        execute { client.get(path) }

    private suspend inline fun <reified T> getWithParams(
        path: String,
        params: Map<String, String>
    ): ApiResult<T> = execute {
        client.get(path) {
            url {
                params.forEach { (key, value) -> parameters.append(key, value) }
            }
        }
    }

    private suspend inline fun <reified T> post(path: String, body: Any): ApiResult<T> =
        execute {
            client.post(path) {
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

    private fun networkError(message: String): ApiError =
        ApiError("NETWORK_ERROR", message)
}
