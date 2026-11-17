package org.example.project

import kotlinx.serialization.json.Json
import org.example.project.data.CONVERSATION_REALTIME_CATEGORY
import org.example.project.data.appendDistinctMessages
import org.example.project.data.isConversationAccessTerminated
import org.example.project.data.parseConversationRealtimeMessage
import org.example.project.dto.ConversationMessageDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationRealtimeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun message(id: Long, conversationId: Long = 7) = ConversationMessageDto(
        id = id,
        conversationId = conversationId,
        content = "msg-$id",
        createdAt = "2026-11-17T10:00:00Z"
    )

    private fun envelope(id: Long, conversationId: Long = 7, category: String = CONVERSATION_REALTIME_CATEGORY) =
        """{"category":"$category","message":{"id":$id,"conversationId":$conversationId,""" +
            """"content":"hello","createdAt":"2026-11-17T10:00:00Z"}}"""

    @Test
    fun `parses conversation message envelope`() {
        val parsed = parseConversationRealtimeMessage(json, envelope(id = 5), expectedConversationId = 7)
        assertEquals(5L, parsed?.id)
        assertEquals(7L, parsed?.conversationId)
        assertEquals("hello", parsed?.content)
    }

    @Test
    fun `rejects envelope of another category`() {
        assertNull(
            parseConversationRealtimeMessage(
                json,
                envelope(id = 5, category = "TASK_CHAT_MESSAGE"),
                expectedConversationId = 7
            )
        )
    }

    @Test
    fun `rejects message of another conversation`() {
        assertNull(parseConversationRealtimeMessage(json, envelope(id = 5, conversationId = 8), expectedConversationId = 7))
    }

    @Test
    fun `rejects malformed payload`() {
        assertNull(parseConversationRealtimeMessage(json, "not json", expectedConversationId = 7))
        assertNull(parseConversationRealtimeMessage(json, """{"category":"$CONVERSATION_REALTIME_CATEGORY"}""", 7))
    }

    @Test
    fun `append distinct deduplicates and sorts by id`() {
        val base = listOf(message(1), message(3))
        val result = base.appendDistinctMessages(listOf(message(2), message(3, conversationId = 7)))
        assertEquals(listOf(1L, 2L, 3L), result.map { it.id })
    }

    @Test
    fun `access termination only on 401 403 404`() {
        assertTrue(isConversationAccessTerminated(401))
        assertTrue(isConversationAccessTerminated(403))
        assertTrue(isConversationAccessTerminated(404))
        assertFalse(isConversationAccessTerminated(200))
        assertFalse(isConversationAccessTerminated(500))
        assertFalse(isConversationAccessTerminated(null))
    }
}
