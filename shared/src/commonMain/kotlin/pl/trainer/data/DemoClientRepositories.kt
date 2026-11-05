package pl.trainer.data

import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.CancellationPolicy
import com.trainingapp.contract.policy.CancelledBy
import com.trainingapp.contract.policy.FeeCalculator
import com.trainingapp.contract.policy.PolicyRules
import com.trainingapp.contract.policy.PolicyValidator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import pl.trainer.domain.*

/**
 * The client's scheduling repository over the shared demo store. [identity] is the signed-in client; [isOnline]
 * stands in for connectivity: reads return the last known data offline (as a cache would), changes are refused
 * with [Failure.Offline] and never applied.
 */
class DemoClientScheduleRepository(
    private val store: DemoSchedulingStore,
    private val identity: () -> ClientIdentity?,
    private val isOnline: () -> Boolean = { true },
    /** Public trainers from the guest catalogue, so a request from a public profile can be sent. */
    private val catalog: suspend (String) -> CatalogTrainer? = { null },
    private val latencyMillis: Long = 350,
) : ClientScheduleRepository {
    override val updates: StateFlow<Long> get() = store.updates

    private fun me(): ClientIdentity = identity() ?: throw TrainerException(Failure.Denied)

    private suspend fun <T> read(block: DemoSchedulingStore.(clientId: String?) -> T): T {
        delay(latencyMillis)
        val user = me()
        return store.transaction(changes = false) { block(clientIdFor(user, create = false)) }
    }

    private suspend fun <T> write(block: DemoSchedulingStore.(clientId: String, user: ClientIdentity) -> T): T {
        delay(latencyMillis)
        if (!isOnline()) throw TrainerException(Failure.Offline)
        val user = me()
        return store.transaction { block(clientIdFor(user, create = true)!!, user) }
    }

    private fun DemoSchedulingStore.own(clientId: String?, sessionId: String, revision: Int? = null): Session {
        val session = data.sessions.find { it.id == sessionId && it.clientId == clientId } ?: throw TrainerException(Failure.Conflict)
        if (revision != null && session.revision != revision) throw TrainerException(Failure.Conflict)
        return session
    }

    private fun DemoSchedulingStore.view(session: Session) = ClientSession(session, trainerRef(trainerOf(session)))

    override suspend fun load(): ClientSchedule = read { clientId ->
        val trainerIds = relationships.filter { it.first == clientId }.map { it.second }
        ClientSchedule(
            trainers = trainerIds.map { trainerRef(it) },
            sessions = data.sessions.filter { it.clientId == clientId && clientId != null }.map { view(it) },
            serverNow = serverNow(),
        )
    }

    override suspend fun bookingOptions(trainerId: String): BookingOptions {
        val id = store.transaction(changes = false) { canonicalTrainerId(trainerId) }
        if (store.transaction(changes = false) { id !in trainers }) {
            val entry = catalog(trainerId) ?: throw TrainerException(Failure.Load)
            store.transaction(changes = false) { register(entry) }
        }
        return read {
            val profile = trainers[id] ?: throw TrainerException(Failure.Load)
            BookingOptions(profile.ref, currentPolicy(id), profile.price, profile.places, profile.timeZone)
        }
    }

    override suspend fun availableSlots(trainerId: String, date: LocalDate, excludeSessionId: String?): List<AvailableSlot> =
        read { freeSlots(canonicalTrainerId(trainerId), date, excludeSessionId) }

    override suspend fun requestBooking(request: BookingRequest): ClientSession = write { clientId, user ->
        val trainerId = canonicalTrainerId(request.trainerId)
        val profile = trainers[trainerId] ?: throw TrainerException(Failure.InvalidInput)
        if (request.place.isBlank() || !isWorkingSlot(request.start, request.end) || request.start <= serverNow()) {
            throw TrainerException(Failure.InvalidInput)
        }
        // The consent must be for the version that is current when the server receives the request.
        if (currentPolicy(trainerId).versionId != request.acceptedPolicyVersionId) throw TrainerException(Failure.Conflict)
        if (slotTaken(trainerId, request.start, request.end)) throw TrainerException(Failure.Conflict)
        ensureRelationship(clientId, trainerId, user.displayName)
        val pack = packages[clientId]?.firstOrNull { it.trainer.id == trainerId && it.used < it.total }
        val session = Session(
            id = "s-${nextId++}",
            clientId = clientId,
            start = request.start,
            end = request.end,
            timeZone = profile.timeZone,
            location = request.place.trim(),
            status = SessionStatus.Requested,
            payment = if (pack != null) SessionPayment(PaymentKind.Package, pack.used + 1, pack.total) else SessionPayment(PaymentKind.Online),
            policyVersionId = request.acceptedPolicyVersionId,
            price = profile.price,
            trainerId = trainerId,
            requestedAt = serverNow(),
        )
        data = data.copy(sessions = data.sessions + session)
        view(session)
    }

    /** Withdrawing a request costs nothing. A reschedule request is dropped; the original time stays confirmed. */
    override suspend fun withdrawRequest(sessionId: String, revision: Int): ClientSession = write { clientId, _ ->
        val session = own(clientId, sessionId, revision)
        val updated = when {
            session.status == SessionStatus.Requested -> session.copy(status = SessionStatus.CancelledByClient, revision = revision + 1)
            session.proposal?.author == ProposalAuthor.Client -> session.copy(proposal = null, revision = revision + 1)
            else -> throw TrainerException(Failure.Conflict)
        }
        replace(updated)
        view(updated)
    }

    override suspend fun previewRescheduleFee(sessionId: String, revision: Int): RescheduleFeePreview? = read { clientId ->
        val session = own(clientId, sessionId, revision)
        val policy = policyFor(session)
        val fee = FeeCalculator.reschedule(policy, session.start, serverNow(), priceFor(session))
        if (fee.late) RescheduleFeePreview(fee, policy.versionId) else null
    }

    override suspend fun requestReschedule(request: RescheduleRequest): ClientSession = write { clientId, _ ->
        val session = own(clientId, request.sessionId, request.revision)
        if (session.status != SessionStatus.Confirmed || session.proposal != null || session.start <= serverNow()) {
            throw TrainerException(Failure.Conflict)
        }
        if (!isWorkingSlot(request.start, request.end) || request.start <= serverNow()) throw TrainerException(Failure.InvalidInput)
        if (slotTaken(trainerOf(session), request.start, request.end, session.id)) throw TrainerException(Failure.Conflict)
        val updated = session.copy(
            proposal = RescheduleProposal(request.start, request.end, ProposalAuthor.Client, requestedAt = serverNow()),
            revision = session.revision + 1,
        )
        replace(updated)
        view(updated)
    }

    /** Accepting moves the session only when the server confirms the new slot is still free. */
    override suspend fun answerProposal(sessionId: String, revision: Int, accept: Boolean): ClientSession = write { clientId, _ ->
        val session = own(clientId, sessionId, revision)
        val proposal = session.proposal
        if (proposal?.author != ProposalAuthor.Trainer || session.status != SessionStatus.Confirmed) throw TrainerException(Failure.Conflict)
        val updated = if (accept) {
            if (slotTaken(trainerOf(session), proposal.start, proposal.end, session.id)) throw TrainerException(Failure.Conflict)
            session.copy(start = proposal.start, end = proposal.end, proposal = null, revision = revision + 1)
        } else {
            session.copy(proposal = null, revision = revision + 1)
        }
        replace(updated)
        view(updated)
    }

    override suspend fun quoteCancellation(sessionId: String, revision: Int): CancellationQuote {
        delay(latencyMillis)
        // A quote is a server answer: it needs a connection like any other request.
        if (!isOnline()) throw TrainerException(Failure.Offline)
        val user = me()
        return store.transaction(changes = false) {
            val clientId = clientIdFor(user, create = false)
            val session = own(clientId, sessionId, revision)
            val now = serverNow()
            if (session.status != SessionStatus.Confirmed || session.start <= now) throw TrainerException(Failure.Conflict)
            val trainerId = trainerOf(session)
            val policy = policyFor(session)
            val freeLeft = freeLateLeft(session.clientId, trainerId, policy, now)
            val fee = FeeCalculator.cancellation(policy, session.start, now, priceFor(session), freeLeft, CancelledBy.Client)
            CancellationQuote(
                quoteId = "q-${nextId++}",
                sessionId = session.id,
                revision = session.revision,
                fee = fee,
                policyVersionId = policy.versionId,
                freeLateCancellationsLeft = freeLeft,
                chargeKind = session.payment.chargeKind(),
                quotedAt = now,
                expiresAt = now + DemoSchedulingStore.QuoteValidity,
            ).also { quotes[it.quoteId] = it }
        }
    }

    override suspend fun cancel(sessionId: String, revision: Int, quoteId: String): ClientSession = write { clientId, _ ->
        val quote = quotes[quoteId]
        if (quote == null || quote.sessionId != sessionId || serverNow() > quote.expiresAt) throw TrainerException(Failure.QuoteExpired)
        val session = own(clientId, sessionId)
        // The session changed after the quote (e.g. the trainer moved it): the old amount is no longer valid.
        if (session.revision != revision || quote.revision != revision) throw TrainerException(Failure.QuoteExpired)
        if (session.status != SessionStatus.Confirmed) throw TrainerException(Failure.Conflict)
        quotes.remove(quoteId)
        if (quote.fee.usesFreeCancellation) useFreeLate(session.clientId, trainerOf(session), quote.quotedAt)
        val updated = session.copy(status = SessionStatus.CancelledByClient, proposal = null, revision = revision + 1)
        addCharge(updated, ChargeReason.LateCancellation, quote.fee.amount, quote.fee.policyPercent)
        replace(updated)
        view(updated)
    }

    override suspend fun policy(versionId: String): CancellationPolicy = read {
        policies.find { it.versionId == versionId } ?: throw TrainerException(Failure.Load)
    }
}

