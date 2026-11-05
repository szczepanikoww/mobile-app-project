package pl.trainer.domain

import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.CancellationPolicy
import com.trainingapp.contract.policy.FeeQuote
import com.trainingapp.contract.policy.PolicyRules
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/*
 * The client's side of scheduling, the wallet and the trainer's cancellation policy versions
 * (docs/features/client-shell-sessions-wallet.md §6). These are local models behind repository interfaces; the demo
 * implementations live in pl.trainer.data and share one store with the trainer calendar.
 */

/** A trainer as the client sees them. */
data class TrainerRef(val id: String, val name: String, val initials: String)

/** What the client calendar shows for a session. Every state has a label and an icon in the UI. */
enum class ClientSessionState {
    /** A booking request the trainer has not answered ("Czeka na trenera"). */
    AwaitingTrainer,
    Confirmed,
    /** The trainer proposed another time; the original time is still the confirmed one ("Propozycja zmiany"). */
    ProposalFromTrainer,
    /** The client asked for another time; the original time is still the confirmed one. */
    RescheduleRequested,
    Rejected,
    Expired,
    Cancelled,
    Completed,
    NoShow,
}

fun Session.clientState(): ClientSessionState = when {
    proposal?.author == ProposalAuthor.Trainer && status == SessionStatus.Confirmed -> ClientSessionState.ProposalFromTrainer
    proposal?.author == ProposalAuthor.Client && status == SessionStatus.Confirmed -> ClientSessionState.RescheduleRequested
    else -> when (status) {
        SessionStatus.Requested -> ClientSessionState.AwaitingTrainer
        SessionStatus.Confirmed -> ClientSessionState.Confirmed
        SessionStatus.Completed -> ClientSessionState.Completed
        SessionStatus.Rejected -> ClientSessionState.Rejected
        SessionStatus.Expired -> ClientSessionState.Expired
        SessionStatus.CancelledByTrainer, SessionStatus.CancelledByClient -> ClientSessionState.Cancelled
        SessionStatus.NoShow -> ClientSessionState.NoShow
    }
}

/** The client's view of a [Session]: the session itself plus the trainer it is with. */
data class ClientSession(val session: Session, val trainer: TrainerRef) {
    val id: String get() = session.id
    val revision: Int get() = session.revision
    val state: ClientSessionState get() = session.clientState()

    /** The time shown in the agenda: a pending proposal's time, otherwise the session's own. */
    val agendaStart: Instant get() = session.proposal?.start ?: session.start
    val canCancel: Boolean get() = session.status == SessionStatus.Confirmed
    val canReschedule: Boolean get() = session.status == SessionStatus.Confirmed && session.proposal == null
    val canWithdraw: Boolean get() = state == ClientSessionState.AwaitingTrainer || state == ClientSessionState.RescheduleRequested
}

data class ClientSchedule(
    val trainers: List<TrainerRef>,
    val sessions: List<ClientSession>,
    /** The server's time of this response. Screens use it for "today" and "next", never the device clock. */
    val serverNow: Instant,
)

/** A free slot in the trainer's demo availability (D9: Mon–Fri 7:00–20:00, minus sessions and blocks). */
data class AvailableSlot(val start: Instant, val end: Instant)

/** What the booking request sheet needs about a trainer. [policy] is the version the client must accept. */
data class BookingOptions(
    val trainer: TrainerRef,
    val policy: CancellationPolicy,
    val price: Money,
    val places: List<String>,
    val timeZone: String,
)

data class BookingRequest(
    val trainerId: String,
    val start: Instant,
    val end: Instant,
    val place: String,
    /** Must be the trainer's current version: the server refuses an outdated consent with [Failure.Conflict]. */
    val acceptedPolicyVersionId: String,
)

data class RescheduleRequest(val sessionId: String, val revision: Int, val start: Instant, val end: Instant)

/** How a fee is settled, from how the session is paid. */
enum class ChargeKind { Online, Cash, PackageCredit }

fun SessionPayment?.chargeKind(): ChargeKind = when (this?.kind) {
    PaymentKind.Package -> ChargeKind.PackageCredit
    PaymentKind.Cash -> ChargeKind.Cash
    PaymentKind.Online, null -> ChargeKind.Online
}

/**
 * A fresh server quote for cancelling one session revision. It is valid until [expiresAt] (server time) and only for
 * [revision]; confirming with an expired quote or a changed session fails and asks for a new quote.
 */
