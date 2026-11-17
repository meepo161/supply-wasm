package org.example.project

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.example.project.data.appendDistinctNotifications
import org.example.project.data.isNotificationStreamTerminated
import org.example.project.data.parseNotificationRealtimeMessage
import org.example.project.dto.NotificationDto

class NotificationRealtimeWebTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun notification(id: Long) = NotificationDto(
        id = id,
        type = "NEW_TASK_MESSAGE",
        entityType = "TASK_CHAT_MESSAGE",
        entityId = 1000L + id,
        title = "Новое сообщение в задаче",
        isRead = false,
        createdAt = "2026-11-17T10:00:00Z"
    )

    private fun envelope(id: Long, category: String = "NOTIFICATION"): String =
        """{"category":"$category","id":$id,"notification":{"id":$id,"type":"NEW_TASK_MESSAGE",""" +
            """"entityType":"TASK_CHAT_MESSAGE","entityId":${1000L + id},""" +
            """"title":"Новое сообщение в задаче","isRead":false,"createdAt":"2026-11-17T10:00:00Z"}}"""

    @Test
    fun `expected category envelope is parsed`() {
        val parsed = parseNotificationRealtimeMessage(json, envelope(42))
        assertEquals(notification(42), parsed)
    }

    @Test
    fun `unknown category is ignored`() {
        assertNull(parseNotificationRealtimeMessage(json, envelope(42, category = "TASK_CHAT_MESSAGE")))
    }

    @Test
    fun `malformed frame is ignored`() {
        assertNull(parseNotificationRealtimeMessage(json, "not a json"))
        assertNull(parseNotificationRealtimeMessage(json, """{"category":"NOTIFICATION"}"""))
        assertNull(parseNotificationRealtimeMessage(json, """["array"]"""))
    }

    @Test
    fun `appendDistinctNotifications skips duplicates and keeps order`() {
        val base = listOf(notification(1), notification(3))
        val result = base.appendDistinctNotifications(listOf(notification(2), notification(3), notification(4)))
        assertEquals(listOf(1L, 2L, 3L, 4L), result.map { it.id })
    }

    @Test
    fun `appendDistinctNotifications returns same list for empty incoming`() {
        val base = listOf(notification(1))
        assertSame(base, base.appendDistinctNotifications(emptyList()))
    }

    @Test
    fun `terminal stream statuses stop reconnect`() {
        assertTrue(isNotificationStreamTerminated(401))
        assertTrue(isNotificationStreamTerminated(403))
        assertTrue(isNotificationStreamTerminated(404))
    }

    @Test
    fun `temporary stream failures allow reconnect`() {
        assertFalse(isNotificationStreamTerminated(null))
        assertFalse(isNotificationStreamTerminated(429))
        assertFalse(isNotificationStreamTerminated(500))
    }
}
