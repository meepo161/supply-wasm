package org.example.project.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.PushService

@Serializable
data class PushSubscriptionRequest(
    val endpoint: String,
    val p256dh: String,
    val auth: String
)

@Serializable
data class DeletePushSubscriptionRequest(
    val id: Long? = null,
    val endpoint: String? = null
)

@Serializable
data class PushSubscriptionResponse(
    val id: Long,
    val endpoint: String
)

@Serializable
data class PushPublicKeyResponse(
    val enabled: Boolean,
    val publicKey: String?
)

@Serializable
data class DeletePushSubscriptionResponse(val deleted: Boolean)

fun Route.pushRoutes(pushService: PushService) {
    route("/api/push") {
        install(RequireAuthPlugin) {}

        get("/public-key") {
            val status = pushService.publicKeyStatus()
            call.respond(HttpStatusCode.OK, ApiResponse.success(PushPublicKeyResponse(status.enabled, status.publicKey)))
        }

        post("/subscriptions") {
            if (!pushService.publicKeyStatus().enabled) {
                call.respondPushDisabled()
                return@post
            }
            val subscription = pushService.subscribe(call.receive(), call.currentUser().userId)
            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(PushSubscriptionResponse(subscription.id, subscription.endpoint))
            )
        }

        delete("/subscriptions") {
            pushService.unsubscribe(call.receive(), call.currentUser().userId)
            call.respond(HttpStatusCode.OK, ApiResponse.success(DeletePushSubscriptionResponse(true)))
        }
    }
}

private suspend fun ApplicationCall.respondPushDisabled() {
    respond(
        HttpStatusCode.ServiceUnavailable,
        ApiResponse.error<Nothing>(ApiError("PUSH_DISABLED", "Web Push is disabled: VAPID is not configured"))
    )
}
