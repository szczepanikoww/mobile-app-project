package pl.trainer.data

import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.CancellationPolicy
import com.trainingapp.contract.policy.FeeCalculator
import com.trainingapp.contract.policy.PolicyTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import pl.trainer.domain.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeSource

/** The demo trainer whose calendar the trainer views show. Matches the guest demo account "demo-trainer". */
const val DemoTrainerId = "demo-trainer"

/** A trainer the demo server knows: identity, rate and places for booking requests. */
data class DemoTrainerProfile(
    val ref: TrainerRef,
    val price: Money,
    val places: List<String>,
    val timeZone: String = "Europe/Warsaw",
)

/**
 * A public trainer from the guest catalogue that the demo server does not know yet. The app maps the guest
 * catalogue to this, so a booking request from a trainer's public profile reaches the same store.
 */
data class CatalogTrainer(
    val id: String,
    val name: String,
    val initials: String,
    val price: Money?,
    val places: List<String>,
    /** Cancelling at least this many hours before is free; 50% below it (the catalogue's public policy text). */
    val freeCancellationHours: Int?,
)

/** Who is signed in as a client. */
data class ClientIdentity(val userId: String, val displayName: String)

/** A demo server clock that starts at the fixture time and moves forward with real elapsed time. */
class AdvancingDemoClock(private val start: Instant = DemoServerNow) {
    private val mark = TimeSource.Monotonic.markNow()
    fun now(): Instant = start + mark.elapsedNow()
}

/**
 * The one in-memory demo "server" for sessions and requests (decision D10), cancellation policy versions and the
 * client's wallet. The trainer's [DemoTrainerRepository] and the client's repositories read and write it, so a
 * client's booking request appears in the trainer calendar and the trainer's answer appears in the client calendar.
 *
 * Every time comes from [serverNow], never the device clock. All data is synthetic and resets on restart.
 */
