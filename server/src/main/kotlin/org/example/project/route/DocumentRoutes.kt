package org.example.project.route

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ByteReadChannel
import org.example.project.dto.*
import org.example.project.exception.ValidationException
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.AttachmentService
import org.example.project.service.DocumentService

fun Route.documentRoutes(documentService: DocumentService) {
    route("/api") {
        route("/documents") {
            install(RequireAuthPlugin) {}
            post {
                val request = call.receive<CreateDocumentRequest>()
                val document = documentService.create(request, call.currentUser())
                call.respond(HttpStatusCode.Created, ApiResponse.success(document))
            }

            get {
                val documents = documentService.list(call.currentUser())
                call.respond(HttpStatusCode.OK, ApiResponse.success(documents))
            }

            get("/{id}") {
                val id = call.longParam("id")
                val document = documentService.get(id, call.currentUser())
                call.respond(HttpStatusCode.OK, ApiResponse.success(document))
            }

            post("/{id}/update") {
                val id = call.longParam("id")
                val request = call.receive<UpdateDocumentRequest>()
                val document = documentService.update(id, request, call.currentUser())
                call.respond(HttpStatusCode.OK, ApiResponse.success(document))
            }

            post("/{id}/versions") {
                val id = call.longParam("id")
                val version = uploadVersionFromMultipart(documentService, id)
                call.respond(HttpStatusCode.Created, ApiResponse.success(version))
            }

            post("/{id}/versions/{versionId}/approve") {
                val id = call.longParam("id")
                val versionId = call.longParam("versionId")
                val request = call.receive<VersionTransitionRequest>()
                val version = documentService.approve(id, versionId, request, call.currentUser())
                call.respond(HttpStatusCode.OK, ApiResponse.success(version))
            }

            post("/{id}/versions/{versionId}/activate") {
                val id = call.longParam("id")
                val versionId = call.longParam("versionId")
                val request = call.receive<VersionTransitionRequest>()
                val version = documentService.activate(id, versionId, request, call.currentUser())
                call.respond(HttpStatusCode.OK, ApiResponse.success(version))
            }

            post("/{id}/versions/{versionId}/cancel") {
                val id = call.longParam("id")
                val versionId = call.longParam("versionId")
                val request = call.receive<VersionTransitionRequest>()
                val version = documentService.cancel(id, versionId, request, call.currentUser())
                call.respond(HttpStatusCode.OK, ApiResponse.success(version))
            }

            post("/{id}/links") {
                val id = call.longParam("id")
                val request = call.receive<CreateDocumentLinkRequest>()
                val link = documentService.addLink(id, request, call.currentUser())
                call.respond(HttpStatusCode.Created, ApiResponse.success(link))
            }

            delete("/{id}/links/{linkId}") {
                val id = call.longParam("id")
                val linkId = call.longParam("linkId")
                val link = documentService.removeLink(id, linkId, call.currentUser())
                call.respond(HttpStatusCode.OK, ApiResponse.success(link))
            }
        }

        route("/document-versions") {
            install(RequireAuthPlugin) {}

            get("/{versionId}/download") {
                val versionId = call.longParam("versionId")
                val file = documentService.download(
                    versionId = versionId,
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
        }
    }
}

private fun ApplicationCall.longParam(name: String): Long {
    return parameters[name]?.toLongOrNull()
        ?: throw ValidationException("Invalid $name", mapOf(name to "invalid"))
}

private suspend fun RoutingContext.uploadVersionFromMultipart(
    documentService: DocumentService,
    documentId: Long
): DocumentVersionDto {
    val multipart = call.receiveMultipart()
    var fileName: String? = null
    var mimeType: String? = null
    var content: ByteArray? = null
    var changeReason: String? = null
    var part = multipart.readPart()
    while (part != null) {
        when (part) {
            is PartData.FileItem -> {
                try {
                    if (content == null) {
                        fileName = part.originalFileName ?: ""
                        mimeType = part.headers[HttpHeaders.ContentType]
                        content = AttachmentService.readContentLimited(part.provider())
                    }
                } finally {
                    part.release()
                }
            }
            is PartData.FormItem -> {
                if (part.name == "changeReason") changeReason = part.value
                part.release()
            }
            else -> part.release()
        }
        part = multipart.readPart()
    }
    val bytes = content
        ?: throw ValidationException("File part is required", mapOf("file" to "required"))
    return documentService.uploadVersion(
        documentId = documentId,
        originalFileName = fileName ?: "",
        contentType = mimeType,
        changeReason = changeReason,
        channel = ByteReadChannel(bytes),
        currentUser = call.currentUser()
    )
}
