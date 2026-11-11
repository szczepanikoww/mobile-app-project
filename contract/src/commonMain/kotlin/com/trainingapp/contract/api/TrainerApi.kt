package com.trainingapp.contract.api

import com.trainingapp.contract.money.Money
import kotlinx.serialization.Serializable

/*
 * Trainer API (`/api/v1/trainer/...`). Wire DTOs shared by the app and the server; enum values equal the database
 * CHECK values. IDs are UUID strings. A "client id" is always the trainer–client relationship id, as in the app.
 * Every command that changes a session carries the session `revision` the caller saw; a stale one answers CONFLICT.
 * Commands that create something carry an `operationId` (UUID) so a retry returns the first result.
 */

// ---------------------------------------------------------------- sessions and calendar

/** The single session lifecycle (architecture §7.1, database-design C1). */
@Serializable
enum class SessionStatus { REQUESTED, CONFIRMED, REJECTED, EXPIRED, COMPLETED, NO_SHOW, CANCELLED_BY_CLIENT, CANCELLED_BY_TRAINER }

@Serializable
enum class PaymentKind { ONLINE, CASH, PACKAGE }

@Serializable
enum class ProposerRole { CLIENT, TRAINER }

@Serializable
enum class RescheduleStatus { PENDING, COUNTER_PROPOSED, ACCEPTED, REJECTED, WITHDRAWN, EXPIRED }

@Serializable
enum class RelationshipStatus { PENDING, ACTIVE, ENDED }

/** An open reschedule proposal. The session keeps its confirmed time until the proposal is accepted. */
@Serializable
data class ProposalDto(
    val id: String,
    val start: ApiInstant,
    val end: ApiInstant,
    val author: ProposerRole,
    val status: RescheduleStatus,
    /** Server receipt time; a late-reschedule fee tier is taken from it. */
    val requestedAt: ApiInstant,
)

/** [packageUsed] of [packageTotal] credits in the current period when paid from a package. */
@Serializable
data class SessionPaymentDto(val kind: PaymentKind, val packageUsed: Int? = null, val packageTotal: Int? = null)

@Serializable
data class SessionDto(
    val id: String,
    val clientId: String,
    val trainerId: String,
    val start: ApiInstant,
    val end: ApiInstant,
    val timeZone: String,
    val location: String,
    val gymId: String? = null,
    val status: SessionStatus,
    val revision: Int,
    val proposal: ProposalDto? = null,
    val planTitle: String? = null,
    val payment: SessionPaymentDto,
    val policyVersionId: String,
    val price: Money,
    val requestedAt: ApiInstant,
)

@Serializable
data class ClientSummaryDto(
    /** The trainer–client relationship id. */
    val id: String,
    val userId: String,
    val name: String,
    val initials: String,
    val status: RelationshipStatus,
    /** The only financial fact the client list shows (CLAUDE.md §7). */
    val hasOverduePayment: Boolean,
)

@Serializable
enum class BlockKind { UNAVAILABLE, PRIVATE_EVENT }

@Serializable
data class CalendarBlockDto(val id: String, val kind: BlockKind, val title: String? = null, val start: ApiInstant, val end: ApiInstant)

@Serializable
data class TrainerDashboardDto(
    val clients: List<ClientSummaryDto>,
    val sessions: List<SessionDto>,
    val blocks: List<CalendarBlockDto>,
    /** The latest private note per client id, written by this trainer. */
    val notes: Map<String, String>,
    val serverNow: ApiInstant,
)

@Serializable
data class CreateSessionRequest(
    val clientId: String,
    val start: ApiInstant,
    val end: ApiInstant,
    val location: String = "",
    val gymId: String? = null,
    /** The price list entry; null uses the trainer's first single-session offer. */
    val offerId: String? = null,
    val paymentKind: PaymentKind = PaymentKind.CASH,
    val operationId: String,
)

@Serializable
data class RevisionRequest(val revision: Int, val operationId: String? = null)

/** A full fee waiver with an optional reason the client can see (D8: full waivers only). */
@Serializable
data class FeeWaiverDto(val reason: String? = null)

@Serializable
data class AcceptRequest(val revision: Int, val waiver: FeeWaiverDto? = null, val operationId: String? = null)

