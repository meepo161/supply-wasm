package org.example.project.data

import org.example.project.dto.CancelProductionOrderRequest
import org.example.project.dto.CompleteProductionOrderRequest
import org.example.project.dto.CreateProductionOrderRequest
import org.example.project.dto.ProductInstanceDto
import org.example.project.dto.ProductionOrderActionRequest
import org.example.project.dto.ProductionOrderDto
import org.example.project.dto.ProductionOrderStatus
import org.example.project.dto.ProductionStageActionRequest

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
}