/** The client's wallet over the shared demo store. Payments go through a demo test-mode provider. */
class DemoWalletRepository(
    private val store: DemoSchedulingStore,
    private val identity: () -> ClientIdentity?,
    private val isOnline: () -> Boolean = { true },
    private val latencyMillis: Long = 350,
) : WalletRepository {
    override val updates: StateFlow<Long> get() = store.updates

    override suspend fun load(): Wallet {
        delay(latencyMillis)
        val user = identity() ?: throw TrainerException(Failure.Denied)
        return store.transaction(changes = false) {
            val clientId = clientIdFor(user, create = false)
            Wallet(
                packages = packages[clientId].orEmpty().toList(),
                charges = charges[clientId].orEmpty().sortedByDescending { it.createdAt },
                payments = payments[clientId].orEmpty().sortedByDescending { it.at },
                serverNow = serverNow(),
            )
        }
    }

    override suspend fun pay(chargeId: String, method: PaymentMethod, idempotencyKey: String): Payment {
        delay(latencyMillis)
        if (!isOnline()) throw TrainerException(Failure.Offline)
        val user = identity() ?: throw TrainerException(Failure.Denied)
        var confirm: Pair<String, String>? = null
        val payment = store.transaction {
            paymentsByKey[idempotencyKey]?.let { return@transaction it }
            val clientId = clientIdFor(user, create = false) ?: throw TrainerException(Failure.Denied)
            val owed = charges[clientId] ?: throw TrainerException(Failure.Conflict)
            val charge = owed.find { it.id == chargeId } ?: throw TrainerException(Failure.Conflict)
            if (!charge.payableInApp) throw TrainerException(Failure.Conflict)
            val created = Payment("pay-${nextId++}", charge.id, charge.reason, charge.trainer, charge.amount, method, PaymentState.Pending, serverNow())
            owed[owed.indexOf(charge)] = charge.copy(state = ChargeState.PaymentPending)
            payments.getOrPut(clientId) { mutableListOf() } += created
            paymentsByKey[idempotencyKey] = created
            confirm = clientId to created.id
            created
        }
        confirm?.let { (clientId, paymentId) -> store.confirmLater(clientId, paymentId) }
        return payment
    }
}

