package pl.trainer.data

import kotlinx.coroutines.delay
import kotlin.time.Instant
import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.FeeCalculator
import kotlin.time.Duration.Companion.minutes
import pl.trainer.domain.*

/**
 * Synthetic, in-memory demonstration only. No bookings or private records are sent anywhere.
 *
 * The data lives in [store], the demo server shared with the client views (D10): a booking request a client sends
 * appears here, and what the trainer answers appears in the client calendar. Each repository gets its own store
 * unless the app passes the shared one.
 */
class DemoTrainerRepository(
    private val latencyMillis: Long = 350,
    /** Stands in for the server clock; defaults to the store's clock (the fixture date, 28 Oct 2026, 09:20 in Warsaw). */
    serverNow: (() -> Instant)? = null,
    val store: DemoSchedulingStore = DemoSchedulingStore(serverNow ?: { DemoServerNow }),
) : TrainerRepository {
    var scenario = DemoScenario.Normal
    private val serverNow: () -> Instant = serverNow ?: store.serverNow

    /** The trainer's scoped view: own sessions only. */
    private fun DemoSchedulingStore.dashboard() = data.copy(sessions = data.sessions.filter { trainerOf(it) == DemoTrainerId })

    override suspend fun load(): TrainerDashboard {
        delay(latencyMillis)
        when (scenario) {
            DemoScenario.Error -> throw TrainerException(Failure.Load)
            DemoScenario.Denied -> throw TrainerException(Failure.Denied)
            DemoScenario.Empty -> return TrainerDashboard(emptyList(), emptyList(), emptyList())
            else -> return store.transaction(changes = false) { dashboard() }
        }
    }

    private suspend fun change(block: DemoSchedulingStore.() -> Unit): TrainerDashboard {
        delay(latencyMillis)
        when (scenario) {
            DemoScenario.Offline -> throw TrainerException(Failure.Offline)
            DemoScenario.Denied -> throw TrainerException(Failure.Denied)
            DemoScenario.Error -> throw TrainerException(Failure.Save)
            else -> Unit
        }
        return store.transaction { block(); dashboard() }
    }

    private fun DemoSchedulingStore.current(id: String, revision: Int): Session {
        val session = data.sessions.find { it.id == id && trainerOf(it) == DemoTrainerId } ?: throw TrainerException(Failure.Conflict)
        if (session.revision != revision) throw TrainerException(Failure.Conflict)
        return session
    }

    override suspend fun acceptRequest(id: String, revision: Int) = acceptRequest(id, revision, waiver = null)

    override suspend fun acceptRequest(id: String, revision: Int, waiver: FeeWaiver?) = change {
        val session = current(id, revision)
        if (!session.needsAttention()) throw TrainerException(Failure.Conflict)
        val proposal = session.proposal
        if (proposal?.author == ProposalAuthor.Trainer) throw TrainerException(Failure.Conflict)
        val start = proposal?.start ?: session.start
        val end = proposal?.end ?: session.end
        checkTrainerSlot(start, end, id)
        if (proposal != null) chargeLateReschedule(session, proposal, waiver)
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
        checkTrainerSlot(proposal.start, proposal.end, id)
        // Original start/end and confirmed status stay intact until the other side accepts.
        replace(session.copy(proposal = proposal.copy(requestedAt = proposal.requestedAt ?: serverNow()), revision = revision + 1))
    }

    /** A trainer's cancellation never charges the client. */
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

    override suspend fun checkIn(sessionId: String): CheckInInfo {
        delay(latencyMillis)
        when (scenario) {
            DemoScenario.Error -> throw TrainerException(Failure.Load)
            DemoScenario.Denied -> throw TrainerException(Failure.Denied)
            else -> Unit
        }
        return store.transaction(changes = false) {
            val session = data.sessions.find { it.id == sessionId && trainerOf(it) == DemoTrainerId } ?: throw TrainerException(Failure.Conflict)
            val client = data.clients.find { it.id == session.clientId } ?: throw TrainerException(Failure.Denied)
            // The grace period of the policy version the client accepted for this session.
            val grace = policyFor(session).graceMinutes
            val now = serverNow()
            val availableAt = session.start + grace.minutes
            CheckInInfo(session, client, started = now >= session.start, noShowAvailableAt = availableAt,
                noShowAllowed = now >= availableAt, graceMinutes = grace)
        }
    }

    override suspend fun markNoShow(id: String, revision: Int) = change {
        val session = current(id, revision)
        if (!session.isActive()) throw TrainerException(Failure.Conflict)
        val policy = policyFor(session)
        if (serverNow() < session.start + policy.graceMinutes.minutes) throw TrainerException(Failure.Conflict)
        // The server applies the session's policy version; the check-in screen does not show an amount.
        addCharge(session, ChargeReason.NoShow, FeeCalculator.noShow(policy, priceFor(session)), policy.noShowPercent)
        replace(session.copy(status = SessionStatus.NoShow, proposal = null, revision = revision + 1))
    }

    override suspend fun createSession(clientId: String, start: Instant, end: Instant, location: String) = change {
        if (data.clients.none { it.id == clientId && it.active } || location.isBlank()) throw TrainerException(Failure.InvalidInput)
        checkTrainerSlot(start, end)
        data = data.copy(sessions = data.sessions + Session("demo-${nextId++}", clientId, start, end, location = location.trim(),
            status = SessionStatus.Confirmed, policyVersionId = currentPolicy(DemoTrainerId).versionId, price = demoTrainerProfile.price,
            trainerId = DemoTrainerId))
    }

    override suspend fun addBlock(start: Instant, end: Instant) = change {
        checkTrainerSlot(start, end)
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
        return store.transaction(changes = false) {
            if (scenario == DemoScenario.Denied || data.clients.none { it.id == clientId && it.active }) {
                throw TrainerException(Failure.Denied)
            }
            if (scenario == DemoScenario.Error) throw TrainerException(Failure.Load)
            privateClientInformation[clientId] ?: ClientInformation()
        }
    }

    override suspend fun saveClientInformation(clientId: String, information: ClientInformation): ClientInformation {
        change {
            if (data.clients.none { it.id == clientId && it.active }) throw TrainerException(Failure.Denied)
            if (!information.isValidAsOf(DemoDate)) throw TrainerException(Failure.InvalidClientInfo)
            privateClientInformation[clientId] = information
        }
        return information
    }

    override suspend fun requestReview(id: String): RequestReview {
        delay(latencyMillis)
        when (scenario) {
            DemoScenario.Error -> throw TrainerException(Failure.Load)
            DemoScenario.Denied -> throw TrainerException(Failure.Denied)
            else -> Unit
        }
        return store.transaction(changes = false) {
            val session = data.sessions.find { it.id == id && trainerOf(it) == DemoTrainerId } ?: throw TrainerException(Failure.Conflict)
            val client = data.clients.find { it.id == session.clientId } ?: throw TrainerException(Failure.Denied)
            val proposal = session.proposal
            val reschedule = proposal != null
            val start = proposal?.start ?: session.start
            val end = proposal?.end ?: session.end
            val policy = policyFor(session)
            RequestReview(
                session = session,
                client = client,
                kind = if (reschedule) RequestKind.Reschedule else RequestKind.Booking,
                requestedAt = proposal?.requestedAt ?: session.requestedAt,
                requestedStart = start,
                requestedEnd = end,
                slotAvailable = data.sessions.none {
                    it.id != session.id && trainerOf(it) == DemoTrainerId && it.isActive() && start < it.end && end > it.start
                } && data.blocks.none { start < it.end && end > it.start },
                policy = policy,
                lateFee = if (proposal?.author == ProposalAuthor.Client) {
                    FeeCalculator.reschedule(policy, session.start, proposal.requestedAt ?: serverNow(), priceFor(session)).takeIf { it.late }
                } else null,
                serverNow = serverNow(),
            )
        }
    }
}

