package org.example.project

import kotlinx.serialization.json.Json
import org.example.project.data.TaskChatRealtimeEvent
import org.example.project.data.chatTypingFrame
import org.example.project.data.parseTaskChatRealtimeEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskChatRealtimeEventsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `message envelope parses for the expected task`() {
        val text = """{"category":"TASK_CHAT_MESSAGE","message":{"id":5,"taskId":7,"author":null,"type":"USER","content":"привет","createdAt":"2026-11-17T10:00:00Z"}}"""
        val event = parseTaskChatRealtimeEvent(json, text, expectedTaskId = 7)
        val message = assertIs<TaskChatRealtimeEvent.Message>(event).message
        assertEquals(5L, message.id)
        assertNull(parseTaskChatRealtimeEvent(json, text, expectedTaskId = 8))
    }

    @Test
    fun `reaction envelope carries message id and summaries`() {
        val text = """{"category":"TASK_CHAT_REACTION","messageId":5,"reactions":[{"emoji":"👍","count":2,"reactedByMe":true}]}"""
        val event = assertIs<TaskChatRealtimeEvent.Reaction>(
            parseTaskChatRealtimeEvent(json, text, expectedTaskId = 7)
        )
        assertEquals(5L, event.messageId)
        assertEquals("👍", event.reactions.single().emoji)
        assertTrue(event.reactions.single().reactedByMe)
    }

    @Test
    fun `read pin and typing envelopes parse`() {
        val read = assertIs<TaskChatRealtimeEvent.Read>(
            parseTaskChatRealtimeEvent(
                json,
                """{"category":"TASK_CHAT_READ","userId":3,"lastReadMessageId":42}""",
                expectedTaskId = 7
            )
        )
        assertEquals(42L, read.lastReadMessageId)

        val pin = assertIs<TaskChatRealtimeEvent.Pin>(
            parseTaskChatRealtimeEvent(
                json,
                """{"category":"TASK_CHAT_PIN","isPinned":true}""",
                expectedTaskId = 7
            )
        )
        assertTrue(pin.isPinned)

        val typing = assertIs<TaskChatRealtimeEvent.Typing>(
            parseTaskChatRealtimeEvent(
                json,
                """{"category":"TASK_CHAT_TYPING","userId":3,"fullName":"Иван Петров"}""",
                expectedTaskId = 7
            )
        )
        assertEquals("Иван Петров", typing.fullName)
    }

    @Test
    fun `unknown category and malformed payloads are ignored`() {
        assertNull(parseTaskChatRealtimeEvent(json, """{"category":"OTHER"}""", 7))
        assertNull(parseTaskChatRealtimeEvent(json, "not json", 7))
        assertNull(parseTaskChatRealtimeEvent(json, """{"category":"TASK_CHAT_READ","userId":3}""", 7))
    }

    @Test
    fun `typing command frame matches contract`() {
        assertEquals("""{"action":"TYPING"}""", chatTypingFrame())
    }
}
