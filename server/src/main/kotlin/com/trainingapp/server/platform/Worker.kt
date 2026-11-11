package com.trainingapp.server.platform

import com.trainingapp.contract.chat.LiveEventDto
import com.trainingapp.server.billing.BillingService
import com.trainingapp.server.common.dbNow
import com.trainingapp.server.common.toOffset
import com.trainingapp.server.db.Db
import com.trainingapp.server.media.MediaService
import com.trainingapp.server.scheduling.SchedulingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Push delivery. FCM data messages only say "refresh"; they never carry content (plan §8.6). */
fun interface PushSender {
    fun send(userId: Uuid, tokens: List<String>, data: Map<String, String>)

    companion object {
        /** Until FCM credentials exist: logs that a push would be sent, with ids and kind only. */
        val Logging = PushSender { userId, tokens, data ->
            LoggerFactory.getLogger("trainer.push").info("push to user {} ({} tokens): {}", userId, tokens.size, data["kind"])
        }
    }
}

/**
 * The worker (architecture §5): delivers outbox events (push + WebSocket), runs due jobs with
 * `FOR UPDATE SKIP LOCKED` so several workers never take the same row, and runs media maintenance.
 */
class Worker(
    private val db: Db,
    private val scheduling: SchedulingService,
    private val billing: BillingService,
    private val media: MediaService,
    private val push: PushSender,
    private val realtime: RealtimeHub,
) {
    private val log = LoggerFactory.getLogger("trainer.worker")

    fun start(scope: CoroutineScope): Job = scope.launch {
        var lastMaintenance = 0L
        while (isActive) {
            val worked = runCatching { drainOutbox() + runDueJobs() }.onFailure { log.error("worker loop failed: {}", it.javaClass.simpleName) }.getOrDefault(0)
            if (System.currentTimeMillis() - lastMaintenance > 10.minutes.inWholeMilliseconds) {
                runCatching { media.maintenance() }.onFailure { log.error("media maintenance failed: {}", it.javaClass.simpleName) }
                lastMaintenance = System.currentTimeMillis()
            }
            if (worked == 0) delay(1.seconds)
        }
    }

    /** Maps outbox events to "refresh" signals for the other party (push tokens and open WebSockets). */
    suspend fun drainOutbox(batch: Int = 50): Int {
        val events = db.tx {
            val rows = OutboxEvents.selectAll().where { OutboxEvents.publishedAt.isNull() }
                .orderBy(OutboxEvents.id, SortOrder.ASC).limit(batch)
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                .map { Triple(it[OutboxEvents.id], it[OutboxEvents.aggregateType], it[OutboxEvents.payload]) }
            if (rows.isNotEmpty()) {
                val now = dbNow().toOffset()
                OutboxEvents.update({ OutboxEvents.id inList rows.map { it.first } }) { it[publishedAt] = now }
            }
            rows.map { (_, type, payload) -> deliveries(type, payload) }
        }
        for (delivery in events.flatten()) {
            realtime.publish(delivery.userId, delivery.event)
            val tokens = db.tx { PushTokens.selectAll().where { PushTokens.userId eq delivery.userId }.map { it[PushTokens.token] } }
            if (tokens.isNotEmpty()) push.send(delivery.userId, tokens, mapOf("kind" to (delivery.event.kind ?: delivery.event.type)))
        }
        return events.size
    }

    private data class Delivery(val userId: Uuid, val event: LiveEventDto)

    private fun deliveries(aggregateType: String, payload: JsonObject): List<Delivery> {
        fun id(key: String) = payload[key]?.jsonPrimitive?.content?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        return when (aggregateType) {
            "session" -> listOfNotNull(id("clientId"), id("trainerId")).map { Delivery(it, LiveEventDto("refresh", kind = "schedule")) }
            "charge", "payment" -> listOfNotNull(id("clientId")).map { Delivery(it, LiveEventDto("refresh", kind = "wallet")) }
            "message" -> listOfNotNull(id("recipientId")).map {
                Delivery(it, LiveEventDto("message", relationshipId = payload["relationshipId"]?.jsonPrimitive?.content, kind = "chat"))
            }
            "media" -> listOfNotNull(id("ownerId")).map { Delivery(it, LiveEventDto("refresh", kind = "media")) }
            else -> emptyList()
        }
    }

    /** Claims due jobs (SKIP LOCKED), runs each, and marks it DONE or retries with backoff up to 5 attempts. */
    suspend fun runDueJobs(batch: Int = 20): Int {
        val claimed = db.tx {
            val now = dbNow()
            // RUNNING jobs whose lock expired belong to a crashed worker and are taken again.
            val rows = Jobs.selectAll().where {
                ((Jobs.status eq "PENDING") and (Jobs.runAt lessEq now.toOffset())) or
                    ((Jobs.status eq "RUNNING") and (Jobs.lockedUntil lessEq now.toOffset()))
            }
                .orderBy(Jobs.runAt, SortOrder.ASC).limit(batch)
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                .map { Triple(it[Jobs.id], it[Jobs.kind], it[Jobs.payload]) to it[Jobs.attempts] }
            if (rows.isNotEmpty()) {
                Jobs.update({ Jobs.id inList rows.map { it.first.first } }) {
                    it[status] = "RUNNING"
                    it[lockedUntil] = (now + 5.minutes).toOffset()
                }
            }
            rows
        }
        for ((job, attempts) in claimed) {
            val (id, kind, payload) = job
            val result = runCatching { run(kind, payload) }
            db.tx {
                val now = dbNow()
                Jobs.update({ Jobs.id eq id }) {
                    if (result.isSuccess) {
                        it[status] = "DONE"
                    } else {
                        val next = attempts + 1
                        it[Jobs.attempts] = next
                        it[status] = if (next >= 5) "FAILED" else "PENDING"
                        it[runAt] = (now + (30L * next * next).seconds).toOffset()
                        it[lastError] = result.exceptionOrNull()?.javaClass?.simpleName
                    }
                    it[lockedUntil] = null
                }
            }
        }
        return claimed.size
    }

    private suspend fun run(kind: String, payload: JsonObject) {
        fun id(key: String) = Uuid.parse(payload.getValue(key).jsonPrimitive.content)
        when (kind) {
            JobQueue.REQUEST_EXPIRY -> scheduling.expireRequest(id("sessionId"))
            JobQueue.RESCHEDULE_EXPIRY -> scheduling.expireReschedule(id("requestId"))
            JobQueue.REMINDER -> {
                val revision = payload.getValue("revision").jsonPrimitive.content.toInt()
                // Acceptance test 7: a moved or cancelled session never gets the old revision's reminder.
                val session = scheduling.reminderStillValid(id("sessionId"), revision) ?: return
                for (user in listOf(session.clientId, session.trainerId)) {
                    val tokens = db.tx { PushTokens.selectAll().where { PushTokens.userId eq user }.map { it[PushTokens.token] } }
                    if (tokens.isNotEmpty()) push.send(user, tokens, mapOf("kind" to "reminder", "sessionId" to session.id.toString()))
                }
            }
            JobQueue.FAKE_PAYMENT_CONFIRM -> billing.confirmFakePayment(id("paymentId"))
            JobQueue.MEDIA_PROCESS -> media.process(id("mediaId"))
            else -> log.warn("unknown job kind {}", kind)
        }
    }
}
