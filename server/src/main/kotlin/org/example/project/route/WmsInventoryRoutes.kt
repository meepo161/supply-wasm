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
import org.example.project.service.WmsInventoryService

// Ответы команд возвращаются как сохранённое JSON-тело (idempotency replay),
// поэтому используется respondText, а не respond с сериализацией.
fun Route.wmsInventoryRoutes(wmsInventoryService: WmsInventoryService) {
    route("/api/wms/inventories") {
        install(RequireAuthPlugin) {}

        post {
            val request = call.receive<CreateInventoryRequest>()
            val result = wmsInventoryService.create(request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        get {
            val warehouseId = call.request.queryParameters["warehouseId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid warehouseId")
            }
            val status = call.request.queryParameters["status"]?.let {
                try {
                    InventoryStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid inventory status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(wmsInventoryService.list(warehouseId, status, call.currentUser()))
            )
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid inventory id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsInventoryService.get(id, call.currentUser())))
        }

        post("/{id}/book") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid inventory id")
            val request = call.receive<InventoryActionRequest>()
            val result = wmsInventoryService.book(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/counts") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid inventory id")
            val request = call.receive<SubmitInventoryCountsRequest>()
            val result = wmsInventoryService.submitCounts(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/approve") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid inventory id")
            val request = call.receive<InventoryActionRequest>()
            val result = wmsInventoryService.approve(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/post") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid inventory id")
            val request = call.receive<InventoryActionRequest>()
            val result = wmsInventoryService.post(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/pause") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid inventory id")
            val request = call.receive<InventoryActionRequest>()
            val result = wmsInventoryService.pause(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/resume") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid inventory id")
            val request = call.receive<InventoryActionRequest>()
            val result = wmsInventoryService.resume(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/cancel") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid inventory id")
            val request = call.receive<CancelInventoryRequest>()
            val result = wmsInventoryService.cancel(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }
    }
}