private fun at(day: Int, hour: Int, minute: Int = 0) = demoAt(10, day, hour, minute)

/** The grace period of the demo trainer's first policy version (D2). Check-in uses each session's own version. */
const val DemoNoShowGraceMinutes = 15

/** The demo server clock: the fixture date at 09:20 in Warsaw. Never the device clock. */
val DemoServerNow: Instant = at(28, 9, 20)

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
        Session("s1", "c1", at(28, 9), at(28, 10), location = "Studio Centrum", status = SessionStatus.Confirmed,
            planTitle = "Push A", payment = SessionPayment(PaymentKind.Package, packageUsed = 6, packageTotal = 8)),
        Session("s2", "c3", at(28, 10, 30), at(28, 11, 30), location = "Studio Centrum", status = SessionStatus.Requested),
        Session("s3", "c4", at(28, 14), at(28, 15), location = "Studio Centrum", status = SessionStatus.Confirmed,
            payment = SessionPayment(PaymentKind.Cash)),
        Session("s4", "c2", at(28, 16), at(28, 17), location = "Studio Centrum", status = SessionStatus.Confirmed,
            proposal = RescheduleProposal(at(29, 11), at(29, 12))),
        Session("s5", "c5", at(29, 9), at(29, 10), location = "Studio Centrum", status = SessionStatus.Confirmed),
        Session("s6", "c1", at(30, 9), at(30, 10), location = "Studio Centrum", status = SessionStatus.Confirmed),
        Session("s7", "c3", at(26, 10), at(26, 11), location = "Studio Centrum", status = SessionStatus.Completed),
        // The client views (Katarzyna "Kasia" Nowak, c1). Placed outside the dates the calendar tests use.
        Session("s13", "c1", at(29, 7), at(29, 8), location = "Studio Centrum", status = SessionStatus.Confirmed,
            planTitle = "Nogi", payment = SessionPayment(PaymentKind.Online)),
        Session("s8", "c1", demoAt(11, 3, 10), demoAt(11, 3, 11), location = "Siłownia Wola", status = SessionStatus.Confirmed,
            planTitle = "Pull B", payment = SessionPayment(PaymentKind.Package, packageUsed = 7, packageTotal = 8),
            proposal = RescheduleProposal(demoAt(11, 4, 10), demoAt(11, 4, 11), ProposalAuthor.Trainer, requestedAt = at(27, 21))),
        Session("s9", "c1", demoAt(11, 5, 18), demoAt(11, 5, 19), location = "Studio Centrum", status = SessionStatus.Requested,
            requestedAt = at(27, 19, 30)),
        Session("s10", "c1", demoAt(11, 2, 7), demoAt(11, 2, 8), location = "Studio Centrum", status = SessionStatus.Rejected,
            requestedAt = at(26, 8)),
        Session("s14", "c1", at(27, 18), at(27, 19), location = "Studio Centrum", status = SessionStatus.Expired,
            requestedAt = at(24, 12)),
        Session("s11", "c1", at(22, 9), at(22, 10), location = "Studio Centrum", status = SessionStatus.Completed,
            planTitle = "Push A", payment = SessionPayment(PaymentKind.Package, packageUsed = 5, packageTotal = 8)),
        Session("s12", "c1", at(23, 9), at(23, 10), location = "Studio Centrum", status = SessionStatus.CancelledByClient,
            payment = SessionPayment(PaymentKind.Online)),
    ).map { it.copy(trainerId = DemoTrainerId, policyVersionId = "pol-demo-trainer-v1", price = Money.pln(120),
        requestedAt = it.requestedAt ?: if (it.status == SessionStatus.Requested) at(28, 9) else null) },
    blocks = listOf(AvailabilityBlock("b1", at(28, 12), at(28, 13))),
)
