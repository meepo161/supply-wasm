package org.example.project.data

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.utils.io.ByteReadChannel

fun messageFormData(
    content: String,
    idempotencyKey: String,
    files: List<AttachmentFileData>,
    replyToMessageId: Long? = null
): List<PartData> = buildList {
    add(formTextItem("content", content))
    add(formTextItem("idempotencyKey", idempotencyKey))
    replyToMessageId?.let { add(formTextItem("replyToMessageId", it.toString())) }
    files.forEach { file ->
        add(formFileItem("files", file))
    }
}

fun messageFormData(
    content: String,
    idempotencyKey: String,
    file: AttachmentFileData,
    replyToMessageId: Long? = null
): List<PartData> = buildList {
    add(formTextItem("content", content))
    add(formTextItem("idempotencyKey", idempotencyKey))
    replyToMessageId?.let { add(formTextItem("replyToMessageId", it.toString())) }
    add(formFileItem("file", file))
}

private fun formTextItem(name: String, value: String): PartData.FormItem =
    PartData.FormItem(
        value = value,
        dispose = {},
        partHeaders = Headers.build {
            append(HttpHeaders.ContentDisposition, "form-data; name=\"${name.dispositionSafe()}\"")
        }
    )

private fun formFileItem(name: String, file: AttachmentFileData): PartData.FileItem =
    PartData.FileItem(
        provider = { ByteReadChannel(file.bytes) },
        dispose = {},
        partHeaders = Headers.build {
            append(
                HttpHeaders.ContentDisposition,
                "form-data; name=\"${name.dispositionSafe()}\"; filename=\"${file.fileName.dispositionSafe()}\""
            )
            append(
                HttpHeaders.ContentType,
                file.mimeType ?: "application/octet-stream"
            )
            append(HttpHeaders.ContentLength, file.bytes.size.toString())
        }
    )

internal fun String.dispositionSafe(): String =
    map { ch -> if (ch == '"' || ch == '\\' || ch == '\r' || ch == '\n') '_' else ch }
        .joinToString("")
