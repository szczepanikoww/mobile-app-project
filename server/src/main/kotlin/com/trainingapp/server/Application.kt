package com.trainingapp.server

import com.trainingapp.server.auth.AuditLog
import com.trainingapp.server.auth.CallerResolver
import com.trainingapp.server.auth.TokenService
import com.trainingapp.server.auth.installAuth
import com.trainingapp.server.platform.ApiJson
import com.trainingapp.server.platform.AppConfig
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.installErrors
import com.trainingapp.server.routes.commonRoutes
import com.trainingapp.server.routes.trainerRoutes
import com.trainingapp.server.scheduling.TrainerCalendar
import com.trainingapp.server.scheduling.TrainerSessions
import com.trainingapp.server.worker.Worker
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import org.slf4j.event.Level

fun main() {
    val config = AppConfig.fromEnv()
    val services = Services.create(config, Db.connect(config), ServerClock.System)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(services) }.start(wait = true)
}

/** Manual wiring of the modules (no DI framework needed on the server). Tests build it with a test clock and database. */
class Services(
    val config: AppConfig,
    val db: Db,
    val clock: ServerClock,
) {
    val tokens = TokenService(config.auth)
    val callers = CallerResolver(db)
    val audit = AuditLog(db.dataSource)
    val sessions = TrainerSessions(db, clock)
    val calendar = TrainerCalendar(db, clock)
    val worker = Worker(db, clock)

    companion object {
        fun create(config: AppConfig, db: Db, clock: ServerClock) = Services(config, db, clock)
    }
}

fun Application.module(services: Services) {
    install(ContentNegotiation) { json(ApiJson) }
    install(CallLogging) {
        level = Level.INFO
        // Paths and status only: never bodies, which may carry private data.
        format { call -> "${call.request.local.method.value} ${call.request.local.uri.substringBefore('?')} -> ${call.response.status()}" }
    }
    installErrors()
    installAuth(services.tokens)
    routing {
        commonRoutes(services)
        trainerRoutes(services)
    }
    if (services.config.runWorker) {
        val job = services.worker.start()
        monitor.subscribe(ApplicationStopped) { job.cancel() }
    }
}
