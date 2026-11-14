package org.example.project.data

/**
 * Открывает системный диалог выбора файла. Колбэк вызывается только
 * для реально выбранного пользователем файла; при отмене диалога
 * колбэк не вызывается.
 */
expect fun pickAttachmentFile(onPicked: (AttachmentFileData) -> Unit)

/**
 * Инициирует скачивание файла в браузере (Blob + object URL).
 */
expect fun saveAttachmentFile(fileName: String, mimeType: String?, bytes: ByteArray)

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
