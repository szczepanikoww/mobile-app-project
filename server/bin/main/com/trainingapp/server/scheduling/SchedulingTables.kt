package com.trainingapp.server.scheduling

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Exposed mappings of the `scheduling` schema (V1).

object AvailabilityRules : Table("scheduling.availability_rule") {
    val id = uuid("id")
    val trainerId = uuid("trainer_id")
    val weekday = short("weekday")
    val startTime = time("start_time")
    val endTime = time("end_time")
    val timeZone = text("time_zone")
    val gymId = uuid("gym_id").nullable()
    val validFrom = date("valid_from")
    val validTo = date("valid_to").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Private: [title] and [kind] never leave the server; slots expose only free time (plan §9.6). */
object CalendarBlocks : Table("scheduling.calendar_block") {
    val id = uuid("id")
    val trainerId = uuid("trainer_id")
    val kind = text("kind")
    val title = text("title").nullable()
    val startsAt = timestampWithTimeZone("starts_at")
    val endsAt = timestampWithTimeZone("ends_at")
    val createdAt = timestampWithTimeZone("created_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Sessions : Table("scheduling.session") {
    val id = uuid("id")
    val relationshipId = uuid("relationship_id")
    val trainerId = uuid("trainer_id")
    val clientId = uuid("client_id")
    val offerId = uuid("offer_id").nullable()
    val status = text("status")
    val startsAt = timestampWithTimeZone("starts_at")
    val endsAt = timestampWithTimeZone("ends_at")
    val blockedUntil = timestampWithTimeZone("blocked_until")
    val timeZone = text("time_zone")
    val gymId = uuid("gym_id").nullable()
    val placeNote = text("place_note").nullable()
    val priceMinor = long("price_minor")
    val currency = char("currency", 3)
    val paymentKind = text("payment_kind")
    val policyVersionId = uuid("policy_version_id")
    val policyAcceptedAt = timestampWithTimeZone("policy_accepted_at")
    val revision = integer("revision")
    val createdBy = uuid("created_by")
    val operationId = uuid("operation_id")
    val requestedAt = timestampWithTimeZone("requested_at")
    val confirmedAt = timestampWithTimeZone("confirmed_at").nullable()
    val closedAt = timestampWithTimeZone("closed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object SessionEvents : Table("scheduling.session_event") {
    val id = long("id").autoIncrement()
    val sessionId = uuid("session_id")
    val revision = integer("revision")
    val type = text("type")
    val actorId = uuid("actor_id").nullable()
    val oldStartsAt = timestampWithTimeZone("old_starts_at").nullable()
    val oldEndsAt = timestampWithTimeZone("old_ends_at").nullable()
    val occurredAt = timestampWithTimeZone("occurred_at")
    override val primaryKey = PrimaryKey(id)
}

object RescheduleRequests : Table("scheduling.reschedule_request") {
    val id = uuid("id")
    val sessionId = uuid("session_id")
    val baseRevision = integer("base_revision")
    val proposedBy = uuid("proposed_by")
    val proposerRole = text("proposer_role")
    val proposedStartsAt = timestampWithTimeZone("proposed_starts_at")
    val proposedEndsAt = timestampWithTimeZone("proposed_ends_at")
    val counterStartsAt = timestampWithTimeZone("counter_starts_at").nullable()
    val counterEndsAt = timestampWithTimeZone("counter_ends_at").nullable()
    val status = text("status")
    val receivedAt = timestampWithTimeZone("received_at")
    val feeQuoteId = uuid("fee_quote_id").nullable()
    val decidedBy = uuid("decided_by").nullable()
    val decidedAt = timestampWithTimeZone("decided_at").nullable()
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

object Attendances : Table("scheduling.attendance") {
    val id = uuid("id")
    val sessionId = uuid("session_id")
    val outcome = text("outcome")
    val markedBy = uuid("marked_by")
    val markedAt = timestampWithTimeZone("marked_at")
    val correctsId = uuid("corrects_id").nullable()
    val reason = text("reason").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** DB `session.status` values. The single mapper to `:contract` lives in [SessionStatusMapper]. */
object DbSessionStatus {
    const val REQUESTED = "REQUESTED"
    const val CONFIRMED = "CONFIRMED"
    const val REJECTED = "REJECTED"
    const val EXPIRED = "EXPIRED"
    const val COMPLETED = "COMPLETED"
    const val NO_SHOW = "NO_SHOW"
    const val CANCELLED_BY_CLIENT = "CANCELLED_BY_CLIENT"
    const val CANCELLED_BY_TRAINER = "CANCELLED_BY_TRAINER"

    /** Statuses that block time in the exclusion constraints. */
    val BLOCKING = listOf(CONFIRMED, COMPLETED, NO_SHOW)
}

object RescheduleStatus {
    const val PENDING = "PENDING"
    const val COUNTER_PROPOSED = "COUNTER_PROPOSED"
    const val ACCEPTED = "ACCEPTED"
    const val REJECTED = "REJECTED"
    const val WITHDRAWN = "WITHDRAWN"
    const val EXPIRED = "EXPIRED"
    val OPEN = listOf(PENDING, COUNTER_PROPOSED)
}
