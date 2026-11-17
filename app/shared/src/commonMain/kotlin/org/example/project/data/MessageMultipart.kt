package org.example.project.data

import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData

/**
 * Multipart-тело сообщения с файлом: content, idempotencyKey и один файл.
 * Без файла вызывающий код отправляет обычный JSON.
 */
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

/** Имя файла, безопасное для Content-Disposition: без кавычек, слэшей и переводов строк. */
internal fun String.dispositionSafe(): String =
    map { ch -> if (ch == '"' || ch == '\\' || ch == '\r' || ch == '\n') '_' else ch }
        .joinToString("")
