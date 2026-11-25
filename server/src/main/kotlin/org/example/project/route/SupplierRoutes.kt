package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.exception.ValidationException
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.SupplierService

// Ответы команд возвращаются сохранённым JSON-телом (идемпотентный повтор),
// поэтому используется respondText, а не respond с повторной сериализацией.
fun Route.supplierRoutes(supplierService: SupplierService) {
    route("/api/suppliers") {
        install(RequireAuthPlugin) {}

        get {
            val search = call.request.queryParameters["search"]
            val status = call.request.queryParameters["verificationStatus"]?.let {
                try {
                    SupplierVerificationStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid verification status")
                }
            }
            val archived = call.request.queryParameters["archived"]?.toBooleanStrictOrNull()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(
                    supplierService.listSuppliers(search, status, archived, call.currentUser())
                )
            )
        }

        post {
            val request = call.receive<CreateSupplierRequest>()
            val result = supplierService.createSupplier(request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid supplier id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(supplierService.getSupplier(id, call.currentUser())))
        }

        put("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid supplier id")
            val request = call.receive<UpdateSupplierRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(supplierService.updateSupplier(id, request, call.currentUser()))
            )
        }

        post("/{id}/verification") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid supplier id")
            val request = call.receive<SupplierVerificationRequest>()
            val result = supplierService.verifySupplier(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/archive") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid supplier id")
            val version = call.request.queryParameters["version"]?.toLongOrNull()
                ?: throw ValidationException("version is required")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(
                    supplierService.archiveSupplier(id, version, true, call.currentUser())
                )
            )
        }

        post("/{id}/restore") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid supplier id")
            val version = call.request.queryParameters["version"]?.toLongOrNull()
                ?: throw ValidationException("version is required")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(
                    supplierService.archiveSupplier(id, version, false, call.currentUser())
                )
            )
        }

        post("/{id}/contacts") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid supplier id")
            val request = call.receive<AddSupplierContactRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(supplierService.addContact(id, request, call.currentUser()))
            )
        }

        post("/{id}/addresses") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid supplier id")
            val request = call.receive<AddSupplierAddressRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(supplierService.addAddress(id, request, call.currentUser()))
            )
        }

        post("/{id}/documents") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid supplier id")
            val request = call.receive<AddSupplierDocumentRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(supplierService.addDocument(id, request, call.currentUser()))
            )
        }

        put("/{id}/categories") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid supplier id")
            val request = call.receive<SetSupplierCategoriesRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(supplierService.setCategories(id, request, call.currentUser()))
            )
        }

        get("/offers/{id}/invoice-file") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid offer id")
            val file = supplierService.downloadOfferInvoiceFile(
                offerId = id,
                ipAddress = call.request.origin.remoteHost,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
                currentUser = call.currentUser()
            )
            val contentType = file.mimeType
                ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                ?: ContentType.Application.OctetStream
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileNameAsterisk, file.fileName)
                    .toString()
            )
            call.respondBytes(file.content, contentType)
        }

        post("/offers/{id}/return-for-revision") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid offer id")
            val request = call.receive<ReturnOfferForRevisionRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(supplierService.returnOfferForRevision(id, request, call.currentUser()))
            )
        }
    }

    route("/api/supplier-categories") {
        install(RequireAuthPlugin) {}

        get {
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(supplierService.listCategories(call.currentUser()))
            )
        }

        post {
            val request = call.receive<CreateSupplierCategoryRequest>()
            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(supplierService.createCategory(request, call.currentUser()))
            )
        }
    }

    route("/api/supplier-offers") {
        install(RequireAuthPlugin) {}

        get {
            val demandId = call.request.queryParameters["demandId"]?.toLongOrNull()
            val supplierId = call.request.queryParameters["supplierId"]?.toLongOrNull()
            val status = call.request.queryParameters["status"]?.let {
                try {
                    SupplierOfferStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid offer status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(
                    supplierService.listOffers(demandId, supplierId, status, call.currentUser())
                )
            )
        }

        post {
            val request = call.receive<CreateSupplierOfferRequest>()
            val result = supplierService.createOffer(request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid offer id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(supplierService.getOffer(id, call.currentUser())))
        }

        put("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid offer id")
            val request = call.receive<UpdateSupplierOfferRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(supplierService.updateOffer(id, request, call.currentUser()))
            )
        }

        post("/{id}/status") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid offer id")
            val request = call.receive<SupplierOfferStatusRequest>()
            val result = supplierService.changeOfferStatus(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/select") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid offer id")
            val request = call.receive<SelectSupplierOfferRequest>()
            val result = supplierService.selectOffer(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/{id}/clear-selection") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid offer id")
            val version = call.request.queryParameters["version"]?.toLongOrNull()
                ?: throw ValidationException("version is required")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(supplierService.clearSelection(id, version, call.currentUser()))
            )
        }

        get("/{id}/history") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid offer id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(supplierService.listOfferHistory(id, call.currentUser()))
            )
        }
    }

    route("/api/demands/{demandId}/supplier-offers") {
        install(RequireAuthPlugin) {}

        // Сравнение предложений по потребности (§9.6).
        get("/comparison") {
            val demandId = call.parameters["demandId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid demand id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(supplierService.compareOffers(demandId, call.currentUser()))
            )
        }
    }
}
