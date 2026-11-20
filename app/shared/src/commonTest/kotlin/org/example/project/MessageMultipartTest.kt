package org.example.project

import io.ktor.http.content.PartData
import org.example.project.data.AttachmentFileData
import org.example.project.data.messageFormData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessageMultipartTest {
    @Test
    fun `message form repeats files part and preserves message fields`() {
        val parts = messageFormData(
            content = "report",
            idempotencyKey = "same-key",
            files = listOf(
                AttachmentFileData("one.txt", "text/plain", byteArrayOf(1)),
                AttachmentFileData("two.txt", "text/plain", byteArrayOf(2)),
                AttachmentFileData("three.txt", "text/plain", byteArrayOf(3))
            ),
            replyToMessageId = 42
        )

        val formItems = parts.filterIsInstance<PartData.FormItem>()
        assertEquals("report", formItems.single { it.name == "content" }.value)
        assertEquals("same-key", formItems.single { it.name == "idempotencyKey" }.value)
        assertEquals("42", formItems.single { it.name == "replyToMessageId" }.value)
        val files = parts.filterIsInstance<PartData.FileItem>()
        assertEquals(listOf("files", "files", "files"), files.map { it.name })
        assertEquals(listOf("one.txt", "two.txt", "three.txt"), files.map { it.originalFileName })
        files.forEach { assertTrue(it.headers != null) }
    }

    @Test
    fun `single file overload keeps legacy part name`() {
        val parts = messageFormData(
            content = "legacy",
            idempotencyKey = "legacy-key",
            file = AttachmentFileData("legacy.txt", "text/plain", byteArrayOf(1))
        )

        val file = assertIs<PartData.FileItem>(parts.single { it is PartData.FileItem })
        assertEquals("file", file.name)
    }
}
