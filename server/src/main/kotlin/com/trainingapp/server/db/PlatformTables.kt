package com.trainingapp.server.db

import com.trainingapp.server.platform.jsonb
import com.trainingapp.server.platform.tstz
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/** Transactional outbox written with the business change and delivered by the worker. */
object OutboxEvents : Table("platform.outbox_event") {
    val id = long("id").autoIncrement()
    val aggregateType = text("aggregate_type")
    val aggregateId = javaUUID("aggregate_id")
    val type = text("type")
    val payload = jsonb("payload")
    val createdAt = tstz("created_at")
    val publishedAt = tstz("published_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Delayed and retried background work (reminders, expiry, media checks). */
object Jobs : Table("platform.job") {
    val id = long("id").autoIncrement()
    val kind = text("kind")
    val dedupeKey = text("dedupe_key").nullable()
    val payload = jsonb("payload")
    val runAt = tstz("run_at")
    val status = text("status")
    val attempts = integer("attempts")
    val lockedUntil = tstz("locked_until").nullable()
    val lastError = text("last_error").nullable()
    val createdAt = tstz("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Replay protection for endpoints keyed by user and operation id. */
object IdempotencyKeys : Table("platform.idempotency_key") {
    val userId = javaUUID("user_id")
    val operationId = javaUUID("operation_id")
    val endpoint = text("endpoint")
    val requestHash = text("request_hash")
    val responseCode = integer("response_code").nullable()
    val responseBody = jsonb("response_body").nullable()
    val createdAt = tstz("created_at")
    override val primaryKey = PrimaryKey(userId, operationId)
}

/** Append-only audit of health-data reads, denied access and corrections. */
object AuditRecords : Table("platform.audit_record") {
    val id = long("id").autoIncrement()
    val actorId = javaUUID("actor_id").nullable()
    val action = text("action")
    val subjectType = text("subject_type")
    val subjectId = javaUUID("subject_id").nullable()
    val relationshipId = javaUUID("relationship_id").nullable()
    val outcome = text("outcome")
    val occurredAt = tstz("occurred_at")
    override val primaryKey = PrimaryKey(id)
}

/** Push notification tokens per user. */
object PushTokens : Table("platform.push_token") {
    val token = text("token")
    val userId = javaUUID("user_id")
    val platform = text("platform")
    val updatedAt = tstz("updated_at")
    override val primaryKey = PrimaryKey(token)
}

/** Secret, revocable calendar feed link; only the token hash is stored. */
object CalendarFeeds : Table("platform.calendar_feed") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id")
    val tokenHash = text("token_hash")
    val createdAt = tstz("created_at")
    val revokedAt = tstz("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
