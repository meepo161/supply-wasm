package org.example.project.route

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.exception.ValidationException
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.AttachmentService

fun Route.attachmentRoutes(attachmentService: AttachmentService) {
    route("/api") {
        route("/tasks/{taskId}/attachments") {
            install(RequireAuthPlugin) {}

            get {
                val taskId = call.parameters["taskId"]?.toLongOrNull()
                    ?: throw ValidationException("Invalid task id", mapOf("taskId" to "invalid"))
                val attachments = attachmentService.list(AttachmentEntityType.TASK, taskId, call.currentUser())
                call.respond(HttpStatusCode.OK, ApiResponse.success(attachments))
            }

            post {
                val taskId = call.parameters["taskId"]?.toLongOrNull()
                    ?: throw ValidationException("Invalid task id", mapOf("taskId" to "invalid"))
                val attachment = uploadFromMultipart(attachmentService, AttachmentEntityType.TASK, taskId)
                call.respond(HttpStatusCode.Created, ApiResponse.success(attachment))
            }
        }

        route("/projects/{projectId}/attachments") {
            install(RequireAuthPlugin) {}

            get {
                val projectId = call.parameters["projectId"]?.toLongOrNull()
                    ?: throw ValidationException("Invalid project id", mapOf("projectId" to "invalid"))
                val attachments = attachmentService.list(AttachmentEntityType.PROJECT, projectId, call.currentUser())
                call.respond(HttpStatusCode.OK, ApiResponse.success(attachments))
            }

            post {
                val projectId = call.parameters["projectId"]?.toLongOrNull()
                    ?: throw ValidationException("Invalid project id", mapOf("projectId" to "invalid"))
                val attachment = uploadFromMultipart(attachmentService, AttachmentEntityType.PROJECT, projectId)
                call.respond(HttpStatusCode.Created, ApiResponse.success(attachment))
            }
        }

        // Изображения и файлы карточки номенклатуры (D-63).
        route("/wms/nomenclature/{nomenclatureId}/attachments") {
            install(RequireAuthPlugin) {}

            get {
                val nomenclatureId = call.parameters["nomenclatureId"]?.toLongOrNull()
                    ?: throw ValidationException("Invalid nomenclature id", mapOf("nomenclatureId" to "invalid"))
                val attachments = attachmentService.list(AttachmentEntityType.NOMENCLATURE, nomenclatureId, call.currentUser())
                call.respond(HttpStatusCode.OK, ApiResponse.success(attachments))
            }

            post {
                val nomenclatureId = call.parameters["nomenclatureId"]?.toLongOrNull()
                    ?: throw ValidationException("Invalid nomenclature id", mapOf("nomenclatureId" to "invalid"))
                val attachment = uploadFromMultipart(attachmentService, AttachmentEntityType.NOMENCLATURE, nomenclatureId)
                call.respond(HttpStatusCode.Created, ApiResponse.success(attachment))
            }
        }

        route("/attachments") {
            install(RequireAuthPlugin) {}

            get("/{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: throw ValidationException("Invalid attachment id", mapOf("id" to "invalid"))
                val file = attachmentService.download(id, call.currentUser())
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

            delete("/{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: throw ValidationException("Invalid attachment id", mapOf("id" to "invalid"))
                val deleted = attachmentService.delete(id, call.currentUser())
                call.respond(HttpStatusCode.OK, ApiResponse.success(deleted))
            }
        }
    }
}

private suspend fun RoutingContext.uploadFromMultipart(
    attachmentService: AttachmentService,
    entityType: AttachmentEntityType,
    entityId: Long
): AttachmentDto {
    val multipart = call.receiveMultipart()
    var part = multipart.readPart()
    while (part != null) {
        if (part is PartData.FileItem) {
            try {
                return attachmentService.upload(
                    entityType = entityType,
                    entityId = entityId,
                    originalFileName = part.originalFileName ?: "",
                    contentType = part.contentType?.toString(),
                    channel = part.provider(),
                    currentUser = call.currentUser()
                )
            } finally {
                part.release()
            }
        }
        part.release()
        part = multipart.readPart()
    }
    throw ValidationException("File part is required", mapOf("file" to "required"))
}
