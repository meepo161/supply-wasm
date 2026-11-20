package org.example.project.data

import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData

fun messageFormData(
    content: String,
    idempotencyKey: String,
    files: List<AttachmentFileData>,
    replyToMessageId: Long? = null
): List<PartData> =
    formData {
        append("content", content)
        append("idempotencyKey", idempotencyKey)
        replyToMessageId?.let { append("replyToMessageId", it.toString()) }
        files.forEach { file ->
            append(
                "files",
                file.bytes,
                Headers.build {
                    append(
                        HttpHeaders.ContentType,
                        file.mimeType ?: "application/octet-stream"
                    )
                    append(
                        HttpHeaders.ContentDisposition,
                        "filename=\"${file.fileName.dispositionSafe()}\""
                    )
                }
            )
        }
    }

fun messageFormData(
    content: String,
    idempotencyKey: String,
    file: AttachmentFileData,
    replyToMessageId: Long? = null
): List<PartData> =
    formData {
        append("content", content)
        append("idempotencyKey", idempotencyKey)
        replyToMessageId?.let { append("replyToMessageId", it.toString()) }
        append(
            "file",
            file.bytes,
            Headers.build {
                append(
                    HttpHeaders.ContentType,
                    file.mimeType ?: "application/octet-stream"
                )
                append(
                    HttpHeaders.ContentDisposition,
                    "filename=\"${file.fileName.dispositionSafe()}\""
                )
            }
        )
    }

internal fun String.dispositionSafe(): String =
    map { ch -> if (ch == '"' || ch == '\\' || ch == '\r' || ch == '\n') '_' else ch }
        .joinToString("")
