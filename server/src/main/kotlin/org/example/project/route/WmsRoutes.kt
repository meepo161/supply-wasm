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
import org.example.project.service.WmsService

fun Route.wmsRoutes(wmsService: WmsService) {
    route("/api/wms") {
        install(RequireAuthPlugin) {}

        post("/warehouses") {
            val request = call.receive<CreateWarehouseRequest>()
            val warehouse = wmsService.createWarehouse(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(warehouse))
        }

        get("/warehouses") {
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.listWarehouses(call.currentUser())))
        }

        post("/locations") {
            val request = call.receive<CreateStorageLocationRequest>()
            val location = wmsService.createLocation(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(location))
        }

        get("/warehouses/{id}/locations") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid warehouse id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.listLocations(id, call.currentUser())))
        }

        post("/nomenclature") {
            val request = call.receive<CreateNomenclatureRequest>()
            val nomenclature = wmsService.createNomenclature(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(nomenclature))
        }

        get("/nomenclature") {
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.listNomenclature(call.currentUser())))
        }

        post("/nomenclature/{id}/archive") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid nomenclature id")
            val nomenclature = wmsService.archiveNomenclature(id, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(nomenclature))
        }

        get("/stock") {
            val warehouseId = call.request.queryParameters["warehouseId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid warehouseId")
            }
            val nomenclatureId = call.request.queryParameters["nomenclatureId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid nomenclatureId")
            }
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.getStock(warehouseId, nomenclatureId, call.currentUser())))
        }

        post("/receipts") {
            val request = call.receive<CreateReceiptRequest>()
            val receipt = wmsService.createReceipt(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(receipt))
        }

        get("/receipts/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid receipt id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.getReceipt(id, call.currentUser())))
        }

        post("/receipts/{id}/post") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid receipt id")
            val request = call.receive<PostReceiptRequest>()
            val receipt = wmsService.postReceipt(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(receipt))
        }

        post("/reservations") {
            val request = call.receive<CreateReservationRequest>()
            val reservation = wmsService.createReservation(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(reservation))
        }

        get("/reservations") {
            val warehouseId = call.request.queryParameters["warehouseId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid warehouseId")
            }
            val nomenclatureId = call.request.queryParameters["nomenclatureId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid nomenclatureId")
            }
            val status = call.request.queryParameters["status"]?.let {
                try {
                    ReservationStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid reservation status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(wmsService.listReservations(warehouseId, nomenclatureId, status, call.currentUser()))
            )
        }

        get("/reservations/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid reservation id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.getReservation(id, call.currentUser())))
        }

        post("/reservations/{id}/release") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid reservation id")
            val request = call.receive<ReleaseReservationRequest>()
            val reservation = wmsService.releaseReservation(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(reservation))
        }
    }
}