@Serializable
data class ProposeTimeRequest(val revision: Int, val start: ApiInstant, val end: ApiInstant, val operationId: String)

/** Answer of every session command: the changed session and the server time. */
@Serializable
data class SessionResultDto(val session: SessionDto, val serverNow: ApiInstant)

@Serializable
data class CheckInDto(
    val session: SessionDto,
    val client: ClientSummaryDto,
    val started: Boolean,
    val noShowAvailableAt: ApiInstant,
    val noShowAllowed: Boolean,
    val graceMinutes: Int,
    val serverNow: ApiInstant,
)

@Serializable
enum class RequestKind { BOOKING, RESCHEDULE }

@Serializable
data class FeeQuoteDto(
    val tierHoursBefore: Int? = null,
    val policyPercent: Int,
    val amount: Money,
    val usesFreeCancellation: Boolean,
)

@Serializable
data class RequestReviewDto(
    val session: SessionDto,
    val client: ClientSummaryDto,
    val kind: RequestKind,
    val requestedAt: ApiInstant,
    val requestedStart: ApiInstant,
    val requestedEnd: ApiInstant,
    /** The asked-for time fits the trainer's other confirmed sessions and blocks. */
    val slotAvailable: Boolean,
    val policy: PolicyDto,
    /** For a late reschedule: the fee the client's policy version proposes; null when none. */
    val lateFee: FeeQuoteDto? = null,
    val serverNow: ApiInstant,
)

@Serializable
data class CreateBlockRequest(
    val start: ApiInstant,
    val end: ApiInstant,
    val kind: BlockKind = BlockKind.UNAVAILABLE,
    val title: String? = null,
)

/** Weekly working hours in the trainer's local [AvailabilityDto.timeZone]. Times are `HH:mm`, dates `YYYY-MM-DD`. */
@Serializable
data class AvailabilityRuleDto(
    val weekday: Int,
    val startTime: String,
    val endTime: String,
    val gymId: String? = null,
    val validFrom: String? = null,
    val validTo: String? = null,
)

@Serializable
data class AvailabilityDto(val timeZone: String, val rules: List<AvailabilityRuleDto>)

// ---------------------------------------------------------------- cancellation policy

@Serializable
enum class CashFeeMode { ADD_TO_NEXT_SESSION, DEBT_ONLY }

@Serializable
enum class PackageFeeMode { USE_CREDIT, CHARGE_MONEY }

@Serializable
data class PolicyTierDto(val hoursBefore: Int, val feePercent: Int)

@Serializable
data class PolicyDto(
    val versionId: String,
    val trainerId: String,
    val version: Int,
    val createdAt: ApiInstant,
    val tiers: List<PolicyTierDto>,
    val noShowPercent: Int,
    val graceMinutes: Int,
    val freeLateCancellationsPerMonth: Int,
    val lateRescheduleIsCancel: Boolean,
    val cashFeeMode: CashFeeMode,
    val packageFeeMode: PackageFeeMode,
    val clientNote: String? = null,
)

/** Saving creates a new version; CONFLICT when [basedOnVersionId] is no longer the current one. */
@Serializable
data class SavePolicyRequest(
    val basedOnVersionId: String? = null,
    val tiers: List<PolicyTierDto>,
    val noShowPercent: Int,
    val graceMinutes: Int,
    val freeLateCancellationsPerMonth: Int,
    val lateRescheduleIsCancel: Boolean = true,
    val cashFeeMode: CashFeeMode = CashFeeMode.ADD_TO_NEXT_SESSION,
    val packageFeeMode: PackageFeeMode = PackageFeeMode.USE_CREDIT,
    val clientNote: String? = null,
)

@Serializable
data class PolicyEditorDto(
    val current: PolicyDto? = null,
    /** The price the editor uses for its examples: the trainer's first single-session offer. */
    val sessionPrice: Money? = null,
    /** Policy warnings for [current] (PolicyWarning names); never blocking. */
    val warnings: List<String> = emptyList(),
)

@Serializable
enum class ClientPolicyMode { EXEMPT, OTHER_POLICY }

@Serializable
data class ClientPolicyOverrideDto(val mode: ClientPolicyMode, val policyVersionId: String? = null)

// ---------------------------------------------------------------- clients

