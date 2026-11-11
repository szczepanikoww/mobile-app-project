package com.trainingapp.server.http

import com.trainingapp.contract.api.ApiErrorDto
import com.trainingapp.contract.api.IDEMPOTENCY_KEY_HEADER
import com.trainingapp.server.common.ApiException
import com.trainingapp.server.common.ErrorCode
import com.trainingapp.server.common.invalid
import com.trainingapp.server.db.Db
import com.trainingapp.server.db.SqlState
import com.trainingapp.server.db.constraintName
import com.trainingapp.server.db.sqlState
import com.trainingapp.server.platform.Audit.record
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

private val log = LoggerFactory.getLogger("trainer.http")
private val RouteTemplate = AttributeKey<String>("RouteTemplate")
private val StartedAt = AttributeKey<Long>("StartedAt")

/**
 * Request logging without private data (plan §4): method, route template (never the concrete path, which contains
 * ids, nor query values or bodies), status and duration.
 */
val PrivacyCallLogging = createApplicationPlugin("PrivacyCallLogging") {
    onCall { call -> call.attributes.put(StartedAt, System.nanoTime()) }
    on(ResponseSent) { call ->
        val started = call.attributes.getOrNull(StartedAt) ?: return@on
        val template = call.attributes.getOrNull(RouteTemplate) ?: "(unmatched)"
        val ms = (System.nanoTime() - started) / 1_000_000
        log.info("{} {} -> {} in {} ms", call.request.httpMethod.value, template, call.response.status()?.value, ms)
    }
}

fun Application.installRouteTemplateCapture() {
    monitor.subscribe(RoutingRoot.RoutingCallStarted) { call: RoutingCall ->
        val template = call.route.parent.toString().replace(Regex("/\\(method:[A-Z]+\\)"), "")
        call.attributes.put(RouteTemplate, template.ifEmpty { "/" })
    }
}

/**
 * Maps business results and SQLSTATEs to the single error body (plan §3 rule 2 and §4). The trainer API (`/api/...`,
 * docs/features/trainer-backend.md) has its own error body; [trainerApi] answers its errors when it is installed.
 */
fun Application.installErrorHandling(db: Db, trainerApi: (suspend ApplicationCall.(Throwable) -> Unit)? = null) {
    install(StatusPages) {
        exception<Throwable> { call, e ->
            if (trainerApi != null && call.request.path().startsWith("/api/")) call.trainerApi(e) else call.respondClientError(e, db)
        }
    }
}

private suspend fun ApplicationCall.respondClientError(e: Throwable, db: Db) {
    val call = this
    when (e) {
        is ApiException -> {
            e.deniedSubject?.let { subject ->
                // Its own transaction: the request's transaction has been rolled back.
                runCatching {
                    db.tx { record(call.callerUserIdOrNull(), "ACCESS_DENIED", subject.type, subject.id, subject.relationshipId, allowed = false) }
                }.onFailure { log.warn("audit record failed: {}", it.javaClass.simpleName) }
            }
            call.respond(HttpStatusCode.fromValue(e.status), ApiErrorDto(e.code, e.message, e.fields))
        }
        is BadRequestException, is SerializationException -> call.respondError(422, ErrorCode.VALIDATION, "malformed request")
        else -> when (e.sqlState()) {
            SqlState.EXCLUSION_VIOLATION -> call.respondError(409, ErrorCode.SLOT_TAKEN, "time is no longer free")
            SqlState.UNIQUE_VIOLATION -> call.respondError(409, ErrorCode.CONFLICT, "conflict on ${e.constraintName()}")
            SqlState.FOREIGN_KEY_VIOLATION -> call.respondError(409, ErrorCode.DEPENDENCY_MISSING, "missing ${e.constraintName()}")
            SqlState.CHECK_VIOLATION -> {
                // The API validates first: a CHECK reaching this point is a missing validation (a bug).
                log.error("check constraint {} reached the database", e.constraintName())
                call.respondError(422, ErrorCode.VALIDATION, "invalid value")
            }
            SqlState.RAISE_EXCEPTION -> {
                log.error("attempt to change an immutable row")
                call.respondError(500, ErrorCode.INTERNAL, "internal error")
            }
            else -> {
                // Class name only: exception messages can contain SQL values.
                log.error("unhandled {} at {}", e.javaClass.name, e.stackTrace.firstOrNull())
                call.respondError(500, ErrorCode.INTERNAL, "internal error")
            }
        }
    }
}

suspend fun ApplicationCall.respondError(status: Int, code: String, message: String) =
    respond(HttpStatusCode.fromValue(status), ApiErrorDto(code, message))

/** The operation id from the `Idempotency-Key` header or the body field. */
fun ApplicationCall.operationId(fromBody: String? = null): Uuid {
    val raw = request.headers[IDEMPOTENCY_KEY_HEADER] ?: fromBody ?: throw invalid("operationId")
    return runCatching { Uuid.parse(raw) }.getOrNull() ?: throw invalid("operationId")
}

fun ApplicationCall.pathUuid(name: String, what: String = name): Uuid {
    val raw = parameters[name] ?: throw invalid(name)
    return runCatching { Uuid.parse(raw) }.getOrNull()
        ?: throw ApiException(404, ErrorCode.NOT_FOUND, "$what not found")
}