class DemoSchedulingStore(
    val serverNow: () -> Instant = { DemoServerNow },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** How long the demo payment provider takes to confirm a payment. */
    private val paymentConfirmationMillis: Long = 2_500,
) {
    private val mutex = Mutex()
    private val version = MutableStateFlow(0L)

    /** Increments after every change, by either role. */
    val updates: StateFlow<Long> = version.asStateFlow()

    internal val zone = TimeZone.of("Europe/Warsaw")
    internal var nextId = 100

    /** All sessions of all trainers; [TrainerDashboard.clients], blocks and notes belong to [DemoTrainerId]. */
    internal var data: TrainerDashboard = demoDashboard()
    internal val privateClientInformation = mutableMapOf<String, ClientInformation>()
    internal val trainers = mutableMapOf(DemoTrainerId to demoTrainerProfile)

    /** The demo trainer's public catalogue profile is the same person as the signed-in demo trainer. */
    private val trainerAliases = mapOf("tr-michal" to DemoTrainerId)

    /** Signed-in user → the client id used in sessions. A real server scopes ids per relationship. */
    internal val clientIdsByUser = mutableMapOf("demo-client" to "c1")
    internal val relationships = mutableSetOf<Pair<String, String>>().apply {
        demoDashboard().clients.filter { it.active }.forEach { add(it.id to DemoTrainerId) }
    }
    internal val policies = mutableListOf(demoPolicyV1)
    internal val packages = mutableMapOf<String, MutableList<SessionPackage>>(
        "c1" to mutableListOf(SessionPackage("pk1", "Pakiet 8 sesji", demoTrainerProfile.ref, used = 6, total = 8, validUntil = LocalDate(2026, 11, 15))),
    )
    internal val charges = mutableMapOf<String, MutableList<Charge>>("c1" to demoCharges().toMutableList())
    internal val payments = mutableMapOf<String, MutableList<Payment>>("c1" to demoPayments().toMutableList())
    internal val quotes = mutableMapOf<String, CancellationQuote>()
    internal val paymentsByKey = mutableMapOf<String, Payment>()

    /** Free late cancellations used, keyed by client, trainer and the month of the server's receipt time. */
    internal val freeLateUsed = mutableMapOf(Triple("c1", DemoTrainerId, "2026-10") to 1)

    /** Runs [block] under the store lock; increments [updates] when [changes] is true and the block succeeds. */
    internal suspend fun <T> transaction(changes: Boolean = true, block: DemoSchedulingStore.() -> T): T {
        val result = mutex.withLock {
            expireRequests()
            block()
        }
        if (changes) version.value++
        return result
    }

    internal fun canonicalTrainerId(id: String) = trainerAliases[id] ?: id

    /** The client id the server uses for [userId] in sessions and workout data, if the user is a known client. */
    fun clientIdOf(userId: String): String? = clientIdsByUser[userId]

    internal fun trainerOf(session: Session): String = session.trainerId ?: DemoTrainerId

    internal fun trainerRef(id: String): TrainerRef = trainers[id]?.ref ?: TrainerRef(id, id, id.take(2).uppercase())

    internal fun replace(session: Session) {
        data = data.copy(sessions = data.sessions.map { if (it.id == session.id) session else it })
    }

    internal fun policyFor(session: Session): CancellationPolicy =
        session.policyVersionId?.let { id -> policies.find { it.versionId == id } } ?: currentPolicy(trainerOf(session))

    internal fun currentPolicy(trainerId: String): CancellationPolicy =
        policies.filter { it.trainerId == trainerId }.maxByOrNull { it.version } ?: throw TrainerException(Failure.Load)

    internal fun priceFor(session: Session): Money = session.price ?: trainers[trainerOf(session)]?.price ?: Money.pln(120)

    /** A booking request the trainer did not answer before its start expires. */
    private fun expireRequests() {
        val now = serverNow()
        if (data.sessions.none { it.status == SessionStatus.Requested && it.start <= now }) return
        data = data.copy(sessions = data.sessions.map {
            if (it.status == SessionStatus.Requested && it.start <= now) it.copy(status = SessionStatus.Expired, revision = it.revision + 1) else it
        })
    }

    /** Overlap with a confirmed session or a pending booking request of the same trainer, or with a block. */
    internal fun slotTaken(trainerId: String, start: Instant, end: Instant, exceptId: String? = null): Boolean =
        data.sessions.any {
            it.id != exceptId && trainerOf(it) == trainerId &&
                (it.status == SessionStatus.Confirmed || it.status == SessionStatus.Requested) && start < it.end && end > it.start
        } || (trainerId == DemoTrainerId && data.blocks.any { start < it.end && end > it.start })

    /** D9: Mon–Fri, hourly slots between 7:00 and 20:00 in the trainer's zone, in the future, not taken. */
    internal fun freeSlots(trainerId: String, date: LocalDate, exceptId: String? = null): List<AvailableSlot> {
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return emptyList()
        val now = serverNow()
        return (7 until 20).map { hour ->
            val start = LocalDateTime(date, LocalTime(hour, 0)).toInstant(zone)
            AvailableSlot(start, start + 1.hours)
        }.filter { it.start > now && !slotTaken(trainerId, it.start, it.end, exceptId) }
    }

    internal fun isWorkingSlot(start: Instant, end: Instant): Boolean {
        val from = start.toLocalDateTime(zone)
        val until = end.toLocalDateTime(zone)
        return from.date == until.date && from.date.dayOfWeek != DayOfWeek.SATURDAY && from.date.dayOfWeek != DayOfWeek.SUNDAY &&
            from.time >= LocalTime(7, 0) && until.time <= LocalTime(20, 0) && start < end
    }

    internal fun monthKey(instant: Instant): String {
        val date = instant.toLocalDateTime(zone).date
        return "${date.year}-${date.month.ordinal.plus(1).toString().padStart(2, '0')}"
    }

    internal fun freeLateLeft(clientId: String, trainerId: String, policy: CancellationPolicy, at: Instant): Int =
        (policy.freeLateCancellationsPerMonth - (freeLateUsed[Triple(clientId, trainerId, monthKey(at))] ?: 0)).coerceAtLeast(0)

    internal fun useFreeLate(clientId: String, trainerId: String, at: Instant) {
        val key = Triple(clientId, trainerId, monthKey(at))
        freeLateUsed[key] = (freeLateUsed[key] ?: 0) + 1
    }

    /**
     * Records a fee for [session] according to how it is paid: online → an unpaid charge payable in the app, cash →
     * an unpaid charge settled with the trainer, package → settled immediately as a share of one package entry.
     */
    internal fun addCharge(session: Session, reason: ChargeReason, amount: Money, percent: Int) {
        if (amount.isZero) return
        val kind = session.payment.chargeKind()
        val charge = Charge(
            id = "ch-${nextId++}",
            reason = reason,
            sessionId = session.id,
            sessionStart = session.start,
            trainer = trainerRef(trainerOf(session)),
            amount = amount,
            kind = kind,
            state = if (kind == ChargeKind.PackageCredit) ChargeState.Settled else ChargeState.Unpaid,
            createdAt = serverNow(),
            packagePercent = percent.takeIf { kind == ChargeKind.PackageCredit },
        )
        // A new charge is not overdue: the client list's overdue flag stays the finance flow's decision.
        charges.getOrPut(session.clientId) { mutableListOf() } += charge
    }

    /** The late-reschedule fee is charged when the trainer accepts, unless the trainer waives it. */
    internal fun chargeLateReschedule(session: Session, proposal: RescheduleProposal, waiver: FeeWaiver?) {
        if (waiver != null || proposal.author != ProposalAuthor.Client) return
        val fee = FeeCalculator.reschedule(policyFor(session), session.start, proposal.requestedAt ?: serverNow(), priceFor(session))
        addCharge(session, ChargeReason.LateReschedule, fee.amount, fee.policyPercent)
    }

    internal fun clientIdFor(identity: ClientIdentity, create: Boolean): String? = clientIdsByUser[identity.userId]
        ?: if (!create) null else "cu-${nextId++}".also { id ->
            clientIdsByUser[identity.userId] = id
        }

    /** A booking request with a new trainer starts the relationship; the trainer sees the client in the calendar. */
    internal fun ensureRelationship(clientId: String, trainerId: String, displayName: String) {
        if (!relationships.add(clientId to trainerId)) return
        if (trainerId == DemoTrainerId && data.clients.none { it.id == clientId }) {
            val initials = displayName.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
            data = data.copy(clients = data.clients + Client(clientId, displayName.ifBlank { clientId }, initials.ifBlank { "?" }))
        }
    }

    internal fun register(trainer: CatalogTrainer) {
        val id = canonicalTrainerId(trainer.id)
        if (id in trainers) return
        trainers[id] = DemoTrainerProfile(
            TrainerRef(id, trainer.name, trainer.initials),
            trainer.price ?: Money.pln(120),
            trainer.places.ifEmpty { listOf("Siłownia trenera") },
        )
        val hours = trainer.freeCancellationHours ?: 24
        policies += CancellationPolicy("pol-$id-v1", id, 1, DemoServerNow - (60 * 24).hours, listOf(PolicyTier(hours, 50)), 100, 15, 1)
    }

    /** The demo payment provider confirms a pending payment after a short delay. */
    internal fun confirmLater(clientId: String, paymentId: String) {
        scope.launch {
            delay(paymentConfirmationMillis)
            transaction {
                val list = payments[clientId] ?: return@transaction
                val payment = list.find { it.id == paymentId && it.state == PaymentState.Pending } ?: return@transaction
                list[list.indexOf(payment)] = payment.copy(state = PaymentState.Paid, at = serverNow())
                charges[clientId]?.replaceAll { if (it.id == payment.chargeId) it.copy(state = ChargeState.Settled) else it }
            }
        }
    }

    internal fun checkTrainerSlot(start: Instant, end: Instant, exceptId: String? = null) {
        if (start >= end) throw TrainerException(Failure.InvalidInput)
        val overlap = data.sessions.any {
            it.id != exceptId && trainerOf(it) == DemoTrainerId && it.isActive() && start < it.end && end > it.start
        } || data.blocks.any { start < it.end && end > it.start }
        if (overlap) throw TrainerException(Failure.Conflict)
    }

    internal fun nextDays(from: LocalDate, count: Int) = (0 until count).map { from.plus(it, DateTimeUnit.DAY) }

    companion object {
        /** Quotes are valid for five minutes of server time. */
        val QuoteValidity = 5.minutes
    }
}

