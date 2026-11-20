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
    configureStatusPages()
    configureRouting()

    val notificationEventBus = getKoin().get<org.example.project.realtime.NotificationEventBus>()
    val pushService = getKoin().get<org.example.project.service.PushService>()
    launch {
        notificationEventBus.notifications.collect { event -> pushService.send(event) }
    }
}
