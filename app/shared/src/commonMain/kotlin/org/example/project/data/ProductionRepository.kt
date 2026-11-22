package org.example.project.data

import org.example.project.dto.AddInstanceSoftwareRequest
import org.example.project.dto.AllocateProductionMaterialRequest
import org.example.project.dto.ApiError
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
import org.example.project.dto.ProductionOrderActionRequest
import org.example.project.dto.ProductionOrderDto
import org.example.project.dto.ProductionOrderMaterialDto
import org.example.project.dto.ProductionOrderStatus
import org.example.project.dto.ProductionStageActionRequest
import org.example.project.dto.RecordInstanceRemarkRequest
import org.example.project.dto.RecordInstanceTestRequest
import org.example.project.dto.TransitionNonconformityRequest

interface ProductionRepository {
    suspend fun listOrders(
        projectId: Long? = null,
        productModelId: Long? = null,
        status: ProductionOrderStatus? = null
    ): ApiResult<List<ProductionOrderDto>>

    suspend fun getOrder(id: Long): ApiResult<ProductionOrderDto>
    suspend fun createOrder(request: CreateProductionOrderRequest): ApiResult<ProductionOrderDto>

    suspend fun planOrder(id: Long, request: ProductionOrderActionRequest): ApiResult<ProductionOrderDto>
    suspend fun startOrder(id: Long, request: ProductionOrderActionRequest): ApiResult<ProductionOrderDto>
    suspend fun holdOrder(id: Long, request: ProductionOrderActionRequest): ApiResult<ProductionOrderDto>
    suspend fun resumeOrder(id: Long, request: ProductionOrderActionRequest): ApiResult<ProductionOrderDto>
    suspend fun completeOrder(
        id: Long,
        request: CompleteProductionOrderRequest
    ): ApiResult<ProductionOrderDto>
    suspend fun closeOrder(id: Long, request: ProductionOrderActionRequest): ApiResult<ProductionOrderDto>
    suspend fun cancelOrder(id: Long, request: CancelProductionOrderRequest): ApiResult<ProductionOrderDto>

    suspend fun startStage(
        orderId: Long,
        stageId: Long,
        request: ProductionStageActionRequest
    ): ApiResult<ProductionOrderDto>
    suspend fun completeStage(
        orderId: Long,
        stageId: Long,
        request: ProductionStageActionRequest
    ): ApiResult<ProductionOrderDto>
    suspend fun skipStage(
        orderId: Long,
        stageId: Long,
        request: ProductionStageActionRequest
    ): ApiResult<ProductionOrderDto>

    suspend fun listInstances(orderId: Long): ApiResult<List<ProductInstanceDto>>
    suspend fun getInstance(id: Long): ApiResult<ProductInstanceDetailDto>
    suspend fun recordInstanceTest(id: Long, request: RecordInstanceTestRequest): ApiResult<ProductInstanceDetailDto>
    suspend fun recordInstanceRemark(id: Long, request: RecordInstanceRemarkRequest): ApiResult<ProductInstanceDetailDto>
    suspend fun addInstanceSoftware(id: Long, request: AddInstanceSoftwareRequest): ApiResult<ProductInstanceDetailDto>
    suspend fun resolveInstanceRemark(id: Long, remarkId: Long): ApiResult<ProductInstanceDetailDto>
    suspend fun acceptInstance(id: Long): ApiResult<ProductInstanceDetailDto>
    suspend fun shipInstance(id: Long): ApiResult<ProductInstanceDetailDto>

    suspend fun listMaterials(orderId: Long): ApiResult<List<ProductionOrderMaterialDto>> =
        ApiResult.Success(emptyList())

    suspend fun initMaterialsFromBom(orderId: Long): ApiResult<List<ProductionOrderMaterialDto>> =
        ApiResult.Error(ApiError("NOT_IMPLEMENTED", "Not implemented"))

    suspend fun allocateMaterial(
        orderId: Long,
        materialId: Long,
        request: AllocateProductionMaterialRequest
    ): ApiResult<ProductionOrderMaterialDto> =
        ApiResult.Error(ApiError("NOT_IMPLEMENTED", "Not implemented"))

    suspend fun consumeMaterial(
        orderId: Long,
        materialId: Long,
        request: ConsumeProductionMaterialRequest
    ): ApiResult<ProductionOrderMaterialDto> =
        ApiResult.Error(ApiError("NOT_IMPLEMENTED", "Not implemented"))

    suspend fun listEngineeringChanges(orderId: Long): ApiResult<List<EngineeringChangeImpactDto>> =
        ApiResult.Success(emptyList())

    suspend fun createEngineeringChange(
        orderId: Long,
        request: CreateEngineeringChangeRequest
    ): ApiResult<EngineeringChangeImpactDto> =
        ApiResult.Error(ApiError("NOT_IMPLEMENTED", "Not implemented"))

    suspend fun decideEngineeringChange(
        changeId: Long,
        request: DecideEngineeringChangeRequest
    ): ApiResult<EngineeringChangeImpactDto> =
        ApiResult.Error(ApiError("NOT_IMPLEMENTED", "Not implemented"))

    suspend fun listNonconformities(
        objectType: String? = null,
        objectId: Long? = null,
        status: NonconformityStatus? = null
    ): ApiResult<List<NonconformityDto>> =
        ApiResult.Success(emptyList())

    suspend fun createNonconformity(request: CreateNonconformityRequest): ApiResult<NonconformityDto> =
        ApiResult.Error(ApiError("NOT_IMPLEMENTED", "Not implemented"))

    suspend fun transitionNonconformity(
        id: Long,
        request: TransitionNonconformityRequest
    ): ApiResult<NonconformityDto> =
        ApiResult.Error(ApiError("NOT_IMPLEMENTED", "Not implemented"))
}
