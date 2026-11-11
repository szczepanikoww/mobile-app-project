package com.trainingapp.server.db

import com.trainingapp.server.platform.tstz
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.time

/** Weekly working hours of a trainer in local time. */
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

/** Unavailability and private events of a trainer. */
object CalendarBlocks : Table("scheduling.calendar_block") {
    val id = uuid("id")
    val trainerId = uuid("trainer_id")
    val kind = text("kind")
    val title = text("title").nullable()
    val startsAt = tstz("starts_at")
    val endsAt = tstz("ends_at")
    val createdAt = tstz("created_at")
    val deletedAt = tstz("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** A booked coaching session; a booking request is a session in REQUESTED. */
object Sessions : Table("scheduling.session") {
    val id = uuid("id")
    val relationshipId = uuid("relationship_id")
    val trainerId = uuid("trainer_id")
    val clientId = uuid("client_id")
    val offerId = uuid("offer_id").nullable()
    val status = text("status")
    val startsAt = tstz("starts_at")
    val endsAt = tstz("ends_at")
    val blockedUntil = tstz("blocked_until")
    val timeZone = text("time_zone")
    val gymId = uuid("gym_id").nullable()
    val placeNote = text("place_note").nullable()
    val priceMinor = long("price_minor")
    val currency = char("currency", 3)
    val paymentKind = text("payment_kind")
    val policyVersionId = uuid("policy_version_id")
    val policyAcceptedAt = tstz("policy_accepted_at")
    val revision = integer("revision")
    val createdBy = uuid("created_by")
    val operationId = uuid("operation_id")
    val requestedAt = tstz("requested_at")
    val confirmedAt = tstz("confirmed_at").nullable()
    val closedAt = tstz("closed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Append-only history of every session change. */
object SessionEvents : Table("scheduling.session_event") {
    val id = long("id").autoIncrement()
    val sessionId = uuid("session_id")
    val revision = integer("revision")
    val type = text("type")
    val actorId = uuid("actor_id").nullable()
    val oldStartsAt = tstz("old_starts_at").nullable()
    val oldEndsAt = tstz("old_ends_at").nullable()
    val occurredAt = tstz("occurred_at")
    override val primaryKey = PrimaryKey(id)
}

/** Reschedule negotiation; the session stays CONFIRMED until ACCEPTED commits. */
object RescheduleRequests : Table("scheduling.reschedule_request") {
    val id = uuid("id")
    val sessionId = uuid("session_id")
    val baseRevision = integer("base_revision")
    val proposedBy = uuid("proposed_by")
    val proposerRole = text("proposer_role")
    val proposedStartsAt = tstz("proposed_starts_at")
    val proposedEndsAt = tstz("proposed_ends_at")
    val counterStartsAt = tstz("counter_starts_at").nullable()
    val counterEndsAt = tstz("counter_ends_at").nullable()
    val status = text("status")
    val receivedAt = tstz("received_at")
    val feeQuoteId = uuid("fee_quote_id").nullable()
    val decidedBy = uuid("decided_by").nullable()
    val decidedAt = tstz("decided_at").nullable()
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

/** Append-only check-in / no-show outcomes; a correction is a new row. */
object Attendances : Table("scheduling.attendance") {
    val id = uuid("id")
    val sessionId = uuid("session_id")
    val outcome = text("outcome")
    val markedBy = uuid("marked_by")
    val markedAt = tstz("marked_at")
    val correctsId = uuid("corrects_id").nullable()
    val reason = text("reason").nullable()
    override val primaryKey = PrimaryKey(id)
}
