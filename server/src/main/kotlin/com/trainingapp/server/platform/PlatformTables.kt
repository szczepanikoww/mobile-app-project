package com.trainingapp.server.platform

import com.trainingapp.server.common.ApiJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

// Exposed mappings of the `platform` and `coaching.consent_record` tables (V1).

object OutboxEvents : Table("platform.outbox_event") {
    val id = long("id").autoIncrement()
    val aggregateType = text("aggregate_type")
    val aggregateId = uuid("aggregate_id")
    val type = text("type")
    /** Ids only: no health data, no message text (plan §9.5). */
    val payload = jsonb("payload", ApiJson, JsonObject.serializer())
    val createdAt = timestampWithTimeZone("created_at")
    val publishedAt = timestampWithTimeZone("published_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Jobs : Table("platform.job") {
    val id = long("id").autoIncrement()
    val kind = text("kind")
    val dedupeKey = text("dedupe_key").nullable()
    val payload = jsonb("payload", ApiJson, JsonObject.serializer())
    val runAt = timestampWithTimeZone("run_at")
    val status = text("status")
    val attempts = integer("attempts")
    val lockedUntil = timestampWithTimeZone("locked_until").nullable()
    val lastError = text("last_error").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object IdempotencyKeys : Table("platform.idempotency_key") {
    val userId = uuid("user_id")
    val operationId = uuid("operation_id")
    val endpoint = text("endpoint")
    val requestHash = text("request_hash")
    val responseCode = integer("response_code").nullable()
    val responseBody = jsonb("response_body", ApiJson, JsonElement.serializer()).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(userId, operationId)
}

object AuditRecords : Table("platform.audit_record") {
    val id = long("id").autoIncrement()
    val actorId = uuid("actor_id").nullable()
    val action = text("action")
    val subjectType = text("subject_type")
    val subjectId = uuid("subject_id").nullable()
    val relationshipId = uuid("relationship_id").nullable()
    val outcome = text("outcome")
    val occurredAt = timestampWithTimeZone("occurred_at")
    override val primaryKey = PrimaryKey(id)
}

object PushTokens : Table("platform.push_token") {
    val token = text("token")
    val userId = uuid("user_id")
    val platform = text("platform")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(token)
}

object ConsentRecords : Table("coaching.consent_record") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val purpose = text("purpose")
    val documentVersion = text("document_version")
    val action = text("action")
    val recordedAt = timestampWithTimeZone("recorded_at")
    override val primaryKey = PrimaryKey(id)
}
