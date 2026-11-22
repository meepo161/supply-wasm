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
import org.example.project.service.ProductionService

fun Route.productionRoutes(productionService: ProductionService) {
    route("/api/production/orders") {
        install(RequireAuthPlugin) {}

        post {
            val request = call.receive<CreateProductionOrderRequest>()
            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(productionService.create(request, call.currentUser()))
            )
        }

        get {
            val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()
            val productModelId = call.request.queryParameters["productModelId"]?.toLongOrNull()
            val status = call.request.queryParameters["status"]?.let {
                try {
                    ProductionOrderStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid production order status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(
                    productionService.list(projectId, productModelId, status, call.currentUser())
                )
            )
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production order id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.get(id, call.currentUser()))
            )
        }

        post("/{id}/plan") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production order id")
            val request = call.receive<ProductionOrderActionRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.plan(id, request, call.currentUser()))
            )
        }

        post("/{id}/start") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production order id")
            val request = call.receive<ProductionOrderActionRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.start(id, request, call.currentUser()))
            )
        }

        post("/{id}/hold") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production order id")
            val request = call.receive<ProductionOrderActionRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.hold(id, request, call.currentUser()))
            )
        }

        post("/{id}/resume") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production order id")
            val request = call.receive<ProductionOrderActionRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.resume(id, request, call.currentUser()))
            )
        }

        post("/{id}/complete") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production order id")
            val request = call.receive<CompleteProductionOrderRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.complete(id, request, call.currentUser()))
            )
        }

        post("/{id}/close") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production order id")
            val request = call.receive<ProductionOrderActionRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.close(id, request, call.currentUser()))
            )
        }

        post("/{id}/cancel") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production order id")
            val request = call.receive<CancelProductionOrderRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.cancel(id, request, call.currentUser()))
            )
        }

        get("/{id}/instances") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production order id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.listInstances(id, call.currentUser()))
            )
        }

        post("/{id}/stages/{stageId}/start") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production order id")
            val stageId = call.parameters["stageId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production stage id")
            val request = call.receive<ProductionStageActionRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.startStage(id, stageId, request, call.currentUser()))
            )
        }

        post("/{id}/stages/{stageId}/complete") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production order id")
            val stageId = call.parameters["stageId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production stage id")
            val request = call.receive<ProductionStageActionRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.completeStage(id, stageId, request, call.currentUser()))
            )
        }

        post("/{id}/stages/{stageId}/skip") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production order id")
            val stageId = call.parameters["stageId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid production stage id")
            val request = call.receive<ProductionStageActionRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.skipStage(id, stageId, request, call.currentUser()))
            )
        }
    }

    route("/api/production/instances") {
        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid product instance id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(productionService.getInstance(id, call.currentUser())))
        }
        post("/{id}/tests") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid product instance id")
            val request = call.receive<RecordInstanceTestRequest>()
            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(productionService.recordInstanceTest(id, request, call.currentUser()))
            )
        }
        post("/{id}/remarks") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid product instance id")
            val request = call.receive<RecordInstanceRemarkRequest>()
            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(productionService.recordInstanceRemark(id, request, call.currentUser()))
            )
        }
        post("/{id}/software") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid product instance id")
            val request = call.receive<AddInstanceSoftwareRequest>()
            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(productionService.addInstanceSoftware(id, request, call.currentUser()))
            )
        }
        post("/{id}/remarks/{remarkId}/resolve") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid product instance id")
            val remarkId = call.parameters["remarkId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid remark id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.resolveInstanceRemark(id, remarkId, call.currentUser()))
            )
        }
        post("/{id}/accept") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid product instance id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.acceptInstance(id, call.currentUser()))
            )
        }
        post("/{id}/ship") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid product instance id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(productionService.shipInstance(id, call.currentUser()))
            )
        }
    }
}
