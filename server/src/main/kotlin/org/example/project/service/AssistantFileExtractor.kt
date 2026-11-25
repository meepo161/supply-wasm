package org.example.project.service

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.example.project.dto.AssistantUploadDto
import org.example.project.exception.ValidationException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.imageio.ImageIO

private const val MAX_ASSISTANT_FILES = 5
private const val MAX_ASSISTANT_FILE_BYTES = 15L * 1024L * 1024L
private const val MAX_ASSISTANT_TOTAL_BYTES = 25L * 1024L * 1024L
private const val MAX_EXTRACTED_TEXT_CHARS = 24_000
private const val MAX_PDF_SCAN_PAGES = 4

data class ExtractedAttachments(
    val textBlocks: List<String>,
    val imagesBase64: List<String>
)

object AssistantFileExtractor {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val textExtensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log", "ini", "conf", "sql", "html"
    )

    fun extract(uploads: List<AssistantUploadDto>): ExtractedAttachments {
        if (uploads.isEmpty()) return ExtractedAttachments(emptyList(), emptyList())
        if (uploads.size > MAX_ASSISTANT_FILES) {
            throw ValidationException("Можно прикрепить не более $MAX_ASSISTANT_FILES файлов за один запрос")
        }

        val textBlocks = mutableListOf<String>()
        val images = mutableListOf<String>()
        var totalBytes = 0L

        uploads.forEach { upload ->
            val fileName = upload.fileName.trim().ifEmpty { "file" }
            val bytes = try {
                Base64.getDecoder().decode(upload.base64Data.trim())
            } catch (_: IllegalArgumentException) {
                throw ValidationException("Некорректные данные файла: $fileName")
            }

            if (bytes.isEmpty()) {
                throw ValidationException("Файл пуст: $fileName")
            }
            if (bytes.size > MAX_ASSISTANT_FILE_BYTES) {
                throw ValidationException("Файл $fileName превышает 15 МиБ")
            }
            totalBytes += bytes.size
            if (totalBytes > MAX_ASSISTANT_TOTAL_BYTES) {
                throw ValidationException("Общий размер прикреплённых файлов превышает 25 МиБ")
            }

            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mime = upload.mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()

            when {
                ext == "pdf" || mime == "application/pdf" -> extractPdf(fileName, bytes, textBlocks, images)
                ext in imageExtensions || mime.startsWith("image/") -> {
                    images.add(Base64.getEncoder().encodeToString(bytes))
                    textBlocks.add("Приложено изображение: $fileName")
                }
                ext in textExtensions || mime.startsWith("text/") || mime == "application/json" -> {
                    val decoded = decodeText(bytes).take(MAX_EXTRACTED_TEXT_CHARS)
                    textBlocks.add("--- Содержимое файла $fileName ---\n$decoded")
                }
                else -> throw ValidationException(
                    "Неподдерживаемый формат файла «$fileName». Поддерживаются: PDF, JPG/PNG/WEBP, MD, TXT, CSV, JSON, XML."
                )
            }
        }

        return ExtractedAttachments(textBlocks = textBlocks, imagesBase64 = images)
    }

    private fun extractPdf(
        fileName: String,
        bytes: ByteArray,
        textBlocks: MutableList<String>,
        images: MutableList<String>
    ) {
        try {
            Loader.loadPDF(bytes).use { document ->
                val stripper = PDFTextStripper()
                val text = stripper.getText(document).trim()
                if (text.isNotEmpty()) {
                    val truncated = if (text.length > MAX_EXTRACTED_TEXT_CHARS) {
                        text.take(MAX_EXTRACTED_TEXT_CHARS) + "\n… [текст обрезан]"
                    } else {
                        text
                    }
                    textBlocks.add("--- Текст PDF-файла $fileName (стр.: ${document.numberOfPages}) ---\n$truncated")
                } else {
                    val renderer = PDFRenderer(document)
                    val pagesToRender = minOf(document.numberOfPages, MAX_PDF_SCAN_PAGES)
                    for (pageIndex in 0 until pagesToRender) {
                        val buffered = renderer.renderImageWithDPI(pageIndex, 200f)
                        val out = ByteArrayOutputStream()
                        ImageIO.write(buffered, "png", out)
                        images.add(Base64.getEncoder().encodeToString(out.toByteArray()))
                    }
                    textBlocks.add("Приложен скан PDF $fileName (распознавание по изображениям первых $pagesToRender стр. из ${document.numberOfPages}).")
                }
            }
        } catch (e: ValidationException) {
            throw e
        } catch (e: Exception) {
            throw ValidationException("Не удалось прочитать PDF «$fileName»: ${e.message ?: "повреждённый файл"}")
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        val utf8Decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("Windows-1251"))
        }
    }
}