/** The trainer's cancellation policy versions over the shared demo store. */
class DemoPolicyRepository(
    private val store: DemoSchedulingStore,
    private val isOnline: () -> Boolean = { true },
    private val latencyMillis: Long = 350,
) : PolicyRepository {
    override val updates: StateFlow<Long> get() = store.updates

    override suspend fun current(trainerId: String): CancellationPolicy {
        delay(latencyMillis)
        return store.transaction(changes = false) { currentPolicy(canonicalTrainerId(trainerId)) }
    }

    override suspend fun version(versionId: String): CancellationPolicy {
        delay(latencyMillis)
        return store.transaction(changes = false) { policies.find { it.versionId == versionId } ?: throw TrainerException(Failure.Load) }
    }

    override suspend fun sessionPrice(trainerId: String): Money =
        store.transaction(changes = false) { trainers[canonicalTrainerId(trainerId)]?.price ?: Money.pln(120) }

    override suspend fun save(trainerId: String, rules: PolicyRules, basedOnVersionId: String): CancellationPolicy {
        delay(latencyMillis)
        if (!isOnline()) throw TrainerException(Failure.Offline)
        if (PolicyValidator.errors(rules).isNotEmpty()) throw TrainerException(Failure.InvalidInput)
        return store.transaction {
            val id = canonicalTrainerId(trainerId)
            val current = currentPolicy(id)
            if (current.versionId != basedOnVersionId) throw TrainerException(Failure.Conflict)
            val next = current.version + 1
            rules.toPolicy("pol-$id-v$next", id, next, serverNow()).also { policies += it }
        }
    }
}
