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
import org.example.project.service.SupplierClaimService

fun Route.supplierClaimRoutes(service: SupplierClaimService) {
    route("/api/supplier-claims") {
        install(RequireAuthPlugin) {}

        get {
            val supplierId = call.request.queryParameters["supplierId"]?.toLongOrNull()
            val poId = call.request.queryParameters["purchaseOrderId"]?.toLongOrNull()
            val status = call.request.queryParameters["status"]?.let {
                runCatching { SupplierClaimStatus.valueOf(it) }.getOrNull()
                    ?: throw ValidationException("Invalid claim status")
            }
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.list(supplierId, poId, status, call.currentUser())))
        }

        post {
            val request = call.receive<CreateSupplierClaimRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.create(request, call.currentUser())))
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid claim id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.get(id, call.currentUser())))
        }

        post("/{id}/transition") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid claim id")
            val request = call.receive<TransitionSupplierClaimRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.transition(id, request, call.currentUser())))
        }
    }
}
