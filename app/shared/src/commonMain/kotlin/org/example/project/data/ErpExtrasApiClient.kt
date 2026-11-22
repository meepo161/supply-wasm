package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.dto.*

class ErpExtrasApiClient(private val client: HttpClient) : ErpExtrasRepository {

    override suspend fun listTaskDependencies(taskId: Long): ApiResult<List<TaskDependencyDto>> =
        safeCall { client.get("/api/tasks/$taskId/dependencies") }

    override suspend fun addTaskDependency(request: CreateTaskDependencyRequest): ApiResult<TaskDependencyDto> =
        safeCall {
            client.post("/api/tasks/dependencies") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteTaskDependency(depId: Long): ApiResult<Unit> =
        safeCall<Map<String, Boolean>> { client.delete("/api/tasks/dependencies/$depId") }.let { res ->
            when (res) {
                is ApiResult.Success -> ApiResult.Success(Unit)
                is ApiResult.Error -> res
            }
        }

    override suspend fun listCounterparties(kind: CounterpartyKind?, search: String?): ApiResult<List<CounterpartyDto>> =
        safeCall {
            client.get("/api/crm/counterparties") {
                kind?.let { url.parameters.append("kind", it.name) }
                search?.takeIf { it.isNotBlank() }?.let { url.parameters.append("search", it) }
            }
        }

    override suspend fun createCounterparty(request: CreateCounterpartyRequest): ApiResult<CounterpartyDto> =
        safeCall {
            client.post("/api/crm/counterparties") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateCounterparty(id: Long, request: UpdateCounterpartyRequest): ApiResult<CounterpartyDto> =
        safeCall {
            client.put("/api/crm/counterparties/$id") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun addCounterpartyContact(
        counterpartyId: Long,
        request: CreateCounterpartyContactRequest
    ): ApiResult<CounterpartyContactDto> =
        safeCall {
            client.post("/api/crm/counterparties/$counterpartyId/contacts") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun mergeDemands(request: MergeDemandsRequest): ApiResult<Long> =
        safeCall<Map<String, Long>> {
            client.post("/api/demands/merge") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }.let { res ->
            when (res) {
                is ApiResult.Success -> ApiResult.Success(res.data["mergedDemandId"] ?: 0L)
                is ApiResult.Error -> res
            }
        }

    override suspend fun getProcurementPlan(): ApiResult<List<ProcurementPlanItemDto>> =
        safeCall { client.get("/api/procurement/plan") }

    override suspend fun listInstanceDocuments(instanceId: Long): ApiResult<List<InstanceDocumentDto>> =
        safeCall { client.get("/api/production/instances/$instanceId/documents") }

    override suspend fun attachInstanceDocument(
        instanceId: Long,
        request: AttachInstanceDocumentRequest
    ): ApiResult<InstanceDocumentDto> =
        safeCall {
            client.post("/api/production/instances/$instanceId/documents") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun listDocumentTemplates(): ApiResult<List<DocumentTemplateDto>> =
        safeCall { client.get("/api/documents/templates") }

    override suspend fun createDocumentTemplate(request: CreateDocumentTemplateRequest): ApiResult<DocumentTemplateDto> =
        safeCall {
            client.post("/api/documents/templates") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun substituteOrderMaterial(
        orderId: Long,
        materialId: Long,
        request: SubstituteOrderMaterialRequest
    ): ApiResult<ProductionOrderMaterialDto> =
        safeCall {
            client.post("/api/production/orders/$orderId/materials/$materialId/substitute") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun importNomenclatureCsv(request: CsvImportRequest): ApiResult<CsvImportResultDto> =
        safeCall {
            client.post("/api/analytics/import/nomenclature") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun listDelegations(): ApiResult<List<DelegationDto>> =
        safeCall { client.get("/api/delegations") }

    override suspend fun createDelegation(request: CreateDelegationRequest): ApiResult<DelegationDto> =
        safeCall {
            client.post("/api/delegations") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun revokeDelegation(id: Long): ApiResult<Unit> =
        safeCall<Map<String, Boolean>> { client.post("/api/delegations/$id/revoke") }.let { res ->
            when (res) {
                is ApiResult.Success -> ApiResult.Success(Unit)
                is ApiResult.Error -> res
            }
        }

    override suspend fun listTaskObjectLinks(taskId: Long): ApiResult<List<TaskObjectLinkDto>> =
        safeCall { client.get("/api/tasks/$taskId/links") }

    override suspend fun addTaskObjectLink(taskId: Long, request: CreateTaskObjectLinkRequest): ApiResult<TaskObjectLinkDto> =
        safeCall {
            client.post("/api/tasks/$taskId/links") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun listProjectChangeRequests(projectId: Long): ApiResult<List<ProjectChangeRequestDto>> =
        safeCall { client.get("/api/projects/$projectId/change-requests") }

    override suspend fun createProjectChangeRequest(projectId: Long, request: CreateProjectChangeRequest): ApiResult<ProjectChangeRequestDto> =
        safeCall {
            client.post("/api/projects/$projectId/change-requests") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun bulkTaskAction(request: BulkTaskActionRequest): ApiResult<Int> =
        safeCall<Map<String, Int>> {
            client.post("/api/tasks/bulk-action") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }.let { res ->
            when (res) {
                is ApiResult.Success -> ApiResult.Success(res.data["updatedCount"] ?: 0)
                is ApiResult.Error -> res
            }
        }

    override suspend fun getExecutiveQueues(): ApiResult<List<DepartmentQueueDto>> =
        safeCall { client.get("/api/analytics/executive-queues") }

    private suspend inline fun <reified T> safeCall(
        block: () -> io.ktor.client.statement.HttpResponse
    ): ApiResult<T> = try {
        val response = block()
        val body = response.body<ApiResponse<T>>()
        val data = body.data
        val err = body.error
        when {
            body.success && data != null -> ApiResult.Success(data)
            err != null -> ApiResult.Error(err, response.status.value)
            else -> ApiResult.Error(ApiError("UNKNOWN_ERROR", "Unexpected response"), response.status.value)
        }
    } catch (e: Exception) {
        ApiResult.Error(ApiError("NETWORK_ERROR", e.message ?: "Network error"))
    }
}
