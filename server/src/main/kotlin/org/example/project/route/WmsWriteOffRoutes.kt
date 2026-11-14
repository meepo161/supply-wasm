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
import org.example.project.service.WmsWriteOffService

// Ответы команд возвращаются как сохранённое JSON-тело (idempotency replay),
// поэтому используется respondText, а не respond с сериализацией.
fun Route.wmsWriteOffRoutes(wmsWriteOffService: WmsWriteOffService) {
    route("/api/wms/write-offs") {
        install(RequireAuthPlugin) {}

        post {
            val request = call.receive<CreateWriteOffRequest>()
            val result = wmsWriteOffService.create(request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        get {
            val warehouseId = call.request.queryParameters["warehouseId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid warehouseId")
            }
            val status = call.request.queryParameters["status"]?.let {
                try {
                    WriteOffStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid write-off status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(wmsWriteOffService.list(warehouseId, status, call.currentUser()))
            )
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid write-off id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsWriteOffService.get(id, call.currentUser())))
        }

        post("/{id}/post") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid write-off id")
            val request = call.receive<WriteOffActionRequest>()
            val result = wmsWriteOffService.post(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/cancel") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid write-off id")
            val request = call.receive<CancelWriteOffRequest>()
            val result = wmsWriteOffService.cancel(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }
    }

    route("/api/wms/write-off-limit") {
        install(RequireAuthPlugin) {}

        get("/{warehouseId}") {
            val warehouseId = call.parameters["warehouseId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid warehouse id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(wmsWriteOffService.getLimit(warehouseId, call.currentUser()))
            )
        }

        put("/{warehouseId}") {
            val warehouseId = call.parameters["warehouseId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid warehouse id")
            val request = call.receive<SetWriteOffLimitRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(wmsWriteOffService.setLimit(warehouseId, request, call.currentUser()))
            )
        }
    }
}
