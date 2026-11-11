package com.trainingapp.contract.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/** Instants travel as ISO-8601 UTC strings, e.g. `2026-11-12T08:00:00Z`. */
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
