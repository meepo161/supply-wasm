package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.exception.ValidationException
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.ReceiptQcService
import org.example.project.service.ReceiptService

/**
 * Приёмка на склад: основания, расхождения, проведение и поздние связи.
 * Поиск/создание приёмок сохраняют прежние пути `/api/wms/receipts*`, а
 * ожидаемые поставки и связи вынесены отдельными путями.
 */
fun Route.receiptRoutes(receiptService: ReceiptService, receiptQcService: ReceiptQcService) {
    route("/api/wms/receipts") {
        install(RequireAuthPlugin) {}

        get {
            val warehouseId = call.request.queryParameters["warehouseId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid warehouseId")
            }
            val orderId = call.request.queryParameters["purchaseOrderId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid purchaseOrderId")
            }
            val status = call.request.queryParameters["status"]?.let {
                try {
                    ReceiptStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid receipt status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(
                    receiptService.listReceipts(warehouseId, orderId, status, call.currentUser())
                )
            )
        }

        post {
            val request = call.receive<CreateReceiptRequest>()
            val receipt = receiptService.createReceipt(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(receipt))
        }

        get("/expected") {
            val orderId = call.request.queryParameters["purchaseOrderId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid purchaseOrderId")
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(receiptService.listExpectedReceipts(orderId, call.currentUser()))
            )
        }

        post("/quick-receive-order") {
            val request = call.receive<QuickReceiveOrderRequest>()
            val receipt = receiptService.quickReceiveOrder(request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(receipt))
        }

        post("/receive-order") {
            val request = call.receive<ReceiveOrderItemsRequest>()
            val receipt = receiptService.receiveOrderItems(request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(receipt))
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid receipt id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(receiptService.getReceipt(id, call.currentUser())))
        }

        // Склад вносит факт, ячейки и расхождения в поступление (в т. ч. по счёту от снабжения).
        put("/{id}/acceptance") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid receipt id")
            val request = call.receive<AcceptReceiptLinesRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(receiptService.acceptLines(id, request, call.currentUser()))
            )
        }

        post("/{id}/post") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid receipt id")
            val request = call.receive<PostReceiptRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(receiptService.postReceipt(id, request, call.currentUser()))
            )
        }

        /** Поздняя связь проведённой приёмки с заказом или накладной. */
        post("/{id}/links") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid receipt id")
            val request = call.receive<LinkReceiptRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(receiptService.linkReceipt(id, request, call.currentUser()))
            )
        }

        /** Контроль качества при приёмке (WMS-37). */
        get("/{id}/qc") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid receipt id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(receiptQcService.getOrInit(id, call.currentUser())))
        }

        post("/{id}/qc") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid receipt id")
            val request = call.receive<InspectReceiptQcRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(receiptQcService.inspect(id, request, call.currentUser())))
        }
    }

    /** Ожидаемые к приёмке позиции: «заказано / поставлено / принято / осталось». */
    route("/api/wms/expected-receipts") {
        install(RequireAuthPlugin) {}

        get {
            val orderId = call.request.queryParameters["purchaseOrderId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid purchaseOrderId")
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(receiptService.listExpectedReceipts(orderId, call.currentUser()))
            )
        }
    }
}
