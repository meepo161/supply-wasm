package org.example.project.data

import org.example.project.dto.*

interface ErpExtrasRepository {
    suspend fun listTaskDependencies(taskId: Long): ApiResult<List<TaskDependencyDto>>
    suspend fun addTaskDependency(request: CreateTaskDependencyRequest): ApiResult<TaskDependencyDto>
    suspend fun deleteTaskDependency(depId: Long): ApiResult<Unit>

    suspend fun listCounterparties(kind: CounterpartyKind? = null, search: String? = null): ApiResult<List<CounterpartyDto>>
    suspend fun createCounterparty(request: CreateCounterpartyRequest): ApiResult<CounterpartyDto>
    suspend fun updateCounterparty(id: Long, request: UpdateCounterpartyRequest): ApiResult<CounterpartyDto>
    suspend fun addCounterpartyContact(counterpartyId: Long, request: CreateCounterpartyContactRequest): ApiResult<CounterpartyContactDto>

    suspend fun mergeDemands(request: MergeDemandsRequest): ApiResult<Long>
    suspend fun getProcurementPlan(): ApiResult<List<ProcurementPlanItemDto>>

    suspend fun listInstanceDocuments(instanceId: Long): ApiResult<List<InstanceDocumentDto>>
    suspend fun attachInstanceDocument(instanceId: Long, request: AttachInstanceDocumentRequest): ApiResult<InstanceDocumentDto>

    suspend fun listDocumentTemplates(): ApiResult<List<DocumentTemplateDto>>
    suspend fun createDocumentTemplate(request: CreateDocumentTemplateRequest): ApiResult<DocumentTemplateDto>

    suspend fun substituteOrderMaterial(
        orderId: Long,
        materialId: Long,
        request: SubstituteOrderMaterialRequest
    ): ApiResult<ProductionOrderMaterialDto>

    suspend fun importNomenclatureCsv(request: CsvImportRequest): ApiResult<CsvImportResultDto>

    suspend fun listDelegations(): ApiResult<List<DelegationDto>>
    suspend fun createDelegation(request: CreateDelegationRequest): ApiResult<DelegationDto>
    suspend fun revokeDelegation(id: Long): ApiResult<Unit>

    suspend fun listTaskObjectLinks(taskId: Long): ApiResult<List<TaskObjectLinkDto>>
    suspend fun addTaskObjectLink(taskId: Long, request: CreateTaskObjectLinkRequest): ApiResult<TaskObjectLinkDto>

    suspend fun listProjectChangeRequests(projectId: Long): ApiResult<List<ProjectChangeRequestDto>>
    suspend fun createProjectChangeRequest(projectId: Long, request: CreateProjectChangeRequest): ApiResult<ProjectChangeRequestDto>

    suspend fun bulkTaskAction(request: BulkTaskActionRequest): ApiResult<Int>
    suspend fun getExecutiveQueues(): ApiResult<List<DepartmentQueueDto>>
}
