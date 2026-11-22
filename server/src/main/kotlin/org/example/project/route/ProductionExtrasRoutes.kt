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
import org.example.project.service.ProductionExtrasService

fun Route.productionExtrasRoutes(service: ProductionExtrasService) {
    route("/api/production") {
        install(RequireAuthPlugin) {}

        get("/orders/{id}/materials") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid order id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.listMaterials(id, call.currentUser())))
        }

        post("/orders/{id}/materials/init-from-bom") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid order id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.initMaterialsFromBom(id, call.currentUser())))
        }

        post("/orders/{id}/materials/{materialId}/allocate") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid order id")
            val matId = call.parameters["materialId"]?.toLongOrNull() ?: throw ValidationException("Invalid material id")
            val request = call.receive<AllocateProductionMaterialRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.allocateMaterial(id, matId, request, call.currentUser())))
        }

        post("/orders/{id}/materials/{materialId}/consume") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid order id")
            val matId = call.parameters["materialId"]?.toLongOrNull() ?: throw ValidationException("Invalid material id")
            val request = call.receive<ConsumeProductionMaterialRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.consumeMaterial(id, matId, request, call.currentUser())))
        }

        get("/orders/{id}/engineering-changes") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid order id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.listEngineeringChanges(id, call.currentUser())))
        }

        post("/orders/{id}/engineering-changes") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid order id")
            val request = call.receive<CreateEngineeringChangeRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.createEngineeringChange(id, request, call.currentUser())))
        }

        post("/engineering-changes/{changeId}/decide") {
            val changeId = call.parameters["changeId"]?.toLongOrNull() ?: throw ValidationException("Invalid changeId")
            val request = call.receive<DecideEngineeringChangeRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.decideEngineeringChange(changeId, request, call.currentUser())))
        }

        get("/nonconformities") {
            val objectType = call.request.queryParameters["objectType"]
            val objectId = call.request.queryParameters["objectId"]?.toLongOrNull()
            val status = call.request.queryParameters["status"]?.let {
                runCatching { NonconformityStatus.valueOf(it) }.getOrNull()
                    ?: throw ValidationException("Invalid status")
            }
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.listNonconformities(objectType, objectId, status, call.currentUser())))
        }

        post("/nonconformities") {
            val request = call.receive<CreateNonconformityRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.createNonconformity(request, call.currentUser())))
        }

        post("/nonconformities/{id}/transition") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid nonconformity id")
            val request = call.receive<TransitionNonconformityRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.transitionNonconformity(id, request, call.currentUser())))
        }
    }
}
