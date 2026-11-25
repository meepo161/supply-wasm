package org.example.project.route

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import org.example.project.dto.*
import org.example.project.exception.ValidationException
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.SupplierInvoiceService

/**
 * Счёт поставщика (D-61, D-62): регистрация с обязательным файлом, сверка
 * с заказом и поступление на склад, которое оформляет снабжение.
 */
fun Route.supplierInvoiceRoutes(service: SupplierInvoiceService) {
    route("/api/supplier-invoices") {
        install(RequireAuthPlugin) {}

        get {
            val status = call.request.queryParameters["status"]?.let {
                runCatching { SupplierInvoiceStatus.valueOf(it) }.getOrNull()
                    ?: throw ValidationException("Invalid invoice status", mapOf("status" to "invalid"))
            }
            val supplierId = call.request.queryParameters["supplierId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid supplierId")
            }
            val orderId = call.request.queryParameters["purchaseOrderId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid purchaseOrderId")
            }
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.list(status, supplierId, orderId, call.currentUser())))
        }

        post {
            val request = call.receive<SupplierInvoiceInput>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.create(request, call.currentUser())))
        }

        get("/{id}") {
            val id = call.longParamOrThrow("id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.get(id, call.currentUser())))
        }

        put("/{id}") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<UpdateSupplierInvoiceRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.update(id, request, call.currentUser())))
        }

        get("/{id}/check") {
            val id = call.longParamOrThrow("id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.check(id, call.currentUser())))
        }

        post("/{id}/confirm-check") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<ConfirmSupplierInvoiceCheckRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.confirmCheck(id, request, call.currentUser())))
        }

        post("/{id}/reopen") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<SupplierInvoiceActionRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.reopen(id, request, call.currentUser())))
        }

        post("/{id}/cancel") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<CancelSupplierInvoiceRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.cancel(id, request, call.currentUser())))
        }

        post("/{id}/receipt") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<CreateReceiptFromInvoiceRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(service.createReceipt(id, request, call.currentUser())))
        }

        get("/{id}/history") {
            val id = call.longParamOrThrow("id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.history(id, call.currentUser())))
        }

        post("/parse-pdf") {
            val docId = call.request.queryParameters["documentId"]?.toLongOrNull()
            if (docId != null) {
                call.respond(HttpStatusCode.OK, ApiResponse.success(service.parseDocumentPdf(docId, call.currentUser())))
                return@post
            }
            val multipart = call.receiveMultipart()
            var pdfBytes: ByteArray? = null
            multipart.forEachPart { part ->
                if (part is PartData.FileItem && pdfBytes == null) {
                    pdfBytes = part.provider().toByteArray()
                }
                part.dispose()
            }
            val bytes = pdfBytes ?: throw ValidationException("PDF-файл или documentId не переданы")
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.parsePdf(bytes, call.currentUser())))
        }

        get("/{id}/preview") {
            val id = call.longParamOrThrow("id")
            val preview = service.previewHtml(id, call.currentUser())
            if (call.request.queryParameters["raw"] == "true") {
                call.respondText(preview.htmlContent, ContentType.Text.Html)
            } else {
                call.respond(HttpStatusCode.OK, ApiResponse.success(preview))
            }
        }

        get("/{id}/file") {
            val id = call.longParamOrThrow("id")
            val file = service.downloadFile(
                id = id,
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
