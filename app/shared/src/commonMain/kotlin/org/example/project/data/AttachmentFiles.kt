package org.example.project.data

import io.ktor.http.decodeURLPart

/**
 * Открывает системный диалог выбора файла. Колбэк вызывается только
 * для реально выбранного пользователем файла; при отмене диалога
 * колбэк не вызывается.
 */
const val MAX_ATTACHMENT_FILES = 5
const val MAX_ATTACHMENT_FILE_BYTES = 25L * 1024L * 1024L
const val MAX_ATTACHMENT_TOTAL_BYTES = 50L * 1024L * 1024L

fun validateAttachmentSelection(fileSizes: List<Long>): String? = when {
    fileSizes.size > MAX_ATTACHMENT_FILES -> "Можно выбрать не более 5 файлов"
    fileSizes.any { it > MAX_ATTACHMENT_FILE_BYTES } -> "Файл превышает 25 МиБ"
    fileSizes.sum() > MAX_ATTACHMENT_TOTAL_BYTES -> "Общий размер файлов превышает 50 МиБ"
    else -> null
}

expect fun pickAttachmentFile(
    onPicked: (AttachmentFileData) -> Unit,
    onError: (String) -> Unit = {},
    existingFileCount: Int = 0,
    existingTotalBytes: Long = 0L
)

/**
 * Инициирует скачивание файла в браузере (Blob + object URL).
 */
expect fun saveAttachmentFile(fileName: String, mimeType: String?, bytes: ByteArray)

/**
 * Открывает предпросмотр файла в новой вкладке браузера (PDF, изображения, текст и др.).
 */
expect fun previewAttachmentFile(fileName: String, mimeType: String?, bytes: ByteArray)

/**
 * Безопасное имя для сохранения: убирает пути, управляющие символы
 * и символы, недопустимые в именах файлов.
 */
fun safeAttachmentFileName(fileName: String, id: Long): String {
    val cleaned = fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .map { ch -> if (ch in "\\/:*?\"<>|" || ch < ' ') '_' else ch }
        .joinToString("")
        .trim()
        .trimEnd('.')
    return cleaned.ifBlank { "attachment-$id" }
}

internal fun extractContentDispositionFileName(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val parts = header.split(';').map { it.trim() }
    val starParam = parts.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }
    if (starParam != null) {
        val rawValue = starParam.substringAfter('=').trim().removeSurrounding("\"")
        val firstQuote = rawValue.indexOf('\'')
        val secondQuote = if (firstQuote >= 0) rawValue.indexOf('\'', firstQuote + 1) else -1
        val encoded = if (secondQuote >= 0) rawValue.substring(secondQuote + 1) else rawValue
        val decoded = runCatching { encoded.decodeURLPart() }.getOrNull()
        if (!decoded.isNullOrBlank()) return decoded
    }
    val plainParam = parts.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
    if (plainParam != null) {
        val value = plainParam.substringAfter('=').trim().removeSurrounding("\"")
        if (value.isNotBlank()) return value
    }
    return null
}
