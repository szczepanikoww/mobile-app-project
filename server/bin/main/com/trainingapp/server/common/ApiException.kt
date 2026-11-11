package com.trainingapp.server.common

import kotlin.uuid.Uuid

/** Error codes of the `{ "code", "message" }` error body (plan §4). The app maps codes, never messages. */
object ErrorCode {
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val NO_ACCOUNT = "NO_ACCOUNT"
    const val FORBIDDEN = "FORBIDDEN"
    const val NOT_FOUND = "NOT_FOUND"
    const val CONFLICT = "CONFLICT"
    const val REVISION_CONFLICT = "REVISION_CONFLICT"
    const val SLOT_TAKEN = "SLOT_TAKEN"
    const val POLICY_OUTDATED = "POLICY_OUTDATED"
    const val DEPENDENCY_MISSING = "DEPENDENCY_MISSING"
    const val QUOTE_EXPIRED = "QUOTE_EXPIRED"
    const val VALIDATION = "VALIDATION"
    const val IDEMPOTENCY_MISMATCH = "IDEMPOTENCY_MISMATCH"
    const val INTERNAL = "INTERNAL"
}

/**
 * A business result that becomes an HTTP error. [message] is for logs only and must never contain private values.
 * [deniedSubject] marks an access denial that is written to `platform.audit_record` (plan §9 rule 4).
 */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val fields: List<String> = emptyList(),
    val deniedSubject: DeniedSubject? = null,
) : RuntimeException(message)

data class DeniedSubject(val type: String, val id: Uuid?, val relationshipId: Uuid? = null)

fun notFound(what: String, denied: DeniedSubject? = null) = ApiException(404, ErrorCode.NOT_FOUND, "$what not found", deniedSubject = denied)
fun forbidden(what: String, denied: DeniedSubject? = null) = ApiException(403, ErrorCode.FORBIDDEN, what, deniedSubject = denied)
fun conflict(code: String = ErrorCode.CONFLICT, message: String) = ApiException(409, code, message)
fun invalid(vararg fields: String) = ApiException(422, ErrorCode.VALIDATION, "invalid: ${fields.joinToString()}", fields.toList())

/** Parses a path or body id; a malformed id is treated like a missing resource. */
fun String.toUuidOrNotFound(what: String): Uuid = runCatching { Uuid.parse(this) }.getOrNull() ?: throw notFound(what)
fun String.toUuidOrInvalid(field: String): Uuid = runCatching { Uuid.parse(this) }.getOrNull() ?: throw invalid(field)
