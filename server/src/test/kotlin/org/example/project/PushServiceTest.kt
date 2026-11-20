package org.example.project

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.example.project.config.PushSettings
import org.example.project.dto.NotificationDto
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.realtime.NotificationEventBus
import org.example.project.repository.PushSubscription
import org.example.project.repository.PushSubscriptionStore
import org.example.project.route.PushSubscriptionRequest
import org.example.project.service.PushService
import org.example.project.service.PushTransport
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushServiceTest {

    @Test
    fun `payload contains only category and event id`() = runBlocking {
        val store = FakeStore()
        store.rows += subscription(id = 1, userId = 7)
        val transport = FakeTransport()
        val service = PushService(store, transport, enabledSettings)

        service.send(notificationEvent(id = 42, recipientId = 7))

        val sent = transport.sent.single()
        val payload = Json.parseToJsonElement(sent.second).jsonObject
        assertEquals(setOf("category", "eventId"), payload.keys)
        assertEquals("NOTIFICATION", payload.getValue("category").toString().trim('"'))
        assertEquals("42", payload.getValue("eventId").toString())
        listOf("Secret message", "Secret task", "Secret project", "Secret user").forEach { secret ->
            assertFalse(sent.second.contains(secret))
        }
    }

    @Test
    fun `disabled service is explicit and does not send`() = runBlocking {
        val transport = FakeTransport()
        val service = PushService(FakeStore(), transport, PushSettings(null, null, null, defaultHosts))

        assertFalse(service.publicKeyStatus().enabled)
        assertEquals(null, service.publicKeyStatus().publicKey)
        service.send(notificationEvent(id = 1, recipientId = 7))
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `gone subscriptions are deleted after 404 and 410`() = runBlocking {
        listOf(404, 410).forEachIndexed { index, status ->
            val store = FakeStore()
            store.rows += subscription(id = index + 1L, userId = 7)
            val transport = FakeTransport(status)
            val service = PushService(store, transport, enabledSettings)

            service.send(notificationEvent(id = 42, recipientId = 7))

            assertTrue(store.rows.isEmpty())
        }
    }

    @Test
    fun `temporary push failure does not throw or delete subscription`() = runBlocking {
        val store = FakeStore()
        store.rows += subscription(id = 1, userId = 7)
        val transport = FakeTransport(status = 500)
        val service = PushService(store, transport, enabledSettings)

        service.send(notificationEvent(id = 42, recipientId = 7))

        assertEquals(1, store.rows.size)
    }

    @Test
    fun `transport exception does not escape notification collector`() = runBlocking {
        val store = FakeStore()
        store.rows += subscription(id = 1, userId = 7)
        val transport = FakeTransport(error = IllegalStateException("provider unavailable"))
        val service = PushService(store, transport, enabledSettings)

        service.send(notificationEvent(id = 42, recipientId = 7))

        assertEquals(1, store.rows.size)
    }

    @Test
    fun `only HTTPS browser push hosts are accepted`() {
        val service = PushService(FakeStore(), FakeTransport(), enabledSettings)
        val valid = listOf(
            "https://fcm.googleapis.com/fcm/send/token",
            "https://updates.push.services.mozilla.com/wpush/v2/token",
            "https://web.push.apple.com/Q123"
        )
        valid.forEach { endpoint ->
            service.subscribe(PushSubscriptionRequest(endpoint, "key-$endpoint", "auth"), 7)
        }

        val invalid = listOf(
            "http://fcm.googleapis.com/fcm/send/token",
            "https://localhost/push",
            "https://127.0.0.1/push",
            "https://10.0.0.4/push",
            "https://192.168.1.10/push",
            "https://[::1]/push",
            "https://example.com/push",
            "https://fcm.googleapis.com.evil.example/push",
            "https://user:password@fcm.googleapis.com/push"
        )
        invalid.forEach { endpoint ->
            assertFailsWith<ValidationException>(endpoint) {
                service.subscribe(PushSubscriptionRequest(endpoint, "key", "auth"), 7)
            }
        }
    }

    @Test
    fun `configured allowlist cannot enable private endpoints`() {
        val settings = enabledSettings.copy(allowedHosts = listOf("localhost", "127.0.0.1", "push.internal"))
        val service = PushService(FakeStore(), FakeTransport(), settings)

        assertFailsWith<ValidationException> {
            service.subscribe(PushSubscriptionRequest("https://localhost/push", "key", "auth"), 7)
        }
        assertFailsWith<ValidationException> {
            service.subscribe(PushSubscriptionRequest("https://127.0.0.1/push", "key", "auth"), 7)
        }
    }

    @Test
    fun `endpoint cannot be reassigned to another owner`() {
        val store = FakeStore()
        val existing = subscription(id = 1, userId = 7)
        store.rows += existing
        val service = PushService(store, FakeTransport(), enabledSettings)

        assertFailsWith<NotFoundException> {
            service.subscribe(PushSubscriptionRequest(existing.endpoint, "new-key", "new-auth"), 8)
        }
        assertEquals(7, store.rows.single().userId)
        assertEquals("key", store.rows.single().p256dh)
    }

    private class FakeTransport(
        private val status: Int = 201,
        private val error: Throwable? = null
    ) : PushTransport {
        val sent = mutableListOf<Pair<PushSubscription, String>>()

        override fun send(subscription: PushSubscription, payload: String): Int {
            sent += subscription to payload
            error?.let { throw it }
            return status
        }
    }

    private class FakeStore : PushSubscriptionStore {
        val rows = mutableListOf<PushSubscription>()

        override fun listForUser(userId: Long): List<PushSubscription> = rows.filter { it.userId == userId }

        override fun findByEndpoint(endpoint: String): PushSubscription? = rows.find { it.endpoint == endpoint }

        override fun findById(id: Long): PushSubscription? = rows.find { it.id == id }

        override fun insert(userId: Long, endpoint: String, p256dh: String, auth: String): PushSubscription {
            val row = PushSubscription((rows.maxOfOrNull { it.id } ?: 0) + 1, userId, endpoint, p256dh, auth)
            rows += row
            return row
        }

        override fun update(userId: Long, id: Long, p256dh: String, auth: String): PushSubscription {
            val index = rows.indexOfFirst { it.id == id && it.userId == userId }
            val updated = rows[index].copy(p256dh = p256dh, auth = auth)
            rows[index] = updated
            return updated
        }

        override fun deleteOwned(id: Long, userId: Long): Boolean = rows.removeIf { it.id == id && it.userId == userId }

        override fun deleteById(id: Long): Boolean = rows.removeIf { it.id == id }
    }

    private companion object {
        val defaultHosts = listOf("fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com")
        val enabledSettings = PushSettings("public", "private", "mailto:test@example.com", defaultHosts)

        fun subscription(id: Long, userId: Long) = PushSubscription(
            id = id,
            userId = userId,
            endpoint = "https://fcm.googleapis.com/fcm/send/subscription-$id",
            p256dh = "key",
            auth = "auth"
        )

        fun notificationEvent(id: Long, recipientId: Long) = NotificationEventBus.Event(
            recipientId = recipientId,
            notification = NotificationDto(
                id = id,
                type = "NEW_TASK_MESSAGE",
                entityType = "TASK_CHAT_MESSAGE",
                entityId = 100,
                title = "Secret message Secret task Secret project Secret user",
                isRead = false,
                createdAt = "2026-11-19T00:00:00Z",
                taskId = 100
            )
        )
    }
}
