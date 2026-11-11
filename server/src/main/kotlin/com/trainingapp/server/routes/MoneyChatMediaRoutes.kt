package com.trainingapp.server.routes

import com.trainingapp.contract.api.FinancePeriod
import com.trainingapp.contract.api.MarkReadRequest
import com.trainingapp.contract.api.RecordCashPaymentRequest
import com.trainingapp.contract.api.SendMessageRequest
import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.server.auth.AuditLog
import com.trainingapp.server.auth.CallerResolver
import com.trainingapp.server.auth.trainer
import com.trainingapp.server.billing.TrainerFinance
import com.trainingapp.server.home.TrainerHome
import com.trainingapp.server.media.MediaService
import com.trainingapp.server.messaging.TrainerChat
import com.trainingapp.server.platform.AppConfig
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.invalid
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * Home, finance, chat and media endpoints of the trainer. Mount inside `route("/api/v1/trainer") { authenticate(AUTH) { } }`.
 */
fun Route.moneyChatMediaRoutes(db: Db, clock: ServerClock, callers: CallerResolver, audit: AuditLog, config: AppConfig) {
    val home = TrainerHome(db, clock)
    val finance = TrainerFinance(db, clock)
    val chat = TrainerChat(db, clock)
    val media = MediaService(db, clock, config, audit)

    get("/home") { call.respond(home.home(call.trainer(callers).userId)) }

    get("/finance") {
        val period = call.request.queryParameters["period"]?.let {
            runCatching { FinancePeriod.valueOf(it.uppercase()) }.getOrElse { invalid("Invalid period") }
        } ?: FinancePeriod.MONTH
        call.respond(finance.summary(call.trainer(callers).userId, period))
    }
    get("/clients/{clientId}/balance") {
        call.respond(finance.balance(call.trainer(callers).userId, call.parameters["clientId"] ?: invalid("Missing clientId")))
    }
    post("/payments/cash") {
        val trainerId = call.trainer(callers).userId
        call.respond(HttpStatusCode.Created, finance.recordCash(trainerId, call.receive<RecordCashPaymentRequest>()))
    }

    get("/conversations") { call.respond(chat.conversations(call.trainer(callers).userId)) }
    get("/conversations/{clientId}/messages") {
        val trainerId = call.trainer(callers).userId
        val afterSeq = call.request.queryParameters["afterSeq"]?.let { it.toLongOrNull() ?: invalid("Invalid afterSeq") } ?: 0L
        val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: invalid("Invalid limit") } ?: 100
        call.respond(chat.thread(trainerId, call.parameters["clientId"] ?: invalid("Missing clientId"), afterSeq, limit))
    }
    post("/conversations/{clientId}/messages") {
        val trainerId = call.trainer(callers).userId
        val body = call.receive<SendMessageRequest>()
        call.respond(HttpStatusCode.Created, chat.send(trainerId, call.parameters["clientId"] ?: invalid("Missing clientId"), body))
    }
    post("/conversations/{clientId}/read") {
        val trainerId = call.trainer(callers).userId
        chat.markRead(trainerId, call.parameters["clientId"] ?: invalid("Missing clientId"), call.receive<MarkReadRequest>())
        call.respond(HttpStatusCode.NoContent)
    }

    post("/media/upload-sessions") {
        val trainerId = call.trainer(callers).userId
        call.respond(HttpStatusCode.Created, media.createUploadSession(trainerId, call.receive<CreateUploadSessionRequest>()))
    }
    get("/media/{id}") {
        val trainerId = call.trainer(callers).userId
        call.respond(media.status(trainerId, call.parameters["id"] ?: invalid("Missing id")))
    }
}

/** Unauthenticated, signed-link media bytes (`/media/{id}/content`). Mount at the root. */
fun Route.mediaContentRoutes(db: Db, clock: ServerClock, config: AppConfig) {
    // The audit log is only used by the authenticated status call.
    val media = MediaService(db, clock, config, AuditLog(db.dataSource))

    put("/media/{id}/content") {
        media.receiveUpload(call.parameters["id"] ?: invalid("Missing id"), call.request.queryParameters["token"], call.receiveStream())
        call.respond(HttpStatusCode.NoContent)
    }
    get("/media/{id}/content") {
        val content = media.openContent(call.parameters["id"] ?: invalid("Missing id"), call.request.queryParameters["token"])
        call.response.header(HttpHeaders.CacheControl, "private, no-store")
        call.respondOutputStream(ContentType.parse(content.contentType), HttpStatusCode.OK, content.sizeBytes) {
            content.stream.use { it.copyTo(this) }
        }
    }
}
