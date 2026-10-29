package pl.trainer.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import pl.trainer.domain.*

/** Synthetic, in-memory demonstration only. No bookings or private records are sent anywhere. */
class DemoTrainerRepository(private val latencyMillis: Long = 350) : TrainerRepository {
    var scenario = DemoScenario.Normal
    private val mutex = Mutex()
    private var nextId = 10
    private var data = demoDashboard()
    private val privateClientInformation = mutableMapOf<String, ClientInformation>()

    override suspend fun load(): TrainerDashboard {
        delay(latencyMillis)
        when (scenario) {
            DemoScenario.Error -> throw TrainerException(Failure.Load)
            DemoScenario.Denied -> throw TrainerException(Failure.Denied)
            DemoScenario.Empty -> return TrainerDashboard(emptyList(), emptyList(), emptyList())
            else -> return data
        }
    }

    private suspend fun change(block: () -> Unit): TrainerDashboard {
        delay(latencyMillis)
        return mutex.withLock {
            when (scenario) {
                DemoScenario.Offline -> throw TrainerException(Failure.Offline)
                DemoScenario.Denied -> throw TrainerException(Failure.Denied)
                DemoScenario.Error -> throw TrainerException(Failure.Save)
                else -> Unit
            }
            block()
            data
        }
    }

    private fun current(id: String, revision: Int): Session {
        val session = data.sessions.find { it.id == id } ?: throw TrainerException(Failure.Conflict)
        if (session.revision != revision) throw TrainerException(Failure.Conflict)
        return session
    }

    private fun replace(session: Session) {
        data = data.copy(sessions = data.sessions.map { if (it.id == session.id) session else it })
    }

    private fun checkSlot(start: Instant, end: Instant, exceptId: String? = null) {
        if (start >= end) throw TrainerException(Failure.InvalidInput)
        val overlap = data.sessions.any {
            it.id != exceptId && it.isActive() && start < it.end && end > it.start
        } || data.blocks.any { start < it.end && end > it.start }
        if (overlap) throw TrainerException(Failure.Conflict)
    }

    override suspend fun acceptRequest(id: String, revision: Int) = change {
        val session = current(id, revision)
        if (!session.needsAttention()) throw TrainerException(Failure.Conflict)
        if (session.proposal?.author == ProposalAuthor.Trainer) throw TrainerException(Failure.Conflict)
        val start = session.proposal?.start ?: session.start
        val end = session.proposal?.end ?: session.end
        checkSlot(start, end, id)
        replace(session.copy(start = start, end = end, status = SessionStatus.Confirmed, proposal = null, revision = revision + 1))
    }

    override suspend fun rejectRequest(id: String, revision: Int) = change {
        val session = current(id, revision)
        if (!session.needsAttention()) throw TrainerException(Failure.Conflict)
        replace(session.copy(
            status = if (session.proposal != null) session.status else SessionStatus.Rejected,
            proposal = null,
            revision = revision + 1,
        ))
    }

    override suspend fun proposeTime(id: String, revision: Int, proposal: RescheduleProposal) = change {
        val session = current(id, revision)
        if (!session.isActive()) throw TrainerException(Failure.Conflict)
        checkSlot(proposal.start, proposal.end, id)
        // Original start/end and confirmed status stay intact until the other side accepts.
        replace(session.copy(proposal = proposal, revision = revision + 1))
    }

    override suspend fun cancelSession(id: String, revision: Int) = change {
        val session = current(id, revision)
        if (!session.isActive()) throw TrainerException(Failure.Conflict)
        replace(session.copy(status = SessionStatus.CancelledByTrainer, proposal = null, revision = revision + 1))
    }

    override suspend fun completeSession(id: String, revision: Int) = change {
        val session = current(id, revision)
        if (!session.isActive()) throw TrainerException(Failure.Conflict)
        replace(session.copy(status = SessionStatus.Completed, proposal = null, revision = revision + 1))
    }

    override suspend fun createSession(clientId: String, start: Instant, end: Instant, location: String) = change {
        if (data.clients.none { it.id == clientId && it.active } || location.isBlank()) throw TrainerException(Failure.InvalidInput)
        checkSlot(start, end)
        data = data.copy(sessions = data.sessions + Session("demo-${nextId++}", clientId, start, end, location = location.trim(), status = SessionStatus.Confirmed))
    }

    override suspend fun addBlock(start: Instant, end: Instant) = change {
        checkSlot(start, end)
        data = data.copy(blocks = data.blocks + AvailabilityBlock("block-${nextId++}", start, end))
    }

    override suspend fun removeBlock(id: String) = change {
        data = data.copy(blocks = data.blocks.filterNot { it.id == id })
    }

    override suspend fun saveNote(clientId: String, note: String) = change {
        if (data.clients.none { it.id == clientId && it.active }) throw TrainerException(Failure.Denied)
        data = data.copy(notes = data.notes + (clientId to note))
    }

    override suspend fun loadClientInformation(clientId: String): ClientInformation {
        delay(latencyMillis)
        if (scenario == DemoScenario.Denied || data.clients.none { it.id == clientId && it.active }) {
            throw TrainerException(Failure.Denied)
        }
        if (scenario == DemoScenario.Error) throw TrainerException(Failure.Load)
        return privateClientInformation[clientId] ?: ClientInformation()
    }

    override suspend fun saveClientInformation(clientId: String, information: ClientInformation): ClientInformation {
        change {
            if (data.clients.none { it.id == clientId && it.active }) throw TrainerException(Failure.Denied)
            if (!information.isValidAsOf(DemoDate)) throw TrainerException(Failure.InvalidClientInfo)
            privateClientInformation[clientId] = information
        }
        return information
    }
}

private fun at(day: Int, hour: Int, minute: Int = 0) =
    LocalDateTime(2026, 10, day, hour, minute).toInstant(TimeZone.of("Europe/Warsaw"))

fun demoDashboard() = TrainerDashboard(
    clients = listOf(
        Client("c1", "Katarzyna Nowak", "KN"),
        Client("c2", "Marek Wiśniewski", "MW", hasOverduePayment = true),
        Client("c3", "Anna Zielińska", "AZ"),
        Client("c4", "Tomasz Wójcik", "TW"),
        Client("c5", "Aleksandra Dąbrowska-Kowalczyk", "AD", hasOverduePayment = true),
        Client("c6", "Piotr Lewandowski", "PL", active = false),
    ),
    sessions = listOf(
        Session("s1", "c1", at(28, 9), at(28, 10), location = "Studio Centrum", status = SessionStatus.Confirmed),
        Session("s2", "c3", at(28, 10, 30), at(28, 11, 30), location = "Studio Centrum", status = SessionStatus.Requested),
        Session("s3", "c4", at(28, 14), at(28, 15), location = "Studio Centrum", status = SessionStatus.Confirmed),
        Session("s4", "c2", at(28, 16), at(28, 17), location = "Studio Centrum", status = SessionStatus.Confirmed,
            proposal = RescheduleProposal(at(29, 11), at(29, 12))),
        Session("s5", "c5", at(29, 9), at(29, 10), location = "Studio Centrum", status = SessionStatus.Confirmed),
        Session("s6", "c1", at(30, 9), at(30, 10), location = "Studio Centrum", status = SessionStatus.Confirmed),
        Session("s7", "c3", at(26, 10), at(26, 11), location = "Studio Centrum", status = SessionStatus.Completed),
    ),
    blocks = listOf(AvailabilityBlock("b1", at(28, 12), at(28, 13))),
)
