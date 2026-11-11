package com.trainingapp.server.platform

import com.trainingapp.server.common.toOffset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Writes the platform rows that belong to a business transaction: outbox events, delayed jobs and audit records
 * (plan §3 rule 1: "no write, then publish"). Payloads carry ids only.
 */
object Outbox {
    fun JdbcTransaction.emit(aggregateType: String, aggregateId: Uuid, type: String, ids: Map<String, Uuid?> = emptyMap()) {
        OutboxEvents.insert {
            it[OutboxEvents.aggregateType] = aggregateType
            it[OutboxEvents.aggregateId] = aggregateId
            it[OutboxEvents.type] = type
            it[payload] = idsPayload(ids)
        }
    }
}

fun idsPayload(ids: Map<String, Any?>): JsonObject =
    JsonObject(ids.filterValues { it != null }.mapValues { (_, v) -> JsonPrimitive(v.toString()) })

object JobQueue {
    const val REQUEST_EXPIRY = "request-expiry"
    const val RESCHEDULE_EXPIRY = "reschedule-expiry"
    const val REMINDER = "reminder"
    const val FAKE_PAYMENT_CONFIRM = "fake-payment-confirm"
    const val MEDIA_PROCESS = "media-process"

    /** Schedules a job once: a second call with the same [dedupeKey] is ignored. */
    fun JdbcTransaction.schedule(kind: String, dedupeKey: String?, runAt: Instant, payload: Map<String, Any?> = emptyMap()) {
        Jobs.insertIgnore {
            it[Jobs.kind] = kind
            it[Jobs.dedupeKey] = dedupeKey
            it[Jobs.payload] = idsPayload(payload)
            it[Jobs.runAt] = runAt.toOffset()
            it[status] = "PENDING"
            it[attempts] = 0
        }
    }

    fun JdbcTransaction.cancel(dedupeKey: String) {
        Jobs.update({ (Jobs.dedupeKey eq dedupeKey) and (Jobs.status eq "PENDING") }) { it[status] = "CANCELLED" }
    }

    /** Cancels every pending job whose key starts with [prefix], e.g. all reminders of one session. */
    fun JdbcTransaction.cancelPrefix(prefix: String) {
        Jobs.update({ (Jobs.dedupeKey like "$prefix%") and (Jobs.status inList listOf("PENDING")) }) { it[status] = "CANCELLED" }
    }
}

object Audit {
    /** Ids only (plan §9 rule 4). Written in its own transaction by the error handler, so a rollback cannot lose it. */
    fun JdbcTransaction.record(actorId: Uuid?, action: String, subjectType: String, subjectId: Uuid?, relationshipId: Uuid?, allowed: Boolean) {
        AuditRecords.insert {
            it[AuditRecords.actorId] = actorId
            it[AuditRecords.action] = action
            it[AuditRecords.subjectType] = subjectType
            it[AuditRecords.subjectId] = subjectId
            it[AuditRecords.relationshipId] = relationshipId
            it[outcome] = if (allowed) "ALLOWED" else "DENIED"
        }
    }
}
