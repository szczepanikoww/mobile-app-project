package com.trainingapp.server.platform

import org.jetbrains.exposed.v1.core.or
import com.trainingapp.server.db.OutboxEvents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import java.util.UUID

/** Parses a path/body id; a malformed id is INVALID_INPUT, never a 500. */
fun String.toUuid(field: String = "id"): UUID =
    runCatching { UUID.fromString(this) }.getOrElse { invalid("Invalid $field") }

fun String?.toUuidOrNull(field: String): UUID? = this?.toUuid(field)

fun newId(): UUID = UUID.randomUUID()

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Transactional outbox (architecture §5): written in the same transaction as the change, published later by the
 * worker. Payload holds ids only — never health data, notes or message text.
 */
object TrainerOutbox {
    fun emit(type: String, aggregateType: String, aggregateId: UUID, ids: Map<String, UUID?> = emptyMap(), now: kotlin.time.Instant) {
        val payload = JsonObject(ids.filterValues { it != null }.mapValues { JsonPrimitive(it.value.toString()) })
        OutboxEvents.insert {
            it[OutboxEvents.aggregateType] = aggregateType
            it[OutboxEvents.aggregateId] = aggregateId
            it[OutboxEvents.type] = type
            it[OutboxEvents.payload] = ApiJson.encodeToString(JsonObject.serializer(), payload)
            it[OutboxEvents.createdAt] = now.toOffset()
        }
    }
}

/** Splits a display name into up to two initials ("Katarzyna Nowak" → "KN"). */
fun initialsOf(name: String): String =
    name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
