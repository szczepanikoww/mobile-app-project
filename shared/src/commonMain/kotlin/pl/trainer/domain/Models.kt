package pl.trainer.domain

import kotlin.time.Instant
import kotlinx.datetime.LocalDate

enum class SessionStatus { Requested, Confirmed, Completed, Rejected, CancelledByTrainer, NoShow }
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
data class RescheduleProposal(val start: Instant, val end: Instant, val author: ProposalAuthor = ProposalAuthor.Client)

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
)

data class AvailabilityBlock(val id: String, val start: Instant, val end: Instant)

data class TrainerDashboard(
    val clients: List<Client>,
    val sessions: List<Session>,
    val blocks: List<AvailabilityBlock>,
    val notes: Map<String, String> = emptyMap(),
)

enum class DemoScenario { Normal, Loading, Empty, Error, Offline, Denied }
enum class Failure { Load, Offline, Denied, Conflict, InvalidInput, InvalidClientInfo, Save }
class TrainerException(val failure: Failure) : Exception()

/** Integration boundary: production implementations must enforce role/relationship and revisions. */
interface TrainerRepository {
    suspend fun load(): TrainerDashboard
    suspend fun acceptRequest(id: String, revision: Int): TrainerDashboard
    suspend fun rejectRequest(id: String, revision: Int): TrainerDashboard
    suspend fun proposeTime(id: String, revision: Int, proposal: RescheduleProposal): TrainerDashboard
    suspend fun cancelSession(id: String, revision: Int): TrainerDashboard
    suspend fun completeSession(id: String, revision: Int): TrainerDashboard
    suspend fun createSession(clientId: String, start: Instant, end: Instant, location: String): TrainerDashboard
    suspend fun addBlock(start: Instant, end: Instant): TrainerDashboard
    suspend fun removeBlock(id: String): TrainerDashboard
    suspend fun saveNote(clientId: String, note: String): TrainerDashboard
    suspend fun loadClientInformation(clientId: String): ClientInformation
    suspend fun saveClientInformation(clientId: String, information: ClientInformation): ClientInformation
}

fun TrainerDashboard.client(id: String): Client = clients.first { it.id == id }
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
