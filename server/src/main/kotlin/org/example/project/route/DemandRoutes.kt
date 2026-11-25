package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.example.project.dto.*
import org.example.project.exception.ValidationException
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.DemandService

private val demandOptionalBodyJson = Json { ignoreUnknownKeys = true }

fun Route.demandRoutes(demandService: DemandService) {
    route("/api/demands") {
        install(RequireAuthPlugin) {}

        get {
            call.respond(HttpStatusCode.OK, ApiResponse.success(demandService.list(call.currentUser())))
        }

        post {
            val request = call.receive<CreateDemandRequest>()
            val demand = demandService.create(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(demand))
        }

        post("/from-bom") {
            val request = call.receive<CreateDemandFromBomRequest>()
            val demand = demandService.createFromBom(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(demand))
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid demand id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(demandService.get(id, call.currentUser())))
        }

        post("/{id}/submit") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid demand id")
            val request = call.receive<SubmitDemandRequest>()
            val demand = demandService.submit(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(demand))
        }

        post("/{id}/approve") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid demand id")
            val request = call.receive<ApproveDemandRequest>()
            val demand = demandService.approve(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(demand))
        }

        post("/{id}/cancel") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid demand id")
            val request = call.receive<CancelDemandRequest>()
            val demand = demandService.cancel(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(demand))
        }

        get("/{id}/coverage") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid demand id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(demandService.coveragePreview(id, call.currentUser()))
            )
        }

        post("/{id}/mark-paid") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid demand id")
            val request = if (call.request.contentType().match(ContentType.Application.Json)) {
                val bodyText = call.receiveText()
                if (bodyText.isBlank()) MarkDemandPaidRequest()
                else try {
                    demandOptionalBodyJson.decodeFromString<MarkDemandPaidRequest>(bodyText)
                } catch (_: Exception) {
                    throw ValidationException("Invalid request body")
                }
            } else {
                MarkDemandPaidRequest()
            }
            val demand = demandService.markDemandPaid(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(demand))
        }

        post("/{id}/lines/{lineId}/reserve-stock") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid demand id")
            val lineId = call.parameters["lineId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid demand line id")
            val request = if (call.request.contentType().match(ContentType.Application.Json)) {
                val bodyText = call.receiveText()
                if (bodyText.isBlank()) ReserveDemandStockRequest()
                else try {
                    demandOptionalBodyJson.decodeFromString<ReserveDemandStockRequest>(bodyText)
                } catch (_: Exception) {
                    throw ValidationException("Invalid request body")
                }
            } else {
                ReserveDemandStockRequest()
            }
            val demand = demandService.reserveDemandLineStock(id, lineId, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(demand))
        }

        post("/{id}/place-order") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid demand id")
            val request = if (call.request.contentType().match(ContentType.Application.Json)) {
                val bodyText = call.receiveText()
                if (bodyText.isBlank()) PlaceDemandOrderRequest()
                else try {
                    demandOptionalBodyJson.decodeFromString<PlaceDemandOrderRequest>(bodyText)
                } catch (_: Exception) {
                    throw ValidationException("Invalid request body")
                }
            } else {
                PlaceDemandOrderRequest()
            }
            val demand = demandService.placeDemandOrder(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(demand))
        }
    }
}
