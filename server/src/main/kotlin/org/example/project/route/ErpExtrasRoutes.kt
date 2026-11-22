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
import org.example.project.service.ErpExtrasService

fun Route.erpExtrasRoutes(service: ErpExtrasService) {
    // Важно: не вешать RequireAuthPlugin на уже зарегистрированные префиксы (/api/tasks, /api/analytics, /api/production/orders, /api/projects/{id}),
    // чтобы избежать DuplicatePluginException. Каждый обработчик дополнительно проверяет call.currentUser().
    route("/api/tasks") {
        get("/{id}/dependencies") {
            val user = call.currentUser()
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid task id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.listTaskDependencies(id, user)))
        }
        post("/dependencies") {
            val user = call.currentUser()
            val request = call.receive<CreateTaskDependencyRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.addTaskDependency(request, user)))
        }
        delete("/dependencies/{depId}") {
            val user = call.currentUser()
            val depId = call.parameters["depId"]?.toLongOrNull() ?: throw ValidationException("Invalid dependency id")
            service.deleteTaskDependency(depId, user)
            call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("deleted" to true)))
        }
        get("/{id}/links") {
            val user = call.currentUser()
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid task id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.listTaskObjectLinks(id, user)))
        }
        post("/{id}/links") {
            val user = call.currentUser()
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid task id")
            val request = call.receive<CreateTaskObjectLinkRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.addTaskObjectLink(id, request, user)))
        }
        post("/bulk-action") {
            val user = call.currentUser()
            val request = call.receive<BulkTaskActionRequest>()
            val count = service.bulkTaskAction(request, user)
            call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("updatedCount" to count)))
        }
    }

    route("/api/crm") {
        install(RequireAuthPlugin) {}
        get("/counterparties") {
            val kind = call.request.queryParameters["kind"]?.let {
                runCatching { CounterpartyKind.valueOf(it) }.getOrNull()
                    ?: throw ValidationException("Invalid counterparty kind")
            }
            val search = call.request.queryParameters["search"]
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.listCounterparties(kind, search, call.currentUser())))
        }
        post("/counterparties") {
            val request = call.receive<CreateCounterpartyRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.createCounterparty(request, call.currentUser())))
        }
        put("/counterparties/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid counterparty id")
            val request = call.receive<UpdateCounterpartyRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.updateCounterparty(id, request, call.currentUser())))
        }
        post("/counterparties/{id}/contacts") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid counterparty id")
            val request = call.receive<CreateCounterpartyContactRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.addCounterpartyContact(id, request, call.currentUser())))
        }
    }

    route("/api/demands/merge") {
        install(RequireAuthPlugin) {}
        post {
            val request = call.receive<MergeDemandsRequest>()
            val mergedId = service.mergeDemands(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(mapOf("mergedDemandId" to mergedId)))
        }
    }

    route("/api/procurement") {
        install(RequireAuthPlugin) {}
        get("/plan") {
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.getProcurementPlan(call.currentUser())))
        }
    }

    route("/api/production") {
        get("/instances/{id}/documents") {
            val user = call.currentUser()
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid instance id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.listInstanceDocuments(id, user)))
        }
        post("/instances/{id}/documents") {
            val user = call.currentUser()
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid instance id")
            val request = call.receive<AttachInstanceDocumentRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.attachInstanceDocument(id, request, user)))
        }
        post("/orders/{id}/materials/{materialId}/substitute") {
            val user = call.currentUser()
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid order id")
            val matId = call.parameters["materialId"]?.toLongOrNull() ?: throw ValidationException("Invalid material id")
            val request = call.receive<SubstituteOrderMaterialRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.substituteOrderMaterial(id, matId, request, user)))
        }
    }

    route("/api/documents/templates") {
        install(RequireAuthPlugin) {}
        get {
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.listDocumentTemplates(call.currentUser())))
        }
        post {
            val request = call.receive<CreateDocumentTemplateRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.createDocumentTemplate(request, call.currentUser())))
        }
    }

    route("/api/delegations") {
        install(RequireAuthPlugin) {}
        get {
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.listDelegations(call.currentUser())))
        }
        post {
            val request = call.receive<CreateDelegationRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.createDelegation(request, call.currentUser())))
        }
        post("/{id}/revoke") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid delegation id")
            service.revokeDelegation(id, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("revoked" to true)))
        }
    }

    route("/api/projects/{id}/change-requests") {
        get {
            val user = call.currentUser()
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid project id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.listProjectChangeRequests(id, user)))
        }
        post {
            val user = call.currentUser()
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid project id")
            val request = call.receive<CreateProjectChangeRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.createProjectChangeRequest(id, request, user)))
        }
    }

    route("/api/analytics") {
        get("/print/production-order/{id}") {
            val user = call.currentUser()
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid order id")
            val html = service.renderProductionOrderPrintHtml(id, user)
            call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
        }
        post("/import/nomenclature") {
            val user = call.currentUser()
            val request = call.receive<CsvImportRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.importNomenclatureCsv(request, user)))
        }
        get("/executive-queues") {
            val user = call.currentUser()
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.getExecutiveQueues(user)))
        }
    }

    route("/api/wms/locations/suggest") {
        install(RequireAuthPlugin) {}
        get {
            val warehouseId = call.request.queryParameters["warehouseId"]?.toLongOrNull()
                ?: throw ValidationException("warehouseId is required")
            val nomenclatureId = call.request.queryParameters["nomenclatureId"]?.toLongOrNull()
                ?: throw ValidationException("nomenclatureId is required")
            val locId = service.suggestStorageLocation(warehouseId, nomenclatureId, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("locationId" to locId)))
        }
    }
}
