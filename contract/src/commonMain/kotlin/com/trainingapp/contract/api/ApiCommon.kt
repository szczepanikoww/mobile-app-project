package com.trainingapp.contract.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/*
 * Shared pieces of the HTTP API (docs/plans/backend-client-guest.md §4). The server serialises these classes and the
 * app decodes them with the same code, so field names cannot drift.
 */

/** ISO-8601 UTC instant, e.g. `2026-11-11T09:00:00Z`. */
object IsoInstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("IsoInstant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

typealias ApiInstant = @Serializable(with = IsoInstantSerializer::class) Instant

/** Error body of every non-2xx answer. [code] is one of [ApiErrorCode]. */
@Serializable
data class ApiError(val code: String, val message: String, val details: List<String> = emptyList())

/**
 * INVALID_INPUT 422, UNAUTHENTICATED 401, DENIED 403, NOT_FOUND 404, CONFLICT 409 (stale revision, overlapping
 * session, policy base no longer current), QUOTE_EXPIRED 409 (ask for a fresh quote), INTERNAL 500.
 */
enum class ApiErrorCode { INVALID_INPUT, UNAUTHENTICATED, DENIED, NOT_FOUND, CONFLICT, QUOTE_EXPIRED, INTERNAL }

@Serializable
enum class Role { TRAINER, CLIENT }

@Serializable
data class MeDto(
    val userId: String,
    val displayName: String,
    val email: String? = null,
    val locale: String,
    val timeZone: String,
    val roles: Set<Role>,
)

/** Local development only (`AUTH_MODE=dev`): a token for a seeded demo account. */
@Serializable
data class DevTokenRequest(val subject: String)

@Serializable
data class TokenDto(val token: String, val userId: String)

typealias IsoInstant = @Serializable(with = IsoInstantSerializer::class) Instant

/** `{ "minor": 12000, "currency": "PLN" }` — integer minor units plus ISO 4217 code (contract Money). */
@Serializable
data class MoneyDto(val minor: Long, val currency: String)

/**
 * The single error body. [code] is what the app maps (see [ApiErrorCodes]); [message] is for logs and is never shown.
 * [fields] names the invalid request fields for `422 VALIDATION`.
 */
@Serializable
data class ApiErrorDto(val code: String, val message: String, val fields: List<String> = emptyList())

object ApiErrorCodes {
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
}

/** Header carrying the client-generated operation id of a create command. */
const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
