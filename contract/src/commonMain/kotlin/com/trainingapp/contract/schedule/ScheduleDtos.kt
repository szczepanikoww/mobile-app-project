package com.trainingapp.contract.schedule

import com.trainingapp.contract.api.IsoInstant
import com.trainingapp.contract.api.MoneyDto
import com.trainingapp.contract.catalog.PolicyTierDto
import kotlinx.serialization.Serializable

/*
 * The client's scheduling API (`/v1/client/...`, plan §8.1). The server is the only place that changes a session's
 * status; the app shows what these DTOs say.
 */

/** The one session status set (conflict C1): the same 8 values as `scheduling.session.status`. */
@Serializable
enum class SessionStatus {
    REQUESTED, CONFIRMED, REJECTED, EXPIRED, COMPLETED, NO_SHOW, CANCELLED_BY_CLIENT, CANCELLED_BY_TRAINER
}

@Serializable
enum class ProposalAuthorDto { CLIENT, TRAINER }

@Serializable
enum class PaymentKindDto { ONLINE, CASH, PACKAGE }

/** How a fee is settled, from how the session is paid. */
@Serializable
enum class ChargeKindDto { ONLINE, CASH, PACKAGE_CREDIT }

/** `id` is the trainer's user id. */
@Serializable
data class TrainerRefDto(val id: String, val name: String, val initials: String)

/** An open reschedule request. The session keeps its confirmed time until the request is accepted. */
@Serializable
data class ProposalDto(
    val start: IsoInstant,
    val end: IsoInstant,
    val author: ProposalAuthorDto,
    val requestedAt: IsoInstant,
)

@Serializable
data class SessionPaymentDto(val kind: PaymentKindDto, val packageUsed: Int? = null, val packageTotal: Int? = null)

@Serializable
data class SessionDto(
    val id: String,
    val trainerId: String,
    val clientId: String,
    val start: IsoInstant,
    val end: IsoInstant,
    val timeZone: String,
    /** The gym name or the free-text place. */
    val location: String,
    val status: SessionStatus,
    val revision: Int,
    val proposal: ProposalDto? = null,
    val payment: SessionPaymentDto,
    val policyVersionId: String,
    val price: MoneyDto,
    val requestedAt: IsoInstant,
)

/** `GET /v1/client/schedule`. [serverNow] drives "today" and "next"; the device clock never does. */
@Serializable
data class ClientScheduleDto(
    val trainers: List<TrainerRefDto>,
    val sessions: List<SessionDto>,
    val serverNow: IsoInstant,
)

/** A trainer's immutable cancellation policy version (`GET /v1/policies/{versionId}`). */
@Serializable
data class PolicyVersionDto(
    val versionId: String,
    val trainerId: String,
    val version: Int,
    val createdAt: IsoInstant,
    val tiers: List<PolicyTierDto>,
    val noShowPercent: Int,
    val graceMinutes: Int,
    val freeLateCancellationsPerMonth: Int,
    val lateRescheduleIsCancel: Boolean,
    val clientNote: String? = null,
)

@Serializable
data class BookingOptionsDto(
    val trainer: TrainerRefDto,
    /** The version the client must accept; a booking with an older one gets `409 POLICY_OUTDATED`. */
    val policy: PolicyVersionDto,
    val price: MoneyDto,
    val places: List<String>,
    val timeZone: String,
    val durationMinutes: Int,
)

@Serializable
data class SlotDto(val start: IsoInstant, val end: IsoInstant)

/**
 * `POST /v1/client/booking-requests` with the `Idempotency-Key` header (or [operationId]). A retry with the same key
 * returns the same session. [place] is one of [BookingOptionsDto.places] or a free-text place.
 */
@Serializable
data class BookingRequestDto(
    val trainerId: String,
    val start: IsoInstant,
    val end: IsoInstant,
    val place: String,
    val acceptedPolicyVersionId: String,
    val operationId: String? = null,
    /** Null: a package with free credits when the client has one, otherwise an online payment. */
    val paymentKind: PaymentKindDto? = null,
)

@Serializable
data class RevisionDto(val revision: Int)

@Serializable
data class RescheduleRequestDto(val revision: Int, val start: IsoInstant, val end: IsoInstant, val operationId: String)

@Serializable
data class ProposalAnswerDto(val revision: Int, val accept: Boolean)

/** What a late change would cost, computed by the shared `FeeCalculator` at the server's receipt time. */
@Serializable
data class FeeDto(
    val tierHoursBefore: Int?,
    val policyPercent: Int,
    val amount: MoneyDto,
    val usesFreeCancellation: Boolean,
)

@Serializable
data class RescheduleFeePreviewDto(val fee: FeeDto, val policyVersionId: String)

/** A fresh quote, valid for [revision] until [expiresAt] (server time). */
@Serializable
data class CancellationQuoteDto(
    val quoteId: String,
    val sessionId: String,
    val revision: Int,
    val fee: FeeDto,
    val policyVersionId: String,
    val freeLateCancellationsLeft: Int,
    val chargeKind: ChargeKindDto,
    /** True when the client's policy override exempts them from fees. */
    val exempt: Boolean = false,
    val quotedAt: IsoInstant,
    val expiresAt: IsoInstant,
)

@Serializable
data class CancelRequestDto(val revision: Int, val quoteId: String, val operationId: String)