@Serializable
data class ClientDetailDto(
    val client: ClientSummaryDto,
    val startedAt: ApiInstant? = null,
    val grants: Set<AccessScope>,
    val activePlan: AssignedPlanDto? = null,
    val upcomingSessions: List<SessionDto>,
    val policyOverride: ClientPolicyOverrideDto? = null,
)

@Serializable
enum class AccessScope { HEALTH_QUESTIONNAIRE, MEASUREMENTS, WORKOUT_HISTORY, TECHNIQUE_VIDEOS }

@Serializable
data class NoteDto(val id: String, val body: String, val createdAt: ApiInstant, val updatedAt: ApiInstant)

@Serializable
data class SaveNoteRequest(val body: String)

/**
 * Private information the client enters about themself; the trainer reads it with the client's
 * HEALTH_QUESTIONNAIRE grant (decision: client-entered, see docs/features/trainer-backend.md). Dates `YYYY-MM-DD`.
 */
@Serializable
data class ClientInformationDto(
    val birthDate: String? = null,
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val trainingGoal: String = "",
    val injuries: String = "",
    val movementLimitations: String = "",
    val updatedAt: ApiInstant? = null,
) {
    override fun toString() = "ClientInformationDto(redacted)"
}

@Serializable
data class InviteDto(val code: String, val expiresAt: ApiInstant, val maxUses: Int, val usedCount: Int)

@Serializable
data class CreateInviteRequest(val validDays: Int = 7, val maxUses: Int = 1)

// ---------------------------------------------------------------- profile and price list

@Serializable
enum class OfferKind { SINGLE_SESSION, PACKAGE }

@Serializable
data class OfferDto(
    val id: String,
    val kind: OfferKind,
    val name: String,
    val durationMinutes: Int,
    val price: Money,
    val sessionsPerPeriod: Int? = null,
    val isPublic: Boolean,
)

@Serializable
data class SaveOfferRequest(
    val kind: OfferKind,
    val name: String,
    val durationMinutes: Int,
    val price: Money,
    val sessionsPerPeriod: Int? = null,
    val isPublic: Boolean = true,
)

@Serializable
data class TrainerProfileDto(
    val userId: String,
    val displayName: String,
    val headline: String,
    val bio: String? = null,
    val specialties: List<String>,
    val isPublic: Boolean,
    val sessionBufferMinutes: Int,
    val defaultCurrency: String,
    val gymIds: List<String>,
    /** True when an online payout account is connected; the account id itself is never sent. */
    val payoutAccountConnected: Boolean,
)

@Serializable
data class SaveTrainerProfileRequest(
    val displayName: String,
    val headline: String,
    val bio: String? = null,
    val specialties: List<String> = emptyList(),
    val isPublic: Boolean,
    val sessionBufferMinutes: Int,
    val gymIds: List<String> = emptyList(),
)

// ---------------------------------------------------------------- home and finance

@Serializable
data class TrainerHomeDto(
    val todaySessions: List<SessionDto>,
    val nextSession: SessionDto? = null,
    val clients: List<ClientSummaryDto>,
    val openRequests: Int,
    val unreadMessages: Int,
    /** Technique videos sent in chat that the trainer has not read yet. */
    val pendingVideos: Int,
    val overdueClients: Int,
    val weekSessionsDone: Int,
    val weekSessionsPlanned: Int,
    val serverNow: ApiInstant,
    /** The trainer's display name for the greeting. */
    val trainerName: String? = null,
)

@Serializable
enum class FinancePeriod { WEEK, MONTH, YEAR }

@Serializable
enum class PaymentMethod { CASH, CARD, BLIK, PRZELEWY24, TEST_MODE }

@Serializable
enum class PaymentStatus { PENDING, SUCCEEDED, FAILED }

@Serializable
data class PaymentDto(
    val id: String,
    val clientId: String,
    val clientName: String,
    val method: PaymentMethod,
    val status: PaymentStatus,
    val amount: Money,
    val createdAt: ApiInstant,
    val settledAt: ApiInstant? = null,
)

/** One revenue bucket of the chart: a day (week view), a day (month view) or a month (year view). */
@Serializable
data class RevenueBucketDto(val start: ApiInstant, val collected: Money)

/**
 * Figures from the billing ledger for [from]–[to] in the trainer's time zone (architecture §8). Charges, collected
 * money and outstanding debt are different values.
 */
