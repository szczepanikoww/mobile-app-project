package com.trainingapp.server.scheduling

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import com.trainingapp.contract.api.AcceptRequest
import com.trainingapp.contract.api.CheckInDto
import com.trainingapp.contract.api.ChargeKind
import com.trainingapp.contract.api.CreateSessionRequest
import com.trainingapp.contract.api.FeeQuoteDto
import com.trainingapp.contract.api.PackageFeeMode
import com.trainingapp.contract.api.PaymentKind
import com.trainingapp.contract.api.ProposeTimeRequest
import com.trainingapp.contract.api.ProposerRole
import com.trainingapp.contract.api.RequestKind
import com.trainingapp.contract.api.RequestReviewDto
import com.trainingapp.contract.api.RescheduleStatus
import com.trainingapp.contract.api.RevisionRequest
import com.trainingapp.contract.api.SessionResultDto
import com.trainingapp.contract.api.SessionStatus
import com.trainingapp.contract.api.Settlement
import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.FeeCalculator
import com.trainingapp.server.auth.Access
import com.trainingapp.server.billing.FeeAction
import com.trainingapp.server.billing.Ledger
import com.trainingapp.server.catalogue.Policies
import com.trainingapp.server.catalogue.toContract
import com.trainingapp.server.db.Attendances
import com.trainingapp.server.db.ClientPriceOverrides
import com.trainingapp.server.db.Gyms
import com.trainingapp.server.db.Offers
import com.trainingapp.server.db.RescheduleRequests
import com.trainingapp.server.db.SessionEvents
import com.trainingapp.server.db.Sessions
import com.trainingapp.server.db.TrainerClients
import com.trainingapp.server.db.TrainerProfiles
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.TrainerOutbox
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.conflict
import com.trainingapp.server.platform.invalid
import com.trainingapp.server.platform.newId
import com.trainingapp.server.platform.notFound
import com.trainingapp.server.platform.toInstantK
import com.trainingapp.server.platform.toOffset
import com.trainingapp.server.platform.toUuid
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The trainer's side of the session lifecycle (architecture §7, docs/trainer-ui.md "Backend integration contract").
 *
 * Every command runs in one transaction that locks the trainer row (serialises one trainer's calendar changes) and
 * the session row, checks the caller's revision, applies the change, bumps the revision, writes a session event and an
 * outbox event, and records any money consequence in the ledger. No external call happens inside.
 */
class TrainerSessions(private val db: Db, private val clock: ServerClock) {

    companion object {
        val MAX_SESSION = 8.hours
        val MIN_SESSION = 15.minutes
    }

    // ------------------------------------------------------------------ reads

    suspend fun checkIn(trainerId: UUID, sessionId: UUID): CheckInDto = db.tx {
        val now = clock.now()
        val row = ownSession(trainerId, sessionId)
        val dto = SessionReads.toDto(row)
        val policy = Policies.byId(row[Sessions.policyVersionId])
        val noShowAt = dto.start + policy.graceMinutes.minutes
        CheckInDto(
            session = dto,
            client = SessionReads.client(row[Sessions.relationshipId]),
            started = now >= dto.start,
            noShowAvailableAt = noShowAt,
            noShowAllowed = dto.status == SessionStatus.CONFIRMED && now >= noShowAt,
            graceMinutes = policy.graceMinutes,
            serverNow = now,
        )
    }

    /** B4: what the trainer needs to answer an open booking or reschedule request. */
    suspend fun requestReview(trainerId: UUID, sessionId: UUID): RequestReviewDto = db.tx {
        val now = clock.now()
        val row = ownSession(trainerId, sessionId)
        val dto = SessionReads.toDto(row)
        val policy = Policies.byId(row[Sessions.policyVersionId])
        val buffer = bufferOf(trainerId)
        val open = SessionReads.openReschedule(sessionId)
        if (dto.status == SessionStatus.REQUESTED) {
            RequestReviewDto(
                session = dto, client = SessionReads.client(row[Sessions.relationshipId]), kind = RequestKind.BOOKING,
                requestedAt = dto.requestedAt, requestedStart = dto.start, requestedEnd = dto.end,
                slotAvailable = dto.start > now && SessionReads.slotFree(trainerId, dto.start, dto.end + buffer, sessionId),
                policy = policy, lateFee = null, serverNow = now,
            )
        } else if (open != null && open[RescheduleRequests.proposerRole] == ProposerRole.CLIENT.name &&
            open[RescheduleRequests.status] == RescheduleStatus.PENDING.name
        ) {
            val start = open[RescheduleRequests.proposedStartsAt].toInstantK()
            val end = open[RescheduleRequests.proposedEndsAt].toInstantK()
            RequestReviewDto(
                session = dto, client = SessionReads.client(row[Sessions.relationshipId]), kind = RequestKind.RESCHEDULE,
                requestedAt = open[RescheduleRequests.receivedAt].toInstantK(), requestedStart = start, requestedEnd = end,
                slotAvailable = start > now && SessionReads.slotFree(trainerId, start, end + buffer, sessionId),
                policy = policy, lateFee = lateRescheduleFee(row, open)?.let { (quote, _) -> quote }, serverNow = now,
            )
        } else {
            notFound("No open request for this session")
        }
    }

    // ------------------------------------------------------------------ commands

    /** G10: the trainer books a session directly; it is CONFIRMED at once. Idempotent by operationId. */
    suspend fun create(trainerId: UUID, request: CreateSessionRequest): SessionResultDto = db.tx {
        val now = clock.now()
        val operationId = request.operationId.toUuid("operationId")
        Sessions.selectAll().where { Sessions.operationId eq operationId }.singleOrNull()?.let { existing ->
            if (existing[Sessions.trainerId] != trainerId) conflict("Operation id already used")
            return@tx SessionResultDto(SessionReads.toDto(existing), now)
        }
        val relationshipId = request.clientId.toUuid("clientId")
        lockTrainer(trainerId)
        val relationship = Access.activeRelationship(trainerId, relationshipId)
        validateTimes(request.start, request.end, now)
        if (request.location.length > 200) invalid("Location too long")
        val gymId = request.gymId?.toUuid("gymId")?.also { gym ->
            if (Gyms.selectAll().where { Gyms.id eq gym }.empty()) invalid("Unknown gym")
        }
        val (offerId, price) = priceFor(trainerId, relationshipId, request.offerId?.toUuid("offerId"))
        val policy = Policies.forNewSession(trainerId, relationshipId)
        val buffer = bufferOf(trainerId)
        if (!SessionReads.slotFree(trainerId, request.start, request.end + buffer, null)) conflict("Time slot is not free")

        val id = newId()
        Sessions.insert {
            it[Sessions.id] = id
            it[Sessions.relationshipId] = relationshipId
            it[Sessions.trainerId] = trainerId
            it[clientId] = relationship[TrainerClients.clientId]
            it[Sessions.offerId] = offerId
            it[status] = SessionStatus.CONFIRMED.name
            it[startsAt] = request.start.toOffset()
            it[endsAt] = request.end.toOffset()
            it[blockedUntil] = (request.end + buffer).toOffset()
            it[timeZone] = timeZoneOf(trainerId)
            it[Sessions.gymId] = gymId
            it[placeNote] = request.location.takeIf { l -> l.isNotBlank() }
            it[priceMinor] = price.minor
            it[currency] = price.currency
            it[paymentKind] = request.paymentKind.name
            it[policyVersionId] = policy.versionId.toUuid()
            // Created by the trainer: the client's policy consent is the relationship's acceptance (see trainer-backend.md TB4).
            it[policyAcceptedAt] = now.toOffset()
            it[revision] = 1
            it[createdBy] = trainerId
            it[Sessions.operationId] = operationId
            it[requestedAt] = now.toOffset()
            it[confirmedAt] = now.toOffset()
        }
        if (request.paymentKind == PaymentKind.PACKAGE) Ledger.reserveCredit(relationshipId, id, now)
        event(id, 1, "CONFIRMED", trainerId, now)
        TrainerOutbox.emit("session.confirmed", "session", id, mapOf("relationshipId" to relationshipId), now)
        SessionResultDto(SessionReads.session(id)!!, now)
    }

    /**
     * Accepts the open request: a booking request becomes CONFIRMED; a client's reschedule proposal moves the session
     * (after which a late-reschedule fee is decided, unless [AcceptRequest.waiver] waives it in full, D8).
     */
    suspend fun accept(trainerId: UUID, sessionId: UUID, request: AcceptRequest): SessionResultDto = db.tx {
        val now = clock.now()
        lockTrainer(trainerId)
        val row = lockOwnSession(trainerId, sessionId)
        checkRevision(row, request.revision)
        Access.activeRelationship(trainerId, row[Sessions.relationshipId])
        val buffer = bufferOf(trainerId)
        val open = SessionReads.openReschedule(sessionId)
        when {
            row[Sessions.status] == SessionStatus.REQUESTED.name -> {
                val start = row[Sessions.startsAt].toInstantK()
                if (start <= now) conflict("Request has expired")
                if (request.waiver != null) invalid("A booking request has no fee to waive")
                val revision = row[Sessions.revision] + 1
                Sessions.update({ Sessions.id eq sessionId }) {
                    it[status] = SessionStatus.CONFIRMED.name
                    it[blockedUntil] = (row[Sessions.endsAt].toInstantK() + buffer).toOffset()
                    it[Sessions.revision] = revision
                    it[confirmedAt] = now.toOffset()
                }
                if (row[Sessions.paymentKind] == PaymentKind.PACKAGE.name) Ledger.reserveCredit(row[Sessions.relationshipId], sessionId, now)
                event(sessionId, revision, "CONFIRMED", trainerId, now)
                TrainerOutbox.emit("session.confirmed", "session", sessionId, mapOf("relationshipId" to row[Sessions.relationshipId]), now)
            }
            open != null && open[RescheduleRequests.proposerRole] == ProposerRole.CLIENT.name &&
                open[RescheduleRequests.status] == RescheduleStatus.PENDING.name -> {
                if (row[Sessions.status] != SessionStatus.CONFIRMED.name) conflict("Session is not confirmed")
                if (open[RescheduleRequests.baseRevision] != row[Sessions.revision]) conflict("Session changed since the proposal")
                val newStart = open[RescheduleRequests.proposedStartsAt].toInstantK()
                val newEnd = open[RescheduleRequests.proposedEndsAt].toInstantK()
                if (newStart <= now) conflict("Proposed time has passed")
                val fee = lateRescheduleFee(row, open)
                val revision = move(row, newStart, newEnd, buffer, trainerId, now)
                RescheduleRequests.update({ RescheduleRequests.id eq open[RescheduleRequests.id] }) {
                    it[status] = RescheduleStatus.ACCEPTED.name
                    it[decidedBy] = trainerId
                    it[decidedAt] = now.toOffset()
                }
                if (fee != null) {
                    val (quote, percent) = fee
                    val policy = Policies.byId(row[Sessions.policyVersionId])
                    Ledger.decideFee(
                        sessionId = sessionId, relationshipId = row[Sessions.relationshipId], action = FeeAction.LATE_RESCHEDULE,
                        receivedAt = open[RescheduleRequests.receivedAt].toInstantK(), policyVersionId = row[Sessions.policyVersionId],
                        feePercent = percent, quotedMinor = quote.amount.minor, currency = quote.amount.currency,
                        waived = request.waiver != null, exempt = false, usedFreeCancellation = false,
                        reason = request.waiver?.reason, decidedBy = trainerId,
                        settlement = Ledger.feeSettlement(PaymentKind.valueOf(row[Sessions.paymentKind]), policy.packageFeeMode == PackageFeeMode.USE_CREDIT),
                        now = now,
                    )
                } else if (request.waiver != null) {
                    invalid("This reschedule has no fee to waive")
                }
                TrainerOutbox.emit("session.moved", "session", sessionId, mapOf("relationshipId" to row[Sessions.relationshipId]), now)
                revision
            }
            else -> conflict("Nothing to accept")
        }
        SessionResultDto(SessionReads.session(sessionId)!!, now)
    }

    /** Rejects a booking request (→ REJECTED) or the client's reschedule proposal (the session keeps its time). */
    suspend fun reject(trainerId: UUID, sessionId: UUID, request: RevisionRequest): SessionResultDto = db.tx {
        val now = clock.now()
        val row = lockOwnSession(trainerId, sessionId)
        checkRevision(row, request.revision)
        val open = SessionReads.openReschedule(sessionId)
        when {
            row[Sessions.status] == SessionStatus.REQUESTED.name -> {
                val revision = row[Sessions.revision] + 1
                Sessions.update({ Sessions.id eq sessionId }) {
                    it[status] = SessionStatus.REJECTED.name
                    it[Sessions.revision] = revision
                    it[closedAt] = now.toOffset()
                }
                closeOpenReschedule(sessionId, RescheduleStatus.WITHDRAWN, trainerId, now)
                event(sessionId, revision, "REJECTED", trainerId, now)
                TrainerOutbox.emit("session.rejected", "session", sessionId, mapOf("relationshipId" to row[Sessions.relationshipId]), now)
            }
            open != null && open[RescheduleRequests.proposerRole] == ProposerRole.CLIENT.name &&
                open[RescheduleRequests.status] == RescheduleStatus.PENDING.name -> {
                closeOpenReschedule(sessionId, RescheduleStatus.REJECTED, trainerId, now)
                TrainerOutbox.emit("reschedule.rejected", "session", sessionId, mapOf("relationshipId" to row[Sessions.relationshipId]), now)
            }
            open != null && open[RescheduleRequests.proposerRole] == ProposerRole.TRAINER.name -> {
                // The trainer withdraws their own proposal.
                closeOpenReschedule(sessionId, RescheduleStatus.WITHDRAWN, trainerId, now)
            }
            else -> conflict("Nothing to reject")
        }
        SessionResultDto(SessionReads.session(sessionId)!!, now)
    }

    /**
     * The trainer proposes another time (G11: never costs the client). With a client proposal open this is a counter-
     * proposal; otherwise a new proposal the client accepts. The session keeps its confirmed time meanwhile.
     */
    suspend fun propose(trainerId: UUID, sessionId: UUID, request: ProposeTimeRequest): SessionResultDto = db.tx {
        val now = clock.now()
        val operationId = request.operationId.toUuid("operationId")
        if (RescheduleRequests.selectAll().where { RescheduleRequests.operationId eq operationId }.any()) {
            return@tx SessionResultDto(SessionReads.session(sessionId) ?: notFound(), now)
        }
        val row = lockOwnSession(trainerId, sessionId)
        checkRevision(row, request.revision)
        Access.activeRelationship(trainerId, row[Sessions.relationshipId])
        validateTimes(request.start, request.end, now)
        val status = row[Sessions.status]
        if (status != SessionStatus.CONFIRMED.name && status != SessionStatus.REQUESTED.name) conflict("Session cannot be moved")
        if (row[Sessions.startsAt].toInstantK() <= now) conflict("Session has already started")
        val open = SessionReads.openReschedule(sessionId)
        if (open != null && open[RescheduleRequests.proposerRole] == ProposerRole.CLIENT.name &&
            open[RescheduleRequests.status] == RescheduleStatus.PENDING.name
        ) {
            RescheduleRequests.update({ RescheduleRequests.id eq open[RescheduleRequests.id] }) {
                it[RescheduleRequests.status] = RescheduleStatus.COUNTER_PROPOSED.name
                it[counterStartsAt] = request.start.toOffset()
                it[counterEndsAt] = request.end.toOffset()
            }
        } else {
            if (open != null) closeOpenReschedule(sessionId, RescheduleStatus.WITHDRAWN, trainerId, now)
            RescheduleRequests.insert {
                it[id] = newId()
                it[RescheduleRequests.sessionId] = sessionId
                it[baseRevision] = row[Sessions.revision]
                it[proposedBy] = trainerId
                it[proposerRole] = ProposerRole.TRAINER.name
                it[proposedStartsAt] = request.start.toOffset()
                it[proposedEndsAt] = request.end.toOffset()
                it[RescheduleRequests.status] = RescheduleStatus.PENDING.name
                it[receivedAt] = now.toOffset()
                it[RescheduleRequests.operationId] = operationId
            }
        }
        TrainerOutbox.emit("reschedule.proposed", "session", sessionId, mapOf("relationshipId" to row[Sessions.relationshipId]), now)
        SessionResultDto(SessionReads.session(sessionId)!!, now)
    }

    /**
     * Trainer cancellation: never charges the client (fee decision TRAINER_CANCEL with 0), releases package credits and
     * refunds money already received for the session. A booking request is rejected instead.
     */
    suspend fun cancel(trainerId: UUID, sessionId: UUID, request: RevisionRequest): SessionResultDto {
        val status = db.tx { ownSession(trainerId, sessionId)[Sessions.status] }
        if (status == SessionStatus.REQUESTED.name) return reject(trainerId, sessionId, request)
        return db.tx {
            val now = clock.now()
            val row = lockOwnSession(trainerId, sessionId)
            checkRevision(row, request.revision)
            if (row[Sessions.status] != SessionStatus.CONFIRMED.name) conflict("Session cannot be cancelled")
            val revision = row[Sessions.revision] + 1
            Sessions.update({ Sessions.id eq sessionId }) {
                it[Sessions.status] = SessionStatus.CANCELLED_BY_TRAINER.name
                it[Sessions.revision] = revision
                it[closedAt] = now.toOffset()
            }
            closeOpenReschedule(sessionId, RescheduleStatus.WITHDRAWN, trainerId, now)
            Ledger.decideFee(
                sessionId = sessionId, relationshipId = row[Sessions.relationshipId], action = FeeAction.TRAINER_CANCEL,
                receivedAt = now, policyVersionId = row[Sessions.policyVersionId], feePercent = 0, quotedMinor = 0,
                currency = row[Sessions.currency], waived = false, exempt = false, usedFreeCancellation = false,
                reason = null, decidedBy = trainerId, settlement = Settlement.CASH, now = now,
            )
            if (row[Sessions.paymentKind] == PaymentKind.PACKAGE.name) Ledger.releaseCredit(sessionId, now)
            Ledger.refundSession(sessionId, now)
            event(sessionId, revision, "CANCELLED", trainerId, now)
            TrainerOutbox.emit("session.cancelled", "session", sessionId, mapOf("relationshipId" to row[Sessions.relationshipId]), now)
            SessionResultDto(SessionReads.session(sessionId)!!, now)
        }
    }

    /**
     * Check-in ("Potwierdź obecność", D4): the session is COMPLETED and attendance is recorded. A cash session creates
     * its SESSION charge; an online session gets one if the booking flow has not created it; a package session uses its
     * reserved credit.
     */
    suspend fun complete(trainerId: UUID, sessionId: UUID, request: RevisionRequest): SessionResultDto = db.tx {
        val now = clock.now()
        val row = lockOwnSession(trainerId, sessionId)
        checkRevision(row, request.revision)
        if (row[Sessions.status] != SessionStatus.CONFIRMED.name) conflict("Session is not confirmed")
        if (row[Sessions.startsAt].toInstantK() > now) conflict("Session has not started yet")
        val revision = row[Sessions.revision] + 1
        Sessions.update({ Sessions.id eq sessionId }) {
            it[status] = SessionStatus.COMPLETED.name
            it[Sessions.revision] = revision
            it[closedAt] = now.toOffset()
        }
        closeOpenReschedule(sessionId, RescheduleStatus.EXPIRED, null, now)
        attendance(sessionId, "ATTENDED", trainerId, now)
        val relationshipId = row[Sessions.relationshipId]
        val price = row[Sessions.priceMinor]
        when (PaymentKind.valueOf(row[Sessions.paymentKind])) {
            PaymentKind.PACKAGE -> Ledger.consumeCredit(sessionId, Ledger.UNITS_PER_SESSION, now)
            PaymentKind.CASH -> if (price > 0 && !Ledger.hasSessionCharge(sessionId)) {
                Ledger.charge(relationshipId, ChargeKind.SESSION, Settlement.CASH, sessionId, null, price, row[Sessions.currency], now, now)
            }
            PaymentKind.ONLINE -> if (price > 0 && !Ledger.hasSessionCharge(sessionId)) {
                Ledger.charge(relationshipId, ChargeKind.SESSION, Settlement.ONLINE, sessionId, null, price, row[Sessions.currency],
                    row[Sessions.startsAt].toInstantK(), now)
            }
        }
        event(sessionId, revision, "CHECKED_IN", trainerId, now)
        TrainerOutbox.emit("session.completed", "session", sessionId, mapOf("relationshipId" to relationshipId), now)
        SessionResultDto(SessionReads.session(sessionId)!!, now)
    }

    /**
     * No-show: allowed only after start + the grace period of the session's policy version (D2). The fee is the
     * policy's no-show percent of the snapshotted price, unless the client is EXEMPT.
     */
    suspend fun noShow(trainerId: UUID, sessionId: UUID, request: RevisionRequest): SessionResultDto = db.tx {
        val now = clock.now()
        val row = lockOwnSession(trainerId, sessionId)
        checkRevision(row, request.revision)
        if (row[Sessions.status] != SessionStatus.CONFIRMED.name) conflict("Session is not confirmed")
        val policy = Policies.byId(row[Sessions.policyVersionId])
        if (now < row[Sessions.startsAt].toInstantK() + policy.graceMinutes.minutes) conflict("Grace period has not passed")
        val revision = row[Sessions.revision] + 1
        Sessions.update({ Sessions.id eq sessionId }) {
            it[status] = SessionStatus.NO_SHOW.name
            it[Sessions.revision] = revision
            it[closedAt] = now.toOffset()
        }
        closeOpenReschedule(sessionId, RescheduleStatus.EXPIRED, null, now)
        attendance(sessionId, "NO_SHOW", trainerId, now)
        val relationshipId = row[Sessions.relationshipId]
        val price = Money(row[Sessions.priceMinor], row[Sessions.currency])
        val exempt = Policies.isExempt(relationshipId)
        val fee = FeeCalculator.noShow(policy.toContract(), price)
        val kind = PaymentKind.valueOf(row[Sessions.paymentKind])
        val usesCredit = policy.packageFeeMode == PackageFeeMode.USE_CREDIT
        Ledger.decideFee(
            sessionId = sessionId, relationshipId = relationshipId, action = FeeAction.NO_SHOW, receivedAt = now,
            policyVersionId = row[Sessions.policyVersionId], feePercent = policy.noShowPercent, quotedMinor = fee.minor,
            currency = fee.currency, waived = false, exempt = exempt, usedFreeCancellation = false, reason = null,
            decidedBy = trainerId, settlement = Ledger.feeSettlement(kind, usesCredit), now = now,
        )
        if (kind == PaymentKind.PACKAGE) {
            // USE_CREDIT: the no-show percent of one session's credits is used; the rest returns to the balance.
            val units = if (exempt || !usesCredit) 0 else policy.noShowPercent * Ledger.UNITS_PER_SESSION / 100
            Ledger.consumeCredit(sessionId, units, now)
        }
        event(sessionId, revision, "NO_SHOW", trainerId, now)
        TrainerOutbox.emit("session.no_show", "session", sessionId, mapOf("relationshipId" to relationshipId), now)
        SessionResultDto(SessionReads.session(sessionId)!!, now)
    }

    // ------------------------------------------------------------------ helpers

    private fun ownSession(trainerId: UUID, sessionId: UUID): ResultRow =
        Sessions.selectAll().where { (Sessions.id eq sessionId) and (Sessions.trainerId eq trainerId) }.singleOrNull()
            ?: notFound("Session not found")

    private fun lockOwnSession(trainerId: UUID, sessionId: UUID): ResultRow =
        Sessions.selectAll().where { (Sessions.id eq sessionId) and (Sessions.trainerId eq trainerId) }.forUpdate().singleOrNull()
            ?: notFound("Session not found")

    /** Serialises one trainer's calendar changes so the availability checks and the insert see the same state. */
    private fun lockTrainer(trainerId: UUID) {
        TrainerProfiles.selectAll().where { TrainerProfiles.userId eq trainerId }.forUpdate().single()
    }

    private fun checkRevision(row: ResultRow, revision: Int) {
        if (row[Sessions.revision] != revision) conflict("Session changed; reload and try again")
    }

    private fun bufferOf(trainerId: UUID) =
        TrainerProfiles.selectAll().where { TrainerProfiles.userId eq trainerId }.single()[TrainerProfiles.sessionBufferMinutes].minutes

    private fun timeZoneOf(trainerId: UUID): String =
        com.trainingapp.server.db.AppUsers.selectAll().where { com.trainingapp.server.db.AppUsers.id eq trainerId }
            .single()[com.trainingapp.server.db.AppUsers.timeZone]

    private fun validateTimes(start: Instant, end: Instant, now: Instant) {
        if (end <= start) invalid("End must be after start")
        val length = end - start
        if (length < MIN_SESSION || length > MAX_SESSION) invalid("Session length must be 15 min – 8 h")
        if (start <= now) invalid("Start must be in the future")
    }

    /** The offer's price, a client price override, or the trainer's first single-session offer. */
    private fun priceFor(trainerId: UUID, relationshipId: UUID, offerId: UUID?): Pair<UUID?, Money> {
        val offer = if (offerId != null) {
            Offers.selectAll().where { (Offers.id eq offerId) and (Offers.trainerId eq trainerId) }.singleOrNull() ?: invalid("Unknown offer")
        } else {
            Offers.selectAll().where { (Offers.trainerId eq trainerId) and (Offers.kind eq "SINGLE_SESSION") and Offers.archivedAt.isNull() }
                .orderBy(Offers.createdAt, SortOrder.ASC).firstOrNull() ?: conflict("Add a price list entry first")
        }
        val id = offer[Offers.id]
        val override = ClientPriceOverrides.selectAll()
            .where { (ClientPriceOverrides.relationshipId eq relationshipId) and (ClientPriceOverrides.offerId eq id) }.singleOrNull()
        val price = override?.let { Money(it[ClientPriceOverrides.priceMinor], it[ClientPriceOverrides.currency]) }
            ?: Money(offer[Offers.priceMinor], offer[Offers.currency])
        return id to price
    }

    /**
     * The late-reschedule fee the client's policy version proposes for [open], computed at the proposal's server
     * receipt time with the shared FeeCalculator. Null when the policy does not treat late reschedules as cancellations,
     * the client is exempt, or the change is not late.
     */
    private fun lateRescheduleFee(row: ResultRow, open: ResultRow): Pair<FeeQuoteDto, Int>? {
        val policy = Policies.byId(row[Sessions.policyVersionId])
        if (!policy.lateRescheduleIsCancel) return null
        if (Policies.isExempt(row[Sessions.relationshipId])) return null
        val quote = FeeCalculator.reschedule(
            policy.toContract(), row[Sessions.startsAt].toInstantK(), open[RescheduleRequests.receivedAt].toInstantK(),
            Money(row[Sessions.priceMinor], row[Sessions.currency]),
        )
        if (!quote.late || quote.amount.isZero) return null
        return FeeQuoteDto(quote.tier?.hoursBefore, quote.policyPercent, quote.amount, quote.usesFreeCancellation) to quote.policyPercent
    }

    /** Moves a confirmed session; the EXCLUDE constraints reject an overlap (reported as CONFLICT). */
    private fun move(row: ResultRow, start: Instant, end: Instant, buffer: kotlin.time.Duration, actor: UUID, now: Instant): Int {
        val sessionId = row[Sessions.id]
        if (!SessionReads.slotFree(row[Sessions.trainerId], start, end + buffer, sessionId)) conflict("Time slot is not free")
        val revision = row[Sessions.revision] + 1
        Sessions.update({ Sessions.id eq sessionId }) {
            it[startsAt] = start.toOffset()
            it[endsAt] = end.toOffset()
            it[blockedUntil] = (end + buffer).toOffset()
            it[Sessions.revision] = revision
        }
        SessionEvents.insert {
            it[SessionEvents.sessionId] = sessionId
            it[SessionEvents.revision] = revision
            it[type] = "MOVED"
            it[actorId] = actor
            it[oldStartsAt] = row[Sessions.startsAt]
            it[oldEndsAt] = row[Sessions.endsAt]
            it[occurredAt] = now.toOffset()
        }
        return revision
    }

    private fun closeOpenReschedule(sessionId: UUID, status: RescheduleStatus, actor: UUID?, now: Instant) {
        RescheduleRequests.update({ (RescheduleRequests.sessionId eq sessionId) and (RescheduleRequests.status inList SessionReads.OPEN_RESCHEDULE) }) {
            it[RescheduleRequests.status] = status.name
            it[decidedBy] = actor
            it[decidedAt] = now.toOffset()
        }
    }

    private fun attendance(sessionId: UUID, outcome: String, actor: UUID, now: Instant) {
        Attendances.insert {
            it[id] = newId()
            it[Attendances.sessionId] = sessionId
            it[Attendances.outcome] = outcome
            it[markedBy] = actor
            it[markedAt] = now.toOffset()
        }
    }
}

/** Appends a session history row (append-only table). */
internal fun event(sessionId: UUID, revision: Int, type: String, actor: UUID?, now: Instant) {
    SessionEvents.insert {
        it[SessionEvents.sessionId] = sessionId
        it[SessionEvents.revision] = revision
        it[SessionEvents.type] = type
        it[actorId] = actor
        it[occurredAt] = now.toOffset()
    }
}

