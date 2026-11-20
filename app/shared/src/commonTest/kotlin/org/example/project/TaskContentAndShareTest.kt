package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.dto.TaskArea
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus
import org.example.project.dto.TaskWorkType
import org.example.project.ui.tasks.findTextLinks
import org.example.project.ui.tasks.parseTaskMessageLink
import org.example.project.ui.tasks.taskShareContent

class TaskContentAndShareTest {
    @Test
    fun `task text links are clickable ranges without trailing punctuation`() {
        val links = findTextLinks("Схема: https://example.com/spec, затем http://localhost:8081/task.")

        assertEquals(listOf("https://example.com/spec", "http://localhost:8081/task"), links.map { it.url })
        assertEquals("Схема: ", "Схема: https://example.com/spec, затем http://localhost:8081/task.".substring(0, links.first().range.first))
    }

    @Test
    fun `shared task content produces navigable task card`() {
        val task = TaskDto(
            id = 42,
            number = "T-2026-00042",
            area = TaskArea.PERSONAL,
            status = TaskStatus.NEW,
            type = TaskWorkType.GENERAL,
            title = "Проверить комплектацию",
            initiatorId = 1,
            createdBy = 1
        )

        val content = taskShareContent(task)
        val link = parseTaskMessageLink(content)

        assertEquals(42, link?.taskId)
        assertEquals("Задача T-2026-00042: Проверить комплектацию", link?.title)
        assertTrue(content.contains("erp://tasks/42"))
    }

    @Test
    fun `ordinary and malformed messages do not become task links`() {
        assertNull(parseTaskMessageLink("Обычное сообщение"))
        assertNull(parseTaskMessageLink("erp://tasks/not-a-number"))
        assertNull(parseTaskMessageLink("erp://tasks/-1"))
    }
}