internal val demoTrainerProfile = DemoTrainerProfile(
    TrainerRef(DemoTrainerId, "Michał Kowalczyk", "MK"),
    price = Money.pln(120),
    places = listOf("Studio Centrum", "Siłownia Wola"),
)

/** The demo trainer's first policy version: free until 24 h, then 50%; no-show 100%, 15 min grace, 1 free per month. */
internal val demoPolicyV1 = CancellationPolicy(
    versionId = "pol-demo-trainer-v1",
    trainerId = DemoTrainerId,
    version = 1,
    createdAt = LocalDateTime(2026, 9, 1, 10, 0).toInstant(TimeZone.of("Europe/Warsaw")),
    tiers = listOf(PolicyTier(24, 50)),
    noShowPercent = 100,
    graceMinutes = DemoNoShowGraceMinutes,
    freeLateCancellationsPerMonth = 1,
)

private fun demoCharges() = listOf(
    Charge(
        id = "ch-1",
        reason = ChargeReason.LateCancellation,
        sessionId = "s12",
        sessionStart = demoAt(10, 23, 9),
        trainer = demoTrainerProfile.ref,
        amount = Money.pln(60),
        kind = ChargeKind.Online,
        state = ChargeState.Unpaid,
        createdAt = demoAt(10, 22, 19),
    ),
)

private fun demoPayments() = listOf(
    Payment("pay-1", null, null, demoTrainerProfile.ref, Money.pln(120), PaymentMethod.Blik, PaymentState.Paid, demoAt(10, 2, 18)),
    Payment("pay-2", null, null, demoTrainerProfile.ref, Money.pln(120), PaymentMethod.Card, PaymentState.Failed, demoAt(9, 25, 8)),
    Payment("pay-3", null, null, demoTrainerProfile.ref, Money.pln(120), PaymentMethod.Card, PaymentState.Refunded, demoAt(9, 18, 12)),
)

internal fun demoAt(month: Int, day: Int, hour: Int, minute: Int = 0) =
    LocalDateTime(2026, month, day, hour, minute).toInstant(TimeZone.of("Europe/Warsaw"))
