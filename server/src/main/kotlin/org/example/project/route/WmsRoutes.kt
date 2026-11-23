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

        get("/nomenclature/{id}/details") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid nomenclature id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.getNomenclatureDetails(id, call.currentUser())))
        }

        post("/nomenclature/{id}/archive") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid nomenclature id")
            val nomenclature = wmsService.archiveNomenclature(id, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(nomenclature))
        }

        put("/nomenclature/{id}/thresholds") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid nomenclature id")
            val request = call.receive<UpdateNomenclatureThresholdsRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.updateNomenclatureThresholds(id, request, call.currentUser())))
        }

        get("/nomenclature/by-barcode/{code}") {
            val code = call.parameters["code"] ?: throw ValidationException("Barcode is required")
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.findByBarcode(code, call.currentUser())))
        }

        post("/nomenclature/{id}/barcodes") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid nomenclature id")
            val request = call.receive<CreateNomenclatureBarcodeRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(wmsService.addBarcode(id, request, call.currentUser())))
        }

        delete("/nomenclature/{id}/barcodes/{barcodeId}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid nomenclature id")
            val barcodeId = call.parameters["barcodeId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid barcode id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.deleteBarcode(id, barcodeId, call.currentUser())))
        }

        get("/nomenclature/{id}/unit-conversions") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid nomenclature id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.listUnitConversions(id, call.currentUser())))
        }

        post("/nomenclature/{id}/unit-conversions") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid nomenclature id")
            val request = call.receive<CreateUnitConversionRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(wmsService.createUnitConversion(id, request, call.currentUser())))
        }

        get("/nomenclature/{id}/analogs") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid nomenclature id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.listAnalogs(id, call.currentUser())))
        }

        post("/nomenclature/{id}/analogs") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid nomenclature id")
            val request = call.receive<CreateNomenclatureAnalogRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(wmsService.createAnalog(id, request, call.currentUser())))
        }

        get("/stock") {
            val warehouseId = call.request.queryParameters["warehouseId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid warehouseId")
            }
            val nomenclatureId = call.request.queryParameters["nomenclatureId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid nomenclatureId")
            }
            val locationId = call.request.queryParameters["locationId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid locationId")
            }
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.getStock(warehouseId, nomenclatureId, call.currentUser(), locationId)))
        }

        get("/movements") {
            val warehouseId = call.request.queryParameters["warehouseId"]?.toLongOrNull()
            val nomenclatureId = call.request.queryParameters["nomenclatureId"]?.toLongOrNull()
            val type = call.request.queryParameters["type"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.listMovements(warehouseId, nomenclatureId, type, limit, call.currentUser())))
        }

        get("/reports/low-stock") {
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.listLowStock(call.currentUser())))
        }

        get("/lots") {
            val nomenclatureId = call.request.queryParameters["nomenclatureId"]?.toLongOrNull()
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.listLots(nomenclatureId, call.currentUser())))
        }

        get("/serials") {
            val nomenclatureId = call.request.queryParameters["nomenclatureId"]?.toLongOrNull()
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsService.listSerials(nomenclatureId, call.currentUser())))
        }

        // Приёмки обрабатываются в receiptRoutes: там же находятся основания,
        // расхождения, проведение и поздние связи. Путь /api/wms/receipts* не
        // должен дублироваться в wmsRoutes, иначе Ktor выберет первый обработчик.

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
