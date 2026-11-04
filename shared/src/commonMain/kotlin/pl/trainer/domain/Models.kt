package pl.trainer.domain

import com.trainingapp.contract.money.Money
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * Server-reported session states. [CancelledByClient] and [Expired] (architecture 7.1) were added for the client
 * views; every `when` over this enum must handle them.
 */
enum class SessionStatus { Requested, Confirmed, Completed, Rejected, CancelledByTrainer, NoShow, CancelledByClient, Expired }
enum class RequestKind { Booking, Reschedule }

/** [id] is the trainer–client relationship ID in the active trainer's scoped repository. */
data class Client(
    val id: String,
    val name: String,
    val initials: String,
    val active: Boolean = true,
    val hasOverduePayment: Boolean = false,
)

/** Private relationship-scoped information; kept outside client-list responses and fixtures. */
data class ClientInformation(
    val birthDate: LocalDate? = null,
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val trainingGoal: String = "",
    val injuries: String = "",
    val movementLimitations: String = "",
) {
    fun ageOn(date: LocalDate): Int? = birthDate?.let { birth ->
        date.year - birth.year - if (date.month.ordinal < birth.month.ordinal ||
            (date.month.ordinal == birth.month.ordinal && date.day < birth.day)) 1 else 0
    }

    fun isValidAsOf(date: LocalDate): Boolean = (birthDate == null || birthDate <= date) &&
        (weightKg == null || weightKg.isFinite() && weightKg > 0) &&
        (heightCm == null || heightCm.isFinite() && heightCm > 0)

    override fun toString() = "ClientInformation(redacted)"
}

enum class ProposalAuthor { Client, Trainer }
/** [requestedAt] is the server's receipt time of the proposal; a late-reschedule fee is calculated from it. */
data class RescheduleProposal(
    val start: Instant,
    val end: Instant,
    val author: ProposalAuthor = ProposalAuthor.Client,
    val requestedAt: Instant? = null,
)

data class Session(
    val id: String,
    val clientId: String,
    val start: Instant,
    val end: Instant,
    val timeZone: String = "Europe/Warsaw",
    val location: String,
    val status: SessionStatus,
    val revision: Int = 1,
    val proposal: RescheduleProposal? = null,
    /** The assigned plan for this session, if any. Display only. */
    val planTitle: String? = null,
    /** How the session is paid, as the server reports it. Read-only here; choosing a method belongs to finance (D3). */
    val payment: SessionPayment? = null,
    /** The cancellation policy version the client accepted when booking. Quotes and the grace period use it. */
    val policyVersionId: String? = null,
    /** The session price as the server reports it. Fees are a percentage of it, in minor units. */
    val price: Money? = null,
    /** The trainer the session belongs to; null means the repository's own trainer. */
    val trainerId: String? = null,
    /** The server's receipt time of a booking request. */
    val requestedAt: Instant? = null,
)

enum class PaymentKind { Package, Online, Cash }

/** [packageUsed] of [packageTotal] when paid from a package ("Pakiet: 6 z 8"). */
data class SessionPayment(val kind: PaymentKind, val packageUsed: Int? = null, val packageTotal: Int? = null)

/**
 * What the server says about attendance for one session: computed with the server's clock and the grace period of
 * the trainer's cancellation-policy version that applies to the session (D2), never with the device clock.
 */
data class CheckInInfo(
    val session: Session,
    val client: Client,
    /** The session has started, by server time. Before that only "Obecny" is possible. */
    val started: Boolean,
    /** When "Nieobecny" unlocks: the start plus the policy's grace period. */
    val noShowAvailableAt: Instant,
    val noShowAllowed: Boolean,
    val graceMinutes: Int,
) {
    /** Attendance can be recorded only for a confirmed session. */
    val canCheckIn: Boolean get() = session.status == SessionStatus.Confirmed
}

data class AvailabilityBlock(val id: String, val start: Instant, val end: Instant)

data class TrainerDashboard(
    val clients: List<Client>,
    val sessions: List<Session>,
    val blocks: List<AvailabilityBlock>,
    val notes: Map<String, String> = emptyMap(),
)

enum class DemoScenario { Normal, Loading, Empty, Error, Offline, Denied }
/** [QuoteExpired]: a fee quote is no longer valid; a fresh quote is needed before confirming. */
enum class Failure { Load, Offline, Denied, Conflict, InvalidInput, InvalidClientInfo, Save, QuoteExpired }
class TrainerException(val failure: Failure) : Exception()

