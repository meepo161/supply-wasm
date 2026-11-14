package org.example.project

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.example.project.data.appendDistinct
import org.example.project.data.isChatAccessTerminated
import org.example.project.data.parseChatRealtimeMessage
import org.example.project.dto.ChatAuthorDto
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ChatMessageType

class ChatRealtimeWebTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun message(id: Long, taskId: Long = TASK_ID) = ChatMessageDto(
        id = id,
        taskId = taskId,
        author = ChatAuthorDto(1, "Иван Петров"),
        type = ChatMessageType.USER,
        content = "msg-$id",
        createdAt = "2026-11-14T10:00:00Z"
    )

    private fun envelope(id: Long, taskId: Long = TASK_ID, category: String = "TASK_CHAT_MESSAGE"): String =
        """{"category":"$category","id":$id,"message":{"id":$id,"taskId":$taskId,""" +
            """"author":{"userId":1,"fullName":"Иван Петров"},"type":"USER","content":"msg-$id","createdAt":"2026-11-14T10:00:00Z"}}"""

    @Test
    fun `expected category envelope is parsed`() {
        val parsed = parseChatRealtimeMessage(json, envelope(42), TASK_ID)
        assertEquals(message(42), parsed)
    }

    @Test
    fun `unknown category is ignored`() {
        assertNull(parseChatRealtimeMessage(json, envelope(42, category = "NOTIFICATION"), TASK_ID))
    }

    @Test
    fun `malformed frame is ignored`() {
        assertNull(parseChatRealtimeMessage(json, "not a json", TASK_ID))
        assertNull(parseChatRealtimeMessage(json, """{"category":"TASK_CHAT_MESSAGE"}""", TASK_ID))
        assertNull(parseChatRealtimeMessage(json, """["array"]""", TASK_ID))
    }

    @Test
    fun `message of another task is ignored`() {
        assertNull(parseChatRealtimeMessage(json, envelope(42, taskId = 999), TASK_ID))
    }

    @Test
    fun `appendDistinct skips duplicates and keeps order`() {
        val base = listOf(message(1), message(3))
        val result = base.appendDistinct(listOf(message(2), message(3), message(4)))
        assertEquals(listOf(1L, 2L, 3L, 4L), result.map { it.id })
    }

    @Test
    fun `appendDistinct returns same list for empty incoming`() {
        val base = listOf(message(1))
        assertSame(base, base.appendDistinct(emptyList()))
    }

    @Test
    fun `terminal chat statuses stop reconnect`() {
        assertTrue(isChatAccessTerminated(401))
        assertTrue(isChatAccessTerminated(403))
        assertTrue(isChatAccessTerminated(404))
    }

    @Test
    fun `temporary chat failures allow reconnect`() {
        assertFalse(isChatAccessTerminated(null))
        assertFalse(isChatAccessTerminated(429))
        assertFalse(isChatAccessTerminated(500))
    }

    private companion object {
        const val TASK_ID = 10L
    }
}
