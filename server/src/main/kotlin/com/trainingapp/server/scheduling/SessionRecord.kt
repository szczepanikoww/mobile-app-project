package com.trainingapp.server.scheduling

import com.trainingapp.contract.api.MoneyDto
import com.trainingapp.contract.schedule.SessionStatus
import com.trainingapp.server.common.toKotlin
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A `scheduling.session` row as the Scheduling and Billing modules use it. */
data class SessionRecord(
    val id: Uuid,
    val relationshipId: Uuid,
    val trainerId: Uuid,
    val clientId: Uuid,
    val offerId: Uuid?,
    val status: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val blockedUntil: Instant,
    val timeZone: String,
    val gymId: Uuid?,
    val placeNote: String?,
    val price: MoneyDto,
    val paymentKind: String,
    val policyVersionId: Uuid,
    val revision: Int,
    val requestedAt: Instant,
    val operationId: Uuid,
) {
    companion object {
        fun from(row: ResultRow) = SessionRecord(
            id = row[Sessions.id],
            relationshipId = row[Sessions.relationshipId],
            trainerId = row[Sessions.trainerId],
            clientId = row[Sessions.clientId],
            offerId = row[Sessions.offerId],
            status = row[Sessions.status],
            startsAt = row[Sessions.startsAt].toKotlin(),
            endsAt = row[Sessions.endsAt].toKotlin(),
            blockedUntil = row[Sessions.blockedUntil].toKotlin(),
            timeZone = row[Sessions.timeZone],
            gymId = row[Sessions.gymId],
            placeNote = row[Sessions.placeNote],
            price = MoneyDto(row[Sessions.priceMinor], row[Sessions.currency]),
            paymentKind = row[Sessions.paymentKind],
            policyVersionId = row[Sessions.policyVersionId],
            revision = row[Sessions.revision],
            requestedAt = row[Sessions.requestedAt].toKotlin(),
            operationId = row[Sessions.operationId],
        )
    }
}

/** The one mapping between `scheduling.session.status` and the contract enum (conflict C1). */
object SessionStatusMapper {
    fun fromDb(value: String): SessionStatus = SessionStatus.valueOf(value)
    fun toDb(value: SessionStatus): String = value.name
}
