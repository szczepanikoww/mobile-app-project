package com.trainingapp.server.db

import com.trainingapp.server.platform.tstz
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time

/** Weekly working hours of a trainer in local time. */
object AvailabilityRules : Table("scheduling.availability_rule") {
    val id = javaUUID("id")
    val trainerId = javaUUID("trainer_id")
    val weekday = short("weekday")
    val startTime = time("start_time")
    val endTime = time("end_time")
    val timeZone = text("time_zone")
    val gymId = javaUUID("gym_id").nullable()
    val validFrom = date("valid_from")
    val validTo = date("valid_to").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Unavailability and private events of a trainer. */
object CalendarBlocks : Table("scheduling.calendar_block") {
    val id = javaUUID("id")
    val trainerId = javaUUID("trainer_id")
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
    val id = javaUUID("id")
    val relationshipId = javaUUID("relationship_id")
    val trainerId = javaUUID("trainer_id")
    val clientId = javaUUID("client_id")
    val offerId = javaUUID("offer_id").nullable()
    val status = text("status")
    val startsAt = tstz("starts_at")
    val endsAt = tstz("ends_at")
    val blockedUntil = tstz("blocked_until")
    val timeZone = text("time_zone")
    val gymId = javaUUID("gym_id").nullable()
    val placeNote = text("place_note").nullable()
    val priceMinor = long("price_minor")
    val currency = char("currency", 3)
    val paymentKind = text("payment_kind")
    val policyVersionId = javaUUID("policy_version_id")
    val policyAcceptedAt = tstz("policy_accepted_at")
    val revision = integer("revision")
    val createdBy = javaUUID("created_by")
    val operationId = javaUUID("operation_id")
    val requestedAt = tstz("requested_at")
    val confirmedAt = tstz("confirmed_at").nullable()
    val closedAt = tstz("closed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Append-only history of every session change. */
object SessionEvents : Table("scheduling.session_event") {
    val id = long("id").autoIncrement()
    val sessionId = javaUUID("session_id")
    val revision = integer("revision")
    val type = text("type")
    val actorId = javaUUID("actor_id").nullable()
    val oldStartsAt = tstz("old_starts_at").nullable()
    val oldEndsAt = tstz("old_ends_at").nullable()
    val occurredAt = tstz("occurred_at")
    override val primaryKey = PrimaryKey(id)
}

/** Reschedule negotiation; the session stays CONFIRMED until ACCEPTED commits. */
object RescheduleRequests : Table("scheduling.reschedule_request") {
    val id = javaUUID("id")
    val sessionId = javaUUID("session_id")
    val baseRevision = integer("base_revision")
    val proposedBy = javaUUID("proposed_by")
    val proposerRole = text("proposer_role")
    val proposedStartsAt = tstz("proposed_starts_at")
    val proposedEndsAt = tstz("proposed_ends_at")
    val counterStartsAt = tstz("counter_starts_at").nullable()
    val counterEndsAt = tstz("counter_ends_at").nullable()
    val status = text("status")
    val receivedAt = tstz("received_at")
    val feeQuoteId = javaUUID("fee_quote_id").nullable()
    val decidedBy = javaUUID("decided_by").nullable()
    val decidedAt = tstz("decided_at").nullable()
    val operationId = javaUUID("operation_id")
    override val primaryKey = PrimaryKey(id)
}

/** Append-only check-in / no-show outcomes; a correction is a new row. */
object Attendances : Table("scheduling.attendance") {
    val id = javaUUID("id")
    val sessionId = javaUUID("session_id")
    val outcome = text("outcome")
    val markedBy = javaUUID("marked_by")
    val markedAt = tstz("marked_at")
    val correctsId = javaUUID("corrects_id").nullable()
    val reason = text("reason").nullable()
    override val primaryKey = PrimaryKey(id)
}