@Serializable
data class FinanceSummaryDto(
    val period: FinancePeriod,
    val from: ApiInstant,
    val to: ApiInstant,
    val sessionsDone: Int,
    val sessionsValue: Money,
    val cancellationFees: Money,
    val cancellationFeeCount: Int,
    val collected: Money,
    val previousCollected: Money,
    val outstanding: Money,
    val overdueClients: Int,
    val buckets: List<RevenueBucketDto>,
    val payments: List<PaymentDto>,
)

@Serializable
enum class ChargeKind { SESSION, LATE_CANCELLATION_FEE, LATE_RESCHEDULE_FEE, NO_SHOW_FEE, PACKAGE_PERIOD }

@Serializable
enum class Settlement { ONLINE, CASH, PACKAGE_CREDIT }

@Serializable
data class ChargeDto(
    val id: String,
    val kind: ChargeKind,
    val settlement: Settlement,
    val sessionId: String? = null,
    val amount: Money,
    /** Still to pay after waivers and settled payments. */
    val open: Money,
    val dueAt: ApiInstant,
    val createdAt: ApiInstant,
)

/** Cash the trainer received; allocated to the client's oldest open cash charges. */
@Serializable
data class RecordCashPaymentRequest(val clientId: String, val amount: Money, val operationId: String)

@Serializable
data class ClientBalanceDto(val clientId: String, val charges: List<ChargeDto>, val payments: List<PaymentDto>, val outstanding: Money)

// ---------------------------------------------------------------- plans

@Serializable
data class AssignedPlanDto(
    val id: String,
    val clientId: String,
    val planVersionId: String,
    val templateId: String,
    val versionNumber: Int,
    val name: String,
    val status: String,
    val startsOn: String? = null,
    val endsOn: String? = null,
    val assignedAt: ApiInstant,
)

@Serializable
data class AssignPlanRequest(
    val planVersionId: String,
    val startsOn: String? = null,
    val endsOn: String? = null,
    val sessionId: String? = null,
    val operationId: String,
)

// ---------------------------------------------------------------- client progress (read with grants)

@Serializable
data class WorkoutSetDto(
    val id: String,
    val type: String,
    val weightGrams: Int? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val durationSeconds: Int? = null,
    val distanceMeters: Int? = null,
    val completedAt: ApiInstant? = null,
    val techniqueMediaId: String? = null,
)

@Serializable
data class WorkoutExerciseDto(val id: String, val exerciseId: String, val exerciseName: String, val sets: List<WorkoutSetDto>)

@Serializable
data class WorkoutLogDto(
    val id: String,
    val name: String,
    val state: String,
    val startedAt: ApiInstant,
    val finishedAt: ApiInstant? = null,
    val sessionId: String? = null,
    val exercises: List<WorkoutExerciseDto>,
)

@Serializable
data class PersonalRecordDto(val exerciseId: String, val exerciseName: String, val weightGrams: Int, val reps: Int, val completedAt: ApiInstant)

@Serializable
data class MeasurementDto(val id: String, val kind: String, val valueThousandths: Long, val measuredAt: ApiInstant)

// ---------------------------------------------------------------- chat

@Serializable
data class ConversationDto(
    val clientId: String,
    val conversationId: String,
    val clientName: String,
    val lastMessage: MessageDto? = null,
    val unread: Int,
)

@Serializable
data class MessageDto(
    val id: String,
    val seq: Long,
    val senderId: String,
    val mine: Boolean,
    val body: String,
    val createdAt: ApiInstant,
    val workoutLogId: String? = null,
    val setLogId: String? = null,
    val attachmentMediaIds: List<String> = emptyList(),
)

/** [id] is generated by the sender, so a resend is the same message. */
@Serializable
data class SendMessageRequest(
    val id: String,
    val body: String,
    val workoutLogId: String? = null,
    val setLogId: String? = null,
    val attachmentMediaIds: List<String> = emptyList(),
)

@Serializable
data class ThreadDto(val conversationId: String, val clientId: String, val messages: List<MessageDto>, val lastReadSeq: Long)

@Serializable
data class MarkReadRequest(val seq: Long)

// ---------------------------------------------------------------- media

@Serializable
data class UploadSessionDto(val mediaId: String, val uploadUrl: String, val expiresAt: ApiInstant)
