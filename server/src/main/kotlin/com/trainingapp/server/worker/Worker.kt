package com.trainingapp.server.worker

import com.trainingapp.contract.api.RescheduleStatus
import com.trainingapp.contract.api.SessionStatus
import com.trainingapp.server.db.OutboxEvents
import com.trainingapp.server.db.RescheduleRequests
import com.trainingapp.server.db.Sessions
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.Outbox
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.toOffset
import com.trainingapp.server.scheduling.SessionReads
import com.trainingapp.server.scheduling.event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Background work in the API process (architecture §5: same codebase, can run as a separate worker later).
 * - A booking request not answered before its start becomes EXPIRED; so does an open reschedule proposal.
 * - Outbox events are "published". There is no push provider yet, so publishing only marks them; FCM plugs in here.
 */
class Worker(private val db: Db, private val clock: ServerClock) {
    private val log = LoggerFactory.getLogger(Worker::class.java)

    fun start(): Job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        while (isActive) {
            runCatching { runOnce() }.onFailure { log.warn("Worker pass failed: {}", it.javaClass.simpleName) }
            delay(30.seconds)
        }
    }

    /** One pass; tests call it directly. Returns the number of expired sessions and proposals. */
    suspend fun runOnce(): Int {
        val expired = expire()
        publishOutbox()
        return expired
    }

    private suspend fun expire(): Int = db.tx {
        val now = clock.now()
        val sessions = Sessions.selectAll()
            .where { (Sessions.status eq SessionStatus.REQUESTED.name) and (Sessions.startsAt lessEq now.toOffset()) }
            .forUpdate(org.jetbrains.exposed.sql.vendors.ForUpdateOption.PostgreSQL.ForUpdate(org.jetbrains.exposed.sql.vendors.ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .toList()
        sessions.forEach { row ->
            val id = row[Sessions.id]
            val revision = row[Sessions.revision] + 1
            Sessions.update({ Sessions.id eq id }) {
                it[status] = SessionStatus.EXPIRED.name
                it[Sessions.revision] = revision
                it[closedAt] = now.toOffset()
            }
            event(id, revision, "EXPIRED", null, now)
            Outbox.emit("session.expired", "session", id, mapOf("relationshipId" to row[Sessions.relationshipId]), now)
        }
        // Proposals whose proposed (or countered) time, or the session itself, has started.
        val proposals = RescheduleRequests.join(Sessions, org.jetbrains.exposed.sql.JoinType.INNER, RescheduleRequests.sessionId, Sessions.id)
            .selectAll()
            .where {
                (RescheduleRequests.status inList SessionReads.OPEN_RESCHEDULE) and
                    ((RescheduleRequests.proposedStartsAt lessEq now.toOffset()) or (Sessions.startsAt lessEq now.toOffset()) or
                        (RescheduleRequests.counterStartsAt lessEq now.toOffset()))
            }
            .map { it[RescheduleRequests.id] }
        if (proposals.isNotEmpty()) {
            RescheduleRequests.update({ RescheduleRequests.id inList proposals }) {
                it[status] = RescheduleStatus.EXPIRED.name
                it[decidedAt] = now.toOffset()
            }
        }
        sessions.size + proposals.size
    }

    private suspend fun publishOutbox() = db.tx {
        val now = clock.now()
        val batch = OutboxEvents.selectAll().where { OutboxEvents.publishedAt.isNull() }
            .orderBy(OutboxEvents.id, SortOrder.ASC).limit(200).map { it[OutboxEvents.id] }
        if (batch.isNotEmpty()) {
            OutboxEvents.update({ OutboxEvents.id inList batch }) { it[publishedAt] = now.toOffset() }
        }
    }
}
