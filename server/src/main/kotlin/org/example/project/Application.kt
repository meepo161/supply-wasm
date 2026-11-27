package org.example.project

import io.ktor.server.application.*
import io.ktor.server.netty.EngineMain
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.launch
import com.zaxxer.hikari.HikariDataSource
import org.example.project.config.AppConfig
import org.example.project.di.appModule
import org.example.project.plugins.*
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import javax.sql.DataSource

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module(pushTransport: org.example.project.service.PushTransport? = null) {
    val config = AppConfig.load(environment.config)

    install(Koin) {
        slf4jLogger()
        modules(appModule(config, pushTransport))
    }

    monitor.subscribe(ApplicationStopped) {
        (this@module.getKoin().get<DataSource>() as? HikariDataSource)?.close()
    }

    install(SessionContextPlugin) {
        sessionService = this@module.getKoin().get()
        cookieName = config.cookieName
    }

    install(WebSockets)

    configureHTTP(config)
    intercept(ApplicationCallPipeline.Plugins) {
        val requestId = call.request.headers["X-Request-Id"]?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
        call.response.headers.append("X-Request-Id", requestId)
        org.slf4j.MDC.put("requestId", requestId)
        try {
            proceed()
        } finally {
            org.slf4j.MDC.remove("requestId")
        }
    }
    configureStatusPages()
    configureRouting()

    val notificationEventBus = getKoin().get<org.example.project.realtime.NotificationEventBus>()
    val pushService = getKoin().get<org.example.project.service.PushService>()
    val messengerService = getKoin().get<org.example.project.service.ConversationMessengerService>()
    launch {
        notificationEventBus.notifications.collect { event -> pushService.send(event) }
    }
    launch {
        while (true) {
            runCatching { messengerService.deliverDueScheduledMessages() }
            kotlinx.coroutines.delay(15_000)
        }
    }
}