/** Integration boundary: production implementations must enforce role/relationship and revisions. */
interface TrainerRepository {
    suspend fun load(): TrainerDashboard
    suspend fun acceptRequest(id: String, revision: Int): TrainerDashboard
    suspend fun rejectRequest(id: String, revision: Int): TrainerDashboard
    suspend fun proposeTime(id: String, revision: Int, proposal: RescheduleProposal): TrainerDashboard
    suspend fun cancelSession(id: String, revision: Int): TrainerDashboard
    suspend fun completeSession(id: String, revision: Int): TrainerDashboard
    /** Attendance status for the check-in screen. Throws [TrainerException] with [Failure.Conflict] for an unknown session. */
    suspend fun checkIn(sessionId: String): CheckInInfo
    /** Records a no-show. Refused ([Failure.Conflict]) before the grace period has passed or on a stale revision. Any fee is the server's decision. */
    suspend fun markNoShow(id: String, revision: Int): TrainerDashboard
    suspend fun createSession(clientId: String, start: Instant, end: Instant, location: String): TrainerDashboard
    suspend fun addBlock(start: Instant, end: Instant): TrainerDashboard
    suspend fun removeBlock(id: String): TrainerDashboard
    suspend fun saveNote(clientId: String, note: String): TrainerDashboard
    suspend fun loadClientInformation(clientId: String): ClientInformation
    suspend fun saveClientInformation(clientId: String, information: ClientInformation): ClientInformation

    /** B4: what the trainer needs to answer a booking or reschedule request. */
    suspend fun requestReview(id: String): RequestReview = throw TrainerException(Failure.Load)

    /**
     * Accepts a request. For a late reschedule the server charges the fee of the client's policy version unless
     * [waiver] is given (full waivers only in this phase, D8).
     */
    suspend fun acceptRequest(id: String, revision: Int, waiver: FeeWaiver?): TrainerDashboard = acceptRequest(id, revision)
}

/** A full fee waiver with an optional reason the client can see. */
data class FeeWaiver(val reason: String? = null)

/**
 * The trainer's view of one open request. [requestedStart]–[requestedEnd] is the asked-for time; for a reschedule
 * the session's own start and end stay the confirmed time until acceptance succeeds. [lateFee] is the fee the
 * client's policy version proposes for a late reschedule, null when there is none.
 */
data class RequestReview(
    val session: Session,
    val client: Client,
    val kind: RequestKind,
    val requestedAt: Instant?,
    val requestedStart: Instant,
    val requestedEnd: Instant,
    val slotAvailable: Boolean,
    val policy: com.trainingapp.contract.policy.CancellationPolicy?,
    val lateFee: com.trainingapp.contract.policy.FeeQuote?,
)

fun TrainerDashboard.client(id: String): Client = clients.first { it.id == id }

/** A booking or reschedule request the trainer still has to answer (B4 "Szczegóły prośby"). */
fun Session.isOpenRequest() = status == SessionStatus.Requested || proposal?.author == ProposalAuthor.Client

/** Cancelled, rejected or expired: hidden from the trainer agenda. */
fun Session.isClosedWithoutTraining() = status == SessionStatus.Rejected || status == SessionStatus.CancelledByTrainer ||
    status == SessionStatus.CancelledByClient || status == SessionStatus.Expired
fun Session.isActive() = status == SessionStatus.Confirmed
fun Session.needsAttention() = status == SessionStatus.Requested || proposal != null
/** Agenda placement only. The original start/end still reserve the confirmed slot. */
val Session.calendarStart get() = proposal?.start ?: start
val Session.calendarEnd get() = proposal?.end ?: end
fun String.searchKey(): String = lowercase().map { char ->
    when (char) {
        'ą' -> 'a'; 'ć' -> 'c'; 'ę' -> 'e'; 'ł' -> 'l'; 'ń' -> 'n'
        'ó' -> 'o'; 'ś' -> 's'; 'ź', 'ż' -> 'z'; else -> char
    }
}.joinToString("")

/** This is a fixed preview date, never a clock used for eligibility or fees. */
val DemoDate = LocalDate(2026, 10, 28)
