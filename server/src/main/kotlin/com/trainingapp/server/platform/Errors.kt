package com.trainingapp.server.platform

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.SQLException

/**
 * Error codes the app maps to its `Failure` values. Messages are short and never contain private data.
 * CONFLICT: stale revision, overlapping booking, base version no longer current. QUOTE_EXPIRED: get a fresh quote.
 */
enum class ErrorCode(val status: HttpStatusCode) {
    INVALID_INPUT(HttpStatusCode.UnprocessableEntity),
    UNAUTHENTICATED(HttpStatusCode.Unauthorized),
    DENIED(HttpStatusCode.Forbidden),
    NOT_FOUND(HttpStatusCode.NotFound),
    CONFLICT(HttpStatusCode.Conflict),
    QUOTE_EXPIRED(HttpStatusCode.Conflict),
    INTERNAL(HttpStatusCode.InternalServerError),
}

@Serializable
data class ErrorBody(val code: String, val message: String, val details: List<String> = emptyList())

class ApiException(val code: ErrorCode, message: String, val details: List<String> = emptyList()) : RuntimeException(message)

fun invalid(message: String, details: List<String> = emptyList()): Nothing = throw ApiException(ErrorCode.INVALID_INPUT, message, details)
fun denied(message: String = "Access denied"): Nothing = throw ApiException(ErrorCode.DENIED, message)
fun notFound(message: String = "Not found"): Nothing = throw ApiException(ErrorCode.NOT_FOUND, message)
fun conflict(message: String): Nothing = throw ApiException(ErrorCode.CONFLICT, message)

private val log = LoggerFactory.getLogger("Errors")

/** PostgreSQL states that mean "someone else changed this first", reported as CONFLICT. */
private val conflictStates = setOf(
    "23P01", // exclusion_violation: overlapping confirmed session
    "23505", // unique_violation: e.g. second open reschedule, second active plan
    "40001", // serialization_failure
    "40P01", // deadlock_detected
)

fun Throwable.sqlState(): String? {
    var t: Throwable? = this
    while (t != null) {
        if (t is SQLException && t.sqlState != null) return t.sqlState
        t = t.cause
    }
    return null
}

fun Application.installErrors() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.code.status, ErrorBody(e.code.name, e.message ?: e.code.name, e.details))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorBody(ErrorCode.INVALID_INPUT.name, "Malformed request"))
        }
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorBody(ErrorCode.INVALID_INPUT.name, e.message ?: "Invalid input"))
        }
        exception<Throwable> { call, e ->
            val state = e.sqlState()
            when {
                state in conflictStates ->
                    call.respond(HttpStatusCode.Conflict, ErrorBody(ErrorCode.CONFLICT.name, "Conflicting change"))
                state != null && state.startsWith("23") ->
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorBody(ErrorCode.INVALID_INPUT.name, "Constraint violated"))
                else -> {
                    log.error("Unhandled error (sql state {})", state, e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorBody(ErrorCode.INTERNAL.name, "Internal error"))
                }
            }
        }
    }
}
