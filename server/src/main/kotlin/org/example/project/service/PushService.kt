package org.example.project.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.martijndwars.webpush.Encoding
import nl.martijndwars.webpush.Notification
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.example.project.config.PushSettings
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.realtime.NotificationEventBus
import org.example.project.repository.PushSubscription
import org.example.project.repository.PushSubscriptionStore
import org.example.project.route.DeletePushSubscriptionRequest
import org.example.project.route.PushSubscriptionRequest
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.Security

interface PushTransport {
    fun send(subscription: PushSubscription, payload: String): Int
}

class WebPushTransport(private val settings: PushSettings) : PushTransport {
    private val sender by lazy {
        Security.addProvider(BouncyCastleProvider())
        nl.martijndwars.webpush.PushService(
            settings.vapidPublicKey,
            settings.vapidPrivateKey,
            settings.vapidSubject
        )
    }

    override fun send(subscription: PushSubscription, payload: String): Int {
        val notification = Notification(
            subscription.endpoint,
            subscription.p256dh,
            subscription.auth,
            payload
        )
        return sender.send(notification, Encoding.AES128GCM).statusLine.statusCode
    }
}

data class PushPublicKeyStatus(val enabled: Boolean, val publicKey: String?)

class PushService(
    private val subscriptions: PushSubscriptionStore,
    private val transport: PushTransport,
    private val settings: PushSettings
) {
    private val logger = LoggerFactory.getLogger(PushService::class.java)

    fun publicKeyStatus(): PushPublicKeyStatus = PushPublicKeyStatus(
        enabled = settings.enabled,
        publicKey = settings.vapidPublicKey.takeIf { settings.enabled }
    )

    fun subscribe(request: PushSubscriptionRequest, userId: Long): PushSubscription {
        validateEndpoint(request.endpoint, settings.allowedHosts)
        if (request.p256dh.isBlank() || request.p256dh.length > 512) {
            throw ValidationException("Invalid push subscription key")
        }
        if (request.auth.isBlank() || request.auth.length > 512) {
            throw ValidationException("Invalid push subscription auth")
        }
        val existing = subscriptions.findByEndpoint(request.endpoint)
        if (existing != null && existing.userId != userId) throw NotFoundException("push subscription", request.endpoint)
        return if (existing == null) {
            subscriptions.insert(userId, request.endpoint, request.p256dh, request.auth)
        } else {
            subscriptions.update(userId, existing.id, request.p256dh, request.auth)
        }
    }

    fun unsubscribe(request: DeletePushSubscriptionRequest, userId: Long) {
        val existing = when {
            request.id != null -> subscriptions.findById(request.id)
            !request.endpoint.isNullOrBlank() -> subscriptions.findByEndpoint(request.endpoint)
            else -> throw ValidationException("Subscription id or endpoint is required")
        }?.takeIf { it.userId == userId } ?: throw NotFoundException("push subscription", request.id ?: request.endpoint!!)
        if (!subscriptions.deleteOwned(existing.id, userId)) {
            throw NotFoundException("push subscription", existing.id)
        }
    }

    suspend fun send(event: NotificationEventBus.Event) = withContext(Dispatchers.IO) {
        if (!settings.enabled) return@withContext
        val payload = buildJsonObject {
            put("category", "NOTIFICATION")
            put("eventId", event.notification.id)
        }.toString()
        subscriptions.listForUser(event.recipientId).forEach { subscription ->
            try {
                when (val status = transport.send(subscription, payload)) {
                    404, 410 -> subscriptions.deleteById(subscription.id)
                    !in 200..299 -> logger.warn("Web Push provider returned HTTP {}", status)
                }
            } catch (e: Exception) {
                logger.warn("Web Push delivery failed with {}", e.javaClass.simpleName)
            }
        }
    }

    private fun validateEndpoint(value: String, allowedHosts: List<String>) {
        if (value.length > 4096) throw ValidationException("Invalid push subscription endpoint")
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw ValidationException("Invalid push subscription endpoint")
        }
        val host = uri.host?.lowercase()
        val valid = uri.scheme == "https" &&
            host != null &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            !isPrivateHost(host) &&
            allowedHosts.any { allowed ->
                val normalized = allowed.trim().lowercase().trimEnd('.')
                normalized.isNotEmpty() && (host == normalized || host.endsWith(".$normalized"))
            }
        if (!valid) throw ValidationException("Push endpoint is not an allowed browser push host")
    }

    private fun isPrivateHost(host: String): Boolean =
        host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host.contains(':') ||
            IPV4_HOST.matches(host)

    private companion object {
        val IPV4_HOST = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")
    }
}
