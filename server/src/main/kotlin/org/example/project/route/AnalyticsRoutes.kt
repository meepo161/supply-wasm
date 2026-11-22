package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.ApiResponse
import org.example.project.dto.CreateDocumentTemplateRequest
import org.example.project.exception.ValidationException
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.AnalyticsService

fun Route.analyticsRoutes(service: AnalyticsService) {
    route("/api/analytics") {
        install(RequireAuthPlugin) {}

        get("/summary") {
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.summary(call.currentUser())))
        }

        get("/calendar") {
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.calendar(call.currentUser())))
        }

        get("/export/{domain}.csv") {
            val domain = call.parameters["domain"] ?: throw ValidationException("Export domain is required")
            val csv = service.exportCsv(domain, call.currentUser())
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "$domain.csv").toString()
            )
            call.respondText(csv, ContentType.Text.CSV, HttpStatusCode.OK)
        }
    }

    route("/api/document-templates") {
        install(RequireAuthPlugin) {}

        get {
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.listDocumentTemplates(call.currentUser())))
        }

        post {
            val request = call.receive<CreateDocumentTemplateRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.createDocumentTemplate(request, call.currentUser())))
        }
    }
}