data class CancellationQuote(
    val quoteId: String,
    val sessionId: String,
    val revision: Int,
    val fee: FeeQuote,
    val policyVersionId: String,
    val freeLateCancellationsLeft: Int,
    val chargeKind: ChargeKind,
    val quotedAt: Instant,
    val expiresAt: Instant,
)

/** The late-reschedule fee the policy proposes; the trainer decides whether to charge it. */
data class RescheduleFeePreview(val fee: FeeQuote, val policyVersionId: String)

interface ClientScheduleRepository {
    /** Increments whenever the server data changes, including changes made in the trainer's views. */
    val updates: StateFlow<Long>
    suspend fun load(): ClientSchedule
    suspend fun bookingOptions(trainerId: String): BookingOptions
    suspend fun availableSlots(trainerId: String, date: LocalDate, excludeSessionId: String? = null): List<AvailableSlot>
    suspend fun requestBooking(request: BookingRequest): ClientSession
    suspend fun withdrawRequest(sessionId: String, revision: Int): ClientSession
    suspend fun previewRescheduleFee(sessionId: String, revision: Int): RescheduleFeePreview?
    suspend fun requestReschedule(request: RescheduleRequest): ClientSession
    suspend fun answerProposal(sessionId: String, revision: Int, accept: Boolean): ClientSession
    suspend fun quoteCancellation(sessionId: String, revision: Int): CancellationQuote
    /** Cancels with a still-valid [quoteId]. The session is cancelled only when this returns. */
    suspend fun cancel(sessionId: String, revision: Int, quoteId: String): ClientSession
    suspend fun policy(versionId: String): CancellationPolicy
}

/* Wallet */

/** A package as the data gives it. No renewal, expiry or rollover logic exists (G13 is unresolved). */
data class SessionPackage(
    val id: String,
    val name: String,
    val trainer: TrainerRef,
    val used: Int,
    val total: Int,
    val validUntil: LocalDate?,
)

enum class ChargeReason { LateCancellation, LateReschedule, NoShow }

enum class ChargeState {
    /** Not paid yet. Online charges can be paid in the app; cash charges are paid to the trainer. */
    Unpaid,
    /** A payment was submitted and waits for the provider's confirmation. */
    PaymentPending,
    Settled,
}

data class Charge(
    val id: String,
    val reason: ChargeReason,
    val sessionId: String?,
    val sessionStart: Instant?,
    val trainer: TrainerRef,
    val amount: Money,
    val kind: ChargeKind,
    val state: ChargeState,
    val createdAt: Instant,
    /** For a package credit: the share of one package entry that was used. */
    val packagePercent: Int? = null,
) {
    val payableInApp: Boolean get() = kind == ChargeKind.Online && state == ChargeState.Unpaid
}

enum class PaymentMethod { Card, Blik, Przelewy24, TestMode }

enum class PaymentState { Pending, Paid, Failed, Refunded }

data class Payment(
    val id: String,
    val chargeId: String?,
    val reason: ChargeReason?,
    val trainer: TrainerRef,
    val amount: Money,
    val method: PaymentMethod,
    val state: PaymentState,
    val at: Instant,
)

data class Wallet(
    val packages: List<SessionPackage>,
    val charges: List<Charge>,
    val payments: List<Payment>,
    val serverNow: Instant,
) {
    /** Charges still to pay, including those whose payment is pending confirmation. */
    val outstanding: List<Charge> get() = charges.filter { it.state != ChargeState.Settled }
}

interface WalletRepository {
    val updates: StateFlow<Long>
    suspend fun load(): Wallet

    /**
     * Submits a payment for an online charge. Returns a [PaymentState.Pending] payment: it becomes paid only when the
     * server receives the provider's confirmation. The same [idempotencyKey] never pays twice.
     */
    suspend fun pay(chargeId: String, method: PaymentMethod, idempotencyKey: String): Payment
}

/* Cancellation policy versions (trainer) */

interface PolicyRepository {
    val updates: StateFlow<Long>
    suspend fun current(trainerId: String): CancellationPolicy
    suspend fun version(versionId: String): CancellationPolicy

    /** The trainer's demo session rate, used to show amounts next to percentages. */
    suspend fun sessionPrice(trainerId: String): Money

    /**
     * Saves [rules] as a new version. Existing sessions keep their versions. Fails with [Failure.Conflict] when
     * [basedOnVersionId] is no longer the current version, and with [Failure.InvalidInput] for invalid rules.
     */
    suspend fun save(trainerId: String, rules: PolicyRules, basedOnVersionId: String): CancellationPolicy
}
