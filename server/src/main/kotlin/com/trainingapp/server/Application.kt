package com.trainingapp.server

import com.trainingapp.server.billing.BillingService
import com.trainingapp.server.billing.FakePaymentProvider
import com.trainingapp.server.billing.PaymentProvider
import com.trainingapp.server.catalogue.CatalogueService
import com.trainingapp.server.common.ApiJson
import com.trainingapp.server.config.Profile
import com.trainingapp.server.config.ServerConfig
import com.trainingapp.server.db.Db
import com.trainingapp.server.dev.DemoSeed
import com.trainingapp.server.dev.devRoutes
import com.trainingapp.server.http.PrivacyCallLogging
import com.trainingapp.server.http.PublicRateLimit
import com.trainingapp.server.http.TokenVerifier
import com.trainingapp.server.http.accountRoutes
import com.trainingapp.server.http.clientRoutes
import com.trainingapp.server.http.conversationRoutes
import com.trainingapp.server.http.installAuthentication
import com.trainingapp.server.http.installErrorHandling
import com.trainingapp.server.http.installRouteTemplateCapture
import com.trainingapp.server.http.mediaRoutes
import com.trainingapp.server.http.publicRoutes
import com.trainingapp.server.http.webhookRoutes
import com.trainingapp.server.auth.trainerJwt
import com.trainingapp.server.platform.AppConfig
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.respondTrainerError
import com.trainingapp.server.training.ExerciseLibraryImport
import com.trainingapp.server.platform.Db as TrainerDb
import com.trainingapp.server.identity.IdentityService
import com.trainingapp.server.media.LocalDiskStorage
import com.trainingapp.server.media.MediaService
import com.trainingapp.server.media.ObjectStorage
import com.trainingapp.server.messaging.MessagingService
import com.trainingapp.server.platform.PushSender
import com.trainingapp.server.platform.RealtimeHub
import com.trainingapp.server.platform.Worker
import com.trainingapp.server.scheduling.SchedulingService
import com.trainingapp.server.training.TrainingService
import com.trainingapp.server.training.WorkoutSyncService
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.TimeZone
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The modules, wired once. Tests build the same graph on a test database. */
class Services(
    val db: Db,
    val config: ServerConfig,
    val paymentProvider: PaymentProvider = FakePaymentProvider(config.payments.fakeWebhookSecret),
    storage: ObjectStorage = LocalDiskStorage(config.media.storageDir),
    push: PushSender = PushSender.Logging,
) {
    val identity = IdentityService(db)
    val catalogue = CatalogueService(db)
    val billing = BillingService(db, identity, catalogue, paymentProvider, config.payments.fakeConfirmDelaySeconds)
    val training = TrainingService(db, identity)
    val messaging = MessagingService(db, identity, training)
    val scheduling = SchedulingService(db, identity, catalogue, billing, messaging)
    val workoutSync = WorkoutSyncService(db, training)
    val media = MediaService(db, identity, storage, config.media)
    val realtime = RealtimeHub()
    val worker = Worker(db, scheduling, billing, media, push, realtime)
    val tokenVerifier: TokenVerifier = TokenVerifier.from(config.auth)
}

/**
 * The Ktor module: plugins, routes and (optionally) the inline worker. [trainerApi] adds the trainer API (`/api/v1`,
 * docs/features/trainer-backend.md) to the same server: its JWT provider, error body, routes and expiry sweeper.
 */
fun Application.trainerModule(s: Services, startWorker: Boolean = s.config.workerInline, trainerApi: TrainerServices? = null) {
    install(ContentNegotiation) { json(ApiJson) }
    install(PrivacyCallLogging)
    installRouteTemplateCapture()
    installErrorHandling(s.db, trainerApi?.let { { e: Throwable -> respondTrainerError(e) } })
    installAuthentication(s.tokenVerifier) { trainerApi?.let { trainerJwt(it.tokens) } }
    install(WebSockets) { pingPeriod = 30.seconds }
    install(RateLimit) { register(PublicRateLimit) { rateLimiter(limit = 120, refillPeriod = 1.minutes); requestKey { it.request.local.remoteAddress } } }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        publicRoutes(s)
        accountRoutes(s)
        clientRoutes(s)
        conversationRoutes(s)
        mediaRoutes(s)
        webhookRoutes(s)
        if (s.config.isLocal) devRoutes(s)
        trainerApi?.let { trainerApiRoutes(it) }
    }
    if (startWorker) {
        s.worker.start(this)
        trainerApi?.let { startTrainerWorker(it) }
    }
}

fun main() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    val log = LoggerFactory.getLogger("trainer")
    val config = ServerConfig.fromEnv()
    require(config.profile == Profile.LOCAL || config.auth.mode == com.trainingapp.server.config.AuthMode.FIREBASE) {
        "only Firebase tokens are accepted outside the local profile"
    }
    if (config.db.migrateOnStart) {
        runBlocking { com.trainingapp.server.db.waitForDatabase { Db.migrate(config.db.url, config.db.ownerUser, config.db.ownerPassword, config.db.appUser) } }
    }
    val db = Db(Db.pool(config.db), config.db.logSql)
    val services = Services(db, config)
    if (config.seedDemoData) runBlocking { DemoSeed(services).seedIfEmpty() }
    // The trainer API shares the connection pool; its own settings (token signing, data key, media folder) come from
    // AppConfig. Its demo data is the same DemoSeed: `demo-trainer` is the trainer Michał Kowalczyk.
    val trainerApi = TrainerServices.create(AppConfig.fromEnv(), TrainerDb(db.dataSource), ServerClock.System)
    // The global exercise library (free-exercise-db); upserts by source id, so the seeded exercises are kept.
    runBlocking { ExerciseLibraryImport.importIfIncomplete(trainerApi.db, trainerApi.clock) }
    log.info("starting on port {} (profile {}, auth {})", config.port, config.profile, config.auth.mode)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { trainerModule(services, trainerApi = trainerApi) }.start(wait = true)
}
