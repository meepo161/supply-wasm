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
import org.example.project.service.BomService

fun Route.bomRoutes(bomService: BomService) {
    route("/api/bom") {
        install(RequireAuthPlugin) {}

        // Product models
        post("/models") {
            val request = call.receive<CreateProductModelRequest>()
            val model = bomService.createProductModel(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(model))
        }

        get("/models") {
            call.respond(HttpStatusCode.OK, ApiResponse.success(bomService.listProductModels(call.currentUser())))
        }

        get("/models/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid product model id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(bomService.getProductModel(id, call.currentUser())))
        }

        put("/models/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid product model id")
            val request = call.receive<UpdateProductModelRequest>()
            val model = bomService.updateProductModel(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(model))
        }

        // Revisions
        post("/models/{id}/revisions") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid product model id")
            val request = call.receive<CreateBomRevisionRequest>()
            val revision = bomService.createRevision(id, request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(revision))
        }

        get("/models/{id}/revisions") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid product model id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(bomService.listRevisions(id, call.currentUser())))
        }

        get("/revisions/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid revision id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(bomService.getRevision(id, call.currentUser())))
        }

        post("/revisions/{id}/copy") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid revision id")
            val request = call.receive<CopyBomRevisionRequest>()
            val revision = bomService.copyRevision(id, request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(revision))
        }

        post("/revisions/{id}/approve") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid revision id")
            val request = call.receive<ApproveBomRevisionRequest>()
            val revision = bomService.approveRevision(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(revision))
        }

        post("/revisions/{id}/activate") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid revision id")
            val request = call.receive<TransitionBomRevisionRequest>()
            val revision = bomService.activateRevision(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(revision))
        }

        post("/revisions/{id}/replace") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid revision id")
            val request = call.receive<TransitionBomRevisionRequest>()
            val revision = bomService.replaceRevision(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(revision))
        }

        post("/revisions/{id}/cancel") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid revision id")
            val request = call.receive<TransitionBomRevisionRequest>()
            val revision = bomService.cancelRevision(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(revision))
        }

        // BOM lines
        get("/revisions/{id}/lines") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid revision id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(bomService.listBomLines(id, call.currentUser())))
        }

        post("/revisions/{id}/lines") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid revision id")
            val request = call.receive<CreateBomLineRequest>()
            val line = bomService.createBomLine(id, request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(line))
        }

        put("/revisions/{id}/lines/{lineId}") {
            val lineId = call.parameters["lineId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid line id")
            val request = call.receive<UpdateBomLineRequest>()
            val line = bomService.updateBomLine(lineId, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(line))
        }

        delete("/revisions/{id}/lines/{lineId}") {
            val lineId = call.parameters["lineId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid line id")
            bomService.deleteBomLine(lineId, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(Unit))
        }

        // Explosion
        post("/revisions/{id}/explode") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid revision id")
            val request = call.receive<BomExplodeRequest>()
            val report = bomService.explodeRevision(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(report))
        }

        // Diff
        get("/revisions/{id}/diff") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid revision id")
            val otherId = call.request.queryParameters["otherId"]?.toLongOrNull()
                ?: throw ValidationException("otherId query parameter is required")
            call.respond(HttpStatusCode.OK, ApiResponse.success(bomService.diffRevisions(id, otherId, call.currentUser())))
        }

        // Where-used
        get("/nomenclature/{id}/where-used") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid nomenclature id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(bomService.whereUsed(id, call.currentUser())))
        }
    }
}
