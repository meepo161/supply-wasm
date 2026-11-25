package org.example.project.data

import org.example.project.dto.CancelPurchaseDeliveryRequest
import org.example.project.dto.CancelPurchaseOrderRequest
import org.example.project.dto.CreatePurchaseDeliveryRequest
import org.example.project.dto.CreatePurchaseOrderRequest
import org.example.project.dto.PurchaseDeliveryDto
import org.example.project.dto.PurchaseOrderActionRequest
import org.example.project.dto.PurchaseOrderDto
import org.example.project.dto.PurchaseOrderHistoryDto
import org.example.project.dto.PurchaseOrderStatus
import org.example.project.dto.UpdatePurchaseOrderRequest

interface PurchaseOrderRepository {
    suspend fun listOrders(
        supplierId: Long? = null,
        status: PurchaseOrderStatus? = null,
        responsibleId: Long? = null
    ): ApiResult<List<PurchaseOrderDto>>

    suspend fun getOrder(id: Long): ApiResult<PurchaseOrderDto>
    suspend fun createOrder(request: CreatePurchaseOrderRequest): ApiResult<PurchaseOrderDto>
    suspend fun updateOrder(id: Long, request: UpdatePurchaseOrderRequest): ApiResult<PurchaseOrderDto>

    suspend fun submitOrder(id: Long, request: PurchaseOrderActionRequest): ApiResult<PurchaseOrderDto>
    suspend fun approveOrder(id: Long, request: PurchaseOrderActionRequest): ApiResult<PurchaseOrderDto>
    suspend fun sendOrder(id: Long, request: PurchaseOrderActionRequest): ApiResult<PurchaseOrderDto>
    suspend fun closeOrder(id: Long, request: PurchaseOrderActionRequest): ApiResult<PurchaseOrderDto>
    suspend fun cancelOrder(id: Long, request: CancelPurchaseOrderRequest): ApiResult<PurchaseOrderDto>
    suspend fun markOrderPaid(id: Long): ApiResult<PurchaseOrderDto>

    suspend fun orderHistory(id: Long): ApiResult<List<PurchaseOrderHistoryDto>>
    /** Все поставки — сервер фильтрует по заказу, если он передан. */
    suspend fun listDeliveries(orderId: Long? = null): ApiResult<List<PurchaseDeliveryDto>>
    suspend fun registerDelivery(
        orderId: Long,
        request: CreatePurchaseDeliveryRequest
    ): ApiResult<PurchaseDeliveryDto>

    suspend fun cancelDelivery(
        id: Long,
        request: CancelPurchaseDeliveryRequest
    ): ApiResult<PurchaseDeliveryDto>
}
