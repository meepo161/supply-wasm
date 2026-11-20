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


class ConversationRealtimeMergeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun message(id: Long, content: String = "message-$id") = ConversationMessageDto(
        id = id,
        conversationId = 7,
        content = content,
        createdAt = "2026-11-19T10:00:00Z"
    )

    @Test
    fun `legacy envelope still parses as message event`() {
        val event = org.example.project.data.parseConversationRealtimeEvent(
            json,
            """{"category":"CONVERSATION_MESSAGE","message":{"id":5,"conversationId":7,"content":"hello","createdAt":"2026-11-19T10:00:00Z"}}""",
            expectedConversationId = 7
        )
        val message = kotlin.test.assertIs<org.example.project.data.ConversationRealtimeEvent.Message>(event)
        assertEquals(org.example.project.data.ConversationMessageEventType.MESSAGE, message.type)
        assertNull(message.eventId)
        assertEquals(5L, message.message.id)
    }

    @Test
    fun `lifecycle envelope of another conversation is rejected`() {
        val text = """{"category":"CONVERSATION_REACTION","conversationId":8,"messageId":5,"eventId":9,"reactions":[]}"""
        assertNull(org.example.project.data.parseConversationRealtimeEvent(json, text, 7))
        val accepted = org.example.project.data.parseConversationRealtimeEvent(json, text.replace("conversationId\":8", "conversationId\":7"), 7)
        assertEquals(9L, accepted?.eventId)
    }

    @Test
    fun `edited revoked reaction read and pin merge once by event id`() {
        val merger = org.example.project.data.ConversationRealtimeMerger(listOf(message(1, "old")))
        val edited = org.example.project.data.ConversationRealtimeEvent.Message(
            message = message(1, "new").copy(editedAt = "2026-11-19T10:01:00Z"),
            type = org.example.project.data.ConversationMessageEventType.EDITED,
            eventId = 10
        )
        assertTrue(merger.apply(edited))
        assertFalse(merger.apply(edited))
        assertEquals("new", merger.messages.single().content)

        val reaction = org.example.project.data.ConversationRealtimeEvent.Reaction(
            messageId = 1,
            reactions = listOf(org.example.project.dto.ReactionSummaryDto("+1", 2, true)),
            eventId = 11
        )
        assertTrue(merger.apply(reaction))
        assertFalse(merger.apply(reaction))
        assertEquals(2, merger.messages.single().reactions.single().count)

        assertTrue(merger.apply(org.example.project.data.ConversationRealtimeEvent.Read(3, 1, 12)))
        assertFalse(merger.apply(org.example.project.data.ConversationRealtimeEvent.Read(3, 1, 12)))
        assertEquals(1L, merger.readCursors[3])

        assertTrue(merger.apply(org.example.project.data.ConversationRealtimeEvent.Pin(true, 13)))
        assertFalse(merger.apply(org.example.project.data.ConversationRealtimeEvent.Pin(false, 13)))
        assertTrue(merger.isPinned)

        val revoked = org.example.project.data.ConversationRealtimeEvent.Message(
            message = message(1, "").copy(revokedAt = "2026-11-19T10:02:00Z"),
            type = org.example.project.data.ConversationMessageEventType.REVOKED,
            eventId = 14
        )
        assertTrue(merger.apply(revoked))
        assertEquals("", merger.messages.single().content)
        assertEquals("2026-11-19T10:02:00Z", merger.messages.single().revokedAt)
        assertEquals(14L, merger.lastEventId)
    }

    @Test
    fun `new message deduplicates reconnect replay by message and event id`() {
        val merger = org.example.project.data.ConversationRealtimeMerger()
        val event = org.example.project.data.ConversationRealtimeEvent.Message(message(4), eventId = 20)
        assertTrue(merger.apply(event))
        assertFalse(merger.apply(event))
        assertFalse(merger.apply(org.example.project.data.ConversationRealtimeEvent.Message(message(4), eventId = 21)))
        assertEquals(listOf(4L), merger.messages.map { it.id })
        assertEquals(21L, merger.lastEventId)
    }
}


class ConversationReconnectCursorTest {

    @Test
    fun `overflow reconnect advances both cursors from delivered events`() {
        val cursor = org.example.project.data.ConversationReconnectCursor()
        assertEquals(emptyMap(), cursor.parameters())
        assertTrue(cursor.shouldReconnect(org.example.project.data.ConversationDisconnect.OVERFLOW))

        cursor.record(
            org.example.project.data.ConversationRealtimeEvent.Message(
                ConversationMessageDto(8, 7, content = "new", createdAt = "2026-11-19T10:00:00Z"),
                eventId = 41
            )
        )
        assertEquals(mapOf("after" to "8", "afterEvent" to "41"), cursor.parameters())

        cursor.record(org.example.project.data.ConversationRealtimeEvent.Read(2, 8, 42))
        assertEquals(mapOf("after" to "8", "afterEvent" to "42"), cursor.parameters())
        cursor.record(org.example.project.data.ConversationRealtimeEvent.Read(2, 8, 40))
        assertEquals(mapOf("after" to "8", "afterEvent" to "42"), cursor.parameters())
        assertTrue(cursor.shouldReconnect(org.example.project.data.ConversationDisconnect.CLOSED))
        assertFalse(cursor.shouldReconnect(org.example.project.data.ConversationDisconnect.ACCESS_REVOKED))
    }
}


class ConversationOverflowCloseTest {

    @Test
    fun `server event overflow close reason is classified separately`() {
        assertTrue(
            org.example.project.data.isConversationOverflowClose(
                io.ktor.websocket.CloseReason(
                    io.ktor.websocket.CloseReason.Codes.INTERNAL_ERROR.code,
                    "event overflow; reconnect"
                )
            )
        )
        assertFalse(
            org.example.project.data.isConversationOverflowClose(
                io.ktor.websocket.CloseReason(
                    io.ktor.websocket.CloseReason.Codes.INTERNAL_ERROR.code,
                    "other failure"
                )
            )
        )
    }
}
