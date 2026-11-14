package org.example.project

import io.ktor.server.application.*
import io.ktor.server.netty.EngineMain
import io.ktor.server.websocket.WebSockets
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

fun Application.module() {
    val config = AppConfig.load(environment.config)

    install(Koin) {
        slf4jLogger()
        modules(appModule(config))
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
}
