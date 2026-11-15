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
import org.example.project.service.PurchaseOrderService

fun Route.purchaseOrderRoutes(purchaseOrderService: PurchaseOrderService) {
    route("/api/purchase-orders") {
        install(RequireAuthPlugin) {}

        get {
            val supplierId = call.request.queryParameters["supplierId"]?.toLongOrNull()
            val responsibleId = call.request.queryParameters["responsibleId"]?.toLongOrNull()
            val status = call.request.queryParameters["status"]?.let {
                try {
                    PurchaseOrderStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid purchase order status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(
                    purchaseOrderService.listOrders(supplierId, status, responsibleId, call.currentUser())
                )
            )
        }

        post {
            val request = call.receive<CreatePurchaseOrderRequest>()
            val result = purchaseOrderService.createOrder(request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid purchase order id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(purchaseOrderService.getOrder(id, call.currentUser()))
            )
        }

        put("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid purchase order id")
            val request = call.receive<UpdatePurchaseOrderRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(purchaseOrderService.updateOrder(id, request, call.currentUser()))
            )
        }

        post("/{id}/submit") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid purchase order id")
            val request = call.receive<PurchaseOrderActionRequest>()
            val result = purchaseOrderService.submitForApproval(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/approve") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid purchase order id")
            val request = call.receive<PurchaseOrderActionRequest>()
            val result = purchaseOrderService.approve(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/send") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid purchase order id")
            val request = call.receive<PurchaseOrderActionRequest>()
            val result = purchaseOrderService.send(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/close") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid purchase order id")
            val request = call.receive<PurchaseOrderActionRequest>()
            val result = purchaseOrderService.close(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/cancel") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid purchase order id")
            val request = call.receive<CancelPurchaseOrderRequest>()
            val result = purchaseOrderService.cancel(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        get("/{id}/history") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid purchase order id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(purchaseOrderService.listOrderHistory(id, call.currentUser()))
            )
        }

        get("/{id}/deliveries") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid purchase order id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(purchaseOrderService.listDeliveries(id, call.currentUser()))
            )
        }

        post("/{id}/deliveries") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid purchase order id")
            val request = call.receive<CreatePurchaseDeliveryRequest>()
            val result = purchaseOrderService.registerDelivery(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }
    }

    route("/api/purchase-deliveries") {
        install(RequireAuthPlugin) {}

        get {
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(purchaseOrderService.listDeliveries(null, call.currentUser()))
            )
        }

        post("/{id}/cancel") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid delivery id")
            val request = call.receive<CancelPurchaseDeliveryRequest>()
            val result = purchaseOrderService.cancelDelivery(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }
    }
}
