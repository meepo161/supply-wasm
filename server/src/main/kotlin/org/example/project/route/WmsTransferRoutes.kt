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
import org.example.project.service.WmsTransferService

// Ответы команд возвращаются как сохранённое JSON-тело (idempotency replay),
// поэтому используется respondText, а не respond с сериализацией.
fun Route.wmsTransferRoutes(wmsTransferService: WmsTransferService) {
    route("/api/wms/transfers") {
        install(RequireAuthPlugin) {}

        post {
            val request = call.receive<CreateTransferRequest>()
            val result = wmsTransferService.create(request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        get {
            val warehouseId = call.request.queryParameters["warehouseId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid warehouseId")
            }
            val nomenclatureId = call.request.queryParameters["nomenclatureId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid nomenclatureId")
            }
            val status = call.request.queryParameters["status"]?.let {
                try {
                    TransferStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid transfer status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(wmsTransferService.list(warehouseId, nomenclatureId, status, call.currentUser()))
            )
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid transfer id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsTransferService.get(id, call.currentUser())))
        }

        post("/{id}/ship") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid transfer id")
            val request = call.receive<ShipTransferRequest>()
            val result = wmsTransferService.ship(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/in-transit") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid transfer id")
            val request = call.receive<MarkTransferInTransitRequest>()
            val result = wmsTransferService.markInTransit(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/receive") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid transfer id")
            val request = call.receive<ReceiveTransferRequest>()
            val result = wmsTransferService.receive(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/cancel") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid transfer id")
            val request = call.receive<CancelTransferRequest>()
            val result = wmsTransferService.cancel(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }
    }
}
