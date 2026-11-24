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
import org.example.project.service.DocumentService
import org.example.project.service.NomenclatureCardService

/** Карточка номенклатуры (NSI-001, D-63): чтение — `wms:read`, изменение — `wms:manage`. */
fun Route.nomenclatureCardRoutes(cardService: NomenclatureCardService, documentService: DocumentService) {
    // Плагин ставится на собственные узлы: узел /api/wms уже защищён в wmsRoutes,
    // повторная установка на тот же узел даёт DuplicatePluginException.
    route("/api/wms") {
        route("/nomenclature/{id}") {
            install(RequireAuthPlugin) {}

            get("/card") {
                call.respond(HttpStatusCode.OK, ApiResponse.success(cardService.getCard(call.nomenclatureId(), call.currentUser())))
            }

            put {
                val request = call.receive<UpdateNomenclatureRequest>()
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(cardService.updateCard(call.nomenclatureId(), request, call.currentUser()))
                )
            }

            get("/stock-history") {
                val days = call.request.queryParameters["days"]?.let {
                    it.toIntOrNull() ?: throw ValidationException("Invalid days")
                } ?: 90
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(cardService.stockHistory(call.nomenclatureId(), days, call.currentUser()))
                )
            }

            get("/movements") {
                val params = call.request.queryParameters
                val before = params["before"]?.let { it.toLongOrNull() ?: throw ValidationException("Invalid before") }
                val limit = params["limit"]?.let { it.toIntOrNull() ?: throw ValidationException("Invalid limit") } ?: 50
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        cardService.movements(
                            id = call.nomenclatureId(),
                            type = params["type"],
                            from = params["from"],
                            to = params["to"],
                            before = before,
                            limit = limit,
                            currentUser = call.currentUser()
                        )
                    )
                )
            }

            get("/reservations") {
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(cardService.reservations(call.nomenclatureId(), call.currentUser()))
                )
            }

            get("/usage") {
                call.respond(HttpStatusCode.OK, ApiResponse.success(cardService.usage(call.nomenclatureId(), call.currentUser())))
            }

            get("/history") {
                call.respond(HttpStatusCode.OK, ApiResponse.success(cardService.history(call.nomenclatureId(), call.currentUser())))
            }

            get("/documents") {
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(documentService.listByNomenclature(call.nomenclatureId(), call.currentUser()))
                )
            }
        }

        route("/nomenclature-categories") {
            install(RequireAuthPlugin) {}

            get {
                call.respond(HttpStatusCode.OK, ApiResponse.success(cardService.listCategories(call.currentUser())))
            }

            post {
                val request = call.receive<CreateNomenclatureCategoryRequest>()
                call.respond(HttpStatusCode.Created, ApiResponse.success(cardService.createCategory(request, call.currentUser())))
            }

            put("/{categoryId}") {
                val categoryId = call.parameters["categoryId"]?.toLongOrNull()
                    ?: throw ValidationException("Invalid category id")
                val request = call.receive<UpdateNomenclatureCategoryRequest>()
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(cardService.updateCategory(categoryId, request, call.currentUser()))
                )
            }
        }
    }
}

private fun ApplicationCall.nomenclatureId(): Long =
    parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid nomenclature id")
