package com.trainingapp.server

import com.trainingapp.server.auth.AuditLog
import com.trainingapp.server.auth.CallerResolver
import com.trainingapp.server.auth.TokenService
import com.trainingapp.server.auth.trainerJwt
import com.trainingapp.server.platform.ApiJson
import com.trainingapp.server.platform.AppConfig
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.respondTrainerError
import com.trainingapp.server.routes.commonRoutes
import com.trainingapp.server.routes.mediaContentRoutes
import com.trainingapp.server.routes.trainerRoutes
import com.trainingapp.server.scheduling.TrainerCalendar
import com.trainingapp.server.scheduling.TrainerSessions
import com.trainingapp.server.worker.Worker
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.auth.Authentication
import io.ktor.server.routing.Route
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing

/** Manual wiring of the modules (no DI framework needed on the server). Tests build it with a test clock and database. */
class TrainerServices(
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
        fun create(config: AppConfig, db: Db, clock: ServerClock) = TrainerServices(config, db, clock)
    }
}

fun Route.trainerApiRoutes(services: TrainerServices) {
    commonRoutes(services)
    trainerRoutes(services)
    mediaContentRoutes(services.db, services.clock, services.config)
}

fun Application.startTrainerWorker(services: TrainerServices) {
    if (services.config.runWorker) {
        val job = services.worker.start()
        monitor.subscribe(ApplicationStopped) { job.cancel() }
    }
}

/** Standalone Trainer API application (used by tests); the combined server mounts [trainerApiRoutes] itself. */
fun Application.trainerApiModule(services: TrainerServices) {
    install(ContentNegotiation) { json(ApiJson) }
    install(StatusPages) { exception<Throwable> { call, e -> call.respondTrainerError(e) } }
    install(Authentication) { trainerJwt(services.tokens) }
    routing { trainerApiRoutes(services) }
    startTrainerWorker(services)
}
