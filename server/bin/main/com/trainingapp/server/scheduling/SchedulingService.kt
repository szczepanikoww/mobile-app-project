package com.trainingapp.server.scheduling

import com.trainingapp.contract.api.MoneyDto
import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.CancelledBy
import com.trainingapp.contract.policy.FeeCalculator
import com.trainingapp.contract.policy.FeeQuote
import com.trainingapp.contract.schedule.BookingOptionsDto
import com.trainingapp.contract.schedule.BookingRequestDto
import com.trainingapp.contract.schedule.CancelRequestDto
import com.trainingapp.contract.schedule.CancellationQuoteDto
import com.trainingapp.contract.schedule.ChargeKindDto
import com.trainingapp.contract.schedule.ClientScheduleDto
import com.trainingapp.contract.schedule.FeeDto
import com.trainingapp.contract.schedule.PaymentKindDto
import com.trainingapp.contract.schedule.PolicyVersionDto
import com.trainingapp.contract.schedule.ProposalAnswerDto
import com.trainingapp.contract.schedule.ProposalAuthorDto
import com.trainingapp.contract.schedule.ProposalDto
import com.trainingapp.contract.schedule.RescheduleFeePreviewDto
import com.trainingapp.contract.schedule.RescheduleRequestDto
import com.trainingapp.contract.schedule.SessionDto
import com.trainingapp.contract.schedule.SessionPaymentDto
import com.trainingapp.contract.schedule.SlotDto
import com.trainingapp.server.billing.BillingService
import com.trainingapp.server.catalogue.CatalogueService
import com.trainingapp.server.catalogue.PolicyRecord
import com.trainingapp.server.common.ApiException
import com.trainingapp.server.common.DeniedSubject
import com.trainingapp.server.common.ErrorCode
import com.trainingapp.server.common.Ids
import com.trainingapp.server.common.conflict
import com.trainingapp.server.common.dbNow
import com.trainingapp.server.common.invalid
import com.trainingapp.server.common.notFound
import com.trainingapp.server.common.toKotlin
import com.trainingapp.server.common.toOffset
import com.trainingapp.server.db.Db
import com.trainingapp.server.db.SqlState
import com.trainingapp.server.db.attempt
import com.trainingapp.server.db.savepoint
import com.trainingapp.server.db.sqlState
import com.trainingapp.server.identity.CurrentUser
import com.trainingapp.server.identity.IdentityService
import com.trainingapp.server.messaging.MessagingService
import com.trainingapp.server.platform.JobQueue
import com.trainingapp.server.platform.JobQueue.cancel
import com.trainingapp.server.platform.JobQueue.cancelPrefix
import com.trainingapp.server.platform.JobQueue.schedule
import com.trainingapp.server.platform.Outbox.emit
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid

/**
 * The Scheduling module (plan §8.1). The only code that changes `scheduling.session.status`. Every write is one
 * transaction with its session_event, jobs and outbox rows; overlapping confirmed sessions are rejected by the
 * database's exclusion constraints, never by a check in Kotlin alone.
 */
class SchedulingService(
    private val db: Db,
    private val identity: IdentityService,
    private val catalogue: CatalogueService,
    private val billing: BillingService,
    private val messaging: MessagingService,
) {
    companion object {
        /** R4: a booking request expires at the slot start or after 48 h, whichever comes first. */
        val REQUEST_TTL = 48.hours
        val DEFAULT_WINDOW_PAST = 30.days
        val DEFAULT_WINDOW_FUTURE = 90.days
        val REMINDER_BEFORE = 24.hours
        val MIN_DURATION = 15.minutes
        val MAX_DURATION = 240.minutes
    }

    // =============================================================================================================
    // Client reads
    // =============================================================================================================

    suspend fun schedule(client: CurrentUser, from: Instant?, to: Instant?): ClientScheduleDto = db.tx {
        val now = dbNow()
        val start = from ?: (now - DEFAULT_WINDOW_PAST)
        val end = to ?: (now + DEFAULT_WINDOW_FUTURE)
        if (end <= start || end - start > 400.days) throw invalid("from", "to")
        val rows = Sessions.selectAll().where {
            (Sessions.clientId eq client.id) and (Sessions.startsAt greater start.toOffset()) and (Sessions.startsAt less end.toOffset())
        }.orderBy(Sessions.startsAt, SortOrder.ASC).map(SessionRecord::from)
        val relationships = with(identity) { relationshipsOfClient(client.id) }
        val trainerIds = (relationships.map { it.trainerId } + rows.map { it.trainerId }).distinct()
        val refs = with(identity) { refs(trainerIds) }
        ClientScheduleDto(
            trainers = trainerIds.mapNotNull { refs[it] },
            sessions = toDtos(rows),
            serverNow = now,
        )
    }

    suspend fun bookingOptions(client: CurrentUser, trainerId: Uuid): BookingOptionsDto = db.tx {
        val relationship = requireBookableTrainer(client, trainerId)
        val policy = with(catalogue) { currentPolicy(trainerId) } ?: throw conflict(message = "trainer has no cancellation policy")
        val (price, minutes) = bookingPrice(trainerId, relationship?.id)
        BookingOptionsDto(
            trainer = with(identity) { refs(listOf(trainerId)) }.getValue(trainerId),
            policy = policy.toDto(),
            price = price,
            places = with(catalogue) { trainerGyms(trainerId) }.map { it.second },
            timeZone = with(identity) { userTimeZone(trainerId) },
            durationMinutes = minutes,
        )
    }

    /**
     * Free slots on [date] (in each availability rule's own zone, DST-safe): rules minus calendar blocks (times only,
     * never their titles) minus time-blocking sessions with the trainer's buffer, minus the client's own sessions.
     */
    suspend fun availableSlots(client: CurrentUser, trainerId: Uuid, date: LocalDate, excludeSessionId: Uuid?): List<SlotDto> = db.tx {
        val relationship = requireBookableTrainer(client, trainerId)
        val now = dbNow()
        val (_, minutes) = bookingPrice(trainerId, relationship?.id)
        val duration = minutes.minutes
        val buffer = with(identity) { sessionBufferMinutes(trainerId) }.minutes
        val windows = availabilityWindows(trainerId, date)
        if (windows.isEmpty()) return@tx emptyList()
        val dayStart = windows.minOf { it.first } - 1.days
        val dayEnd = windows.maxOf { it.second } + 1.days
        val blocks = blocksBetween(trainerId, dayStart, dayEnd)
        val trainerBusy = Sessions.selectAll().where {
            (Sessions.trainerId eq trainerId) and (Sessions.status inList DbSessionStatus.BLOCKING) and
                (Sessions.startsAt less dayEnd.toOffset()) and (Sessions.blockedUntil greater dayStart.toOffset())
        }.map(SessionRecord::from).filter { it.id != excludeSessionId }.map { it.startsAt to it.blockedUntil }
        val clientBusy = Sessions.selectAll().where {
            (Sessions.clientId eq client.id) and (Sessions.status inList DbSessionStatus.BLOCKING) and
                (Sessions.startsAt less dayEnd.toOffset()) and (Sessions.endsAt greater dayStart.toOffset())
        }.map(SessionRecord::from).filter { it.id != excludeSessionId }.map { it.startsAt to it.endsAt }
        windows.flatMap { (windowStart, windowEnd) ->
            generateSequence(windowStart) { it + duration }.takeWhile { it + duration <= windowEnd }.toList()
        }.filter { start ->
            val end = start + duration
            start > now &&
                blocks.none { overlaps(start, end, it.first, it.second) } &&
                trainerBusy.none { overlaps(start, end + buffer, it.first, it.second) } &&
                clientBusy.none { overlaps(start, end, it.first, it.second) }
        }.distinct().sorted().map { SlotDto(it, it + duration) }
    }

    suspend fun policy(user: CurrentUser, versionId: Uuid): PolicyVersionDto = db.tx {
        val record = with(catalogue) { policy(versionId) } ?: throw notFound("policy")
        val trainerId = Uuid.parse(record.policy.trainerId)
        val allowed = Sessions.selectAll().where {
            (Sessions.policyVersionId eq versionId) and ((Sessions.clientId eq user.id) or (Sessions.trainerId eq user.id))
        }.any() || trainerId == user.id ||
            (with(catalogue) { isPublicTrainer(trainerId) } && with(catalogue) { currentPolicy(trainerId) }?.id == versionId)
        if (!allowed) throw notFound("policy", DeniedSubject("policy_version", versionId))
        record.toDto()
    }

    // =============================================================================================================
    // Client commands
    // =============================================================================================================

    /** One transaction: policy check, relationship (R1), the REQUESTED session, its event, expiry job and outbox. */
    suspend fun requestBooking(client: CurrentUser, request: BookingRequestDto, operationId: Uuid): SessionDto = db.tx {
        existingByOperation(client, operationId)?.let { return@tx toDto(it) }
        val trainerId = runCatching { Uuid.parse(request.trainerId) }.getOrNull() ?: throw invalid("trainerId")
        if (trainerId == client.id) throw invalid("trainerId")
        val now = dbNow()
        validateTimes(request.start, request.end, now)
        requireBookableTrainer(client, trainerId)
        val policy = with(catalogue) { currentPolicy(trainerId) } ?: throw conflict(message = "trainer has no cancellation policy")
        if (policy.policy.versionId != request.acceptedPolicyVersionId) {
            throw ApiException(409, ErrorCode.POLICY_OUTDATED, "policy version is not the current one")
        }
        val relationship = with(identity) { openOrCreatePending(trainerId, client.id) }
        val offer = with(catalogue) { defaultSessionOffer(trainerId) }
        val (price, _) = bookingPrice(trainerId, relationship.id)
        val paymentKind = when (request.paymentKind) {
            PaymentKindDto.PACKAGE -> if (with(billing) { hasFreeCredit(relationship.id) }) "PACKAGE" else throw invalid("paymentKind")
            PaymentKindDto.CASH -> "CASH"
            PaymentKindDto.ONLINE -> "ONLINE"
            null -> if (with(billing) { hasFreeCredit(relationship.id) }) "PACKAGE" else "ONLINE"
        }
        val (gymId, placeNote) = resolvePlace(trainerId, request.place)
        val buffer = with(identity) { sessionBufferMinutes(trainerId) }.minutes
        val id = Ids.v7()
        val inserted = attempt(SqlState.UNIQUE_VIOLATION) {
            Sessions.insert {
                it[Sessions.id] = id
                it[relationshipId] = relationship.id
                it[Sessions.trainerId] = trainerId
                it[clientId] = client.id
                it[offerId] = offer?.get(com.trainingapp.server.catalogue.Offers.id)
                it[status] = DbSessionStatus.REQUESTED
                it[startsAt] = request.start.toOffset()
                it[endsAt] = request.end.toOffset()
                it[blockedUntil] = (request.end + buffer).toOffset()
                it[timeZone] = with(identity) { userTimeZone(trainerId) }
                it[Sessions.gymId] = gymId
                it[Sessions.placeNote] = placeNote
                it[priceMinor] = price.minor
                it[currency] = price.currency
                it[Sessions.paymentKind] = paymentKind
                it[policyVersionId] = policy.id
                it[policyAcceptedAt] = now.toOffset()
                it[revision] = 1
                it[createdBy] = client.id
                it[Sessions.operationId] = operationId
                it[requestedAt] = now.toOffset()
            }
        }
        // A concurrent retry with the same key committed first: return its session.
        if (inserted == null) return@tx toDto(existingByOperation(client, operationId) ?: throw conflict(message = "duplicate booking"))
        event(id, 1, "REQUESTED", client.id)
        val expiresAt = minOf(request.start, now + REQUEST_TTL)
        schedule(JobQueue.REQUEST_EXPIRY, "request-expiry:$id", expiresAt, mapOf("sessionId" to id))
        emit("session", id, "session.requested", mapOf("trainerId" to trainerId, "clientId" to client.id))
        toDto(load(id))
    }

    /** Withdraws a booking request, or the client's own open reschedule request. */
    suspend fun withdraw(client: CurrentUser, sessionId: Uuid, revision: Int): SessionDto = db.tx {
        val session = clientSessionForUpdate(client, sessionId, revision)
        val now = dbNow()
        when {
            session.status == DbSessionStatus.REQUESTED -> {
                bump(session, now) {
                    it[status] = DbSessionStatus.CANCELLED_BY_CLIENT
                    it[closedAt] = now.toOffset()
                }
                event(session.id, session.revision + 1, "CANCELLED", client.id)
                cancel("request-expiry:${session.id}")
                emit("session", session.id, "session.withdrawn", mapOf("trainerId" to session.trainerId, "clientId" to client.id))
            }
            else -> {
                val open = openReschedule(session.id)
                if (session.status != DbSessionStatus.CONFIRMED || open == null || open[RescheduleRequests.proposerRole] != "CLIENT") {
                    throw conflict(message = "nothing to withdraw")
                }
                RescheduleRequests.update({ RescheduleRequests.id eq open[RescheduleRequests.id] }) {
                    it[status] = RescheduleStatus.WITHDRAWN
                    it[decidedBy] = client.id
                    it[decidedAt] = now.toOffset()
                }
                bump(session, now) {}
                cancel("reschedule-expiry:${open[RescheduleRequests.id]}")
                emit("session", session.id, "reschedule.withdrawn", mapOf("trainerId" to session.trainerId, "clientId" to client.id))
            }
        }
        toDto(load(session.id))
    }

    /** The late-reschedule fee the policy proposes, or null when moving is free or not treated as a cancellation. */
    suspend fun previewRescheduleFee(client: CurrentUser, sessionId: Uuid, revision: Int): RescheduleFeePreviewDto? = db.tx {
        val session = clientSession(client, sessionId)
        if (session.revision != revision) throw conflict(ErrorCode.REVISION_CONFLICT, "session changed")
        val (fee, policy, _) = rescheduleFee(session, dbNow()) ?: return@tx null
        RescheduleFeePreviewDto(fee.toDto(), policy.policy.versionId)
    }

    /**
     * Creates a PENDING reschedule request. The session keeps its status and time until the request is accepted;
     * only its revision moves, so stale quotes and answers are refused. The trainer decides any fee (D8).
     */
    suspend fun requestReschedule(client: CurrentUser, sessionId: Uuid, request: RescheduleRequestDto): SessionDto = db.tx {
        val operationId = runCatching { Uuid.parse(request.operationId) }.getOrNull() ?: throw invalid("operationId")
        RescheduleRequests.selectAll().where { RescheduleRequests.operationId eq operationId }.singleOrNull()?.let { existing ->
            if (existing[RescheduleRequests.sessionId] != sessionId) throw ApiException(422, ErrorCode.IDEMPOTENCY_MISMATCH, "key reused")
            return@tx toDto(clientSession(client, sessionId))
        }
        val session = clientSessionForUpdate(client, sessionId, request.revision)
        if (session.status != DbSessionStatus.CONFIRMED) throw conflict(message = "only confirmed sessions can be moved")
        val now = dbNow()
        validateTimes(request.start, request.end, now)
        val quoteId = rescheduleFee(session, now)?.let { (fee, policy, _) ->
            with(billing) { insertQuote(session, session.revision + 1, "LATE_RESCHEDULE", policy.id, now, fee, now + BillingService.QUOTE_VALIDITY) }
        }
        val requestId = Ids.v7()
        val created = attempt(SqlState.UNIQUE_VIOLATION) {
            RescheduleRequests.insert {
                it[id] = requestId
                it[RescheduleRequests.sessionId] = session.id
                it[baseRevision] = session.revision + 1
                it[proposedBy] = client.id
                it[proposerRole] = "CLIENT"
                it[proposedStartsAt] = request.start.toOffset()
                it[proposedEndsAt] = request.end.toOffset()
                it[status] = RescheduleStatus.PENDING
                it[receivedAt] = now.toOffset()
                it[feeQuoteId] = quoteId
                it[RescheduleRequests.operationId] = operationId
            }
        }
        if (created == null) throw conflict(message = "a reschedule request is already open") // reschedule_one_open
        bump(session, now) {}
        schedule(JobQueue.RESCHEDULE_EXPIRY, "reschedule-expiry:$requestId", minOf(session.startsAt, request.start), mapOf("requestId" to requestId))
        emit("session", session.id, "reschedule.requested", mapOf("trainerId" to session.trainerId, "clientId" to client.id))
        toDto(load(session.id))
    }

    /** The client's answer to the trainer's proposal. Accepting moves the session in one locked transaction. */
    suspend fun answerProposal(client: CurrentUser, sessionId: Uuid, answer: ProposalAnswerDto): SessionDto = db.tx {
        val session = clientSession(client, sessionId)
        val open = openReschedule(session.id)
        if (open == null || open[RescheduleRequests.proposerRole] != "TRAINER") throw conflict(message = "no proposal to answer")
        if (answer.accept) {
            acceptReschedule(session.trainerId, session.id, open[RescheduleRequests.id], answer.revision, client.id)
        } else {
            val locked = clientSessionForUpdate(client, sessionId, answer.revision)
            val now = dbNow()
            decideReschedule(open[RescheduleRequests.id], RescheduleStatus.REJECTED, client.id, now)
            bump(locked, now) {}
            emit("session", session.id, "reschedule.rejected", mapOf("trainerId" to session.trainerId, "clientId" to client.id))
        }
        toDto(load(session.id))
    }

    /** A fresh quote for cancelling this revision, valid for R8 (5 minutes). Nothing changes on the session. */
    suspend fun quoteCancellation(client: CurrentUser, sessionId: Uuid, revision: Int): CancellationQuoteDto = db.tx {
        val session = clientSession(client, sessionId)
        if (session.revision != revision) throw conflict(ErrorCode.REVISION_CONFLICT, "session changed")
        if (session.status != DbSessionStatus.CONFIRMED) throw conflict(message = "only confirmed sessions can be cancelled")
        val now = dbNow()
        val effective = effectivePolicy(session)
        val freeLeft = (effective.policy.policy.freeLateCancellationsPerMonth -
            with(billing) { freeCancellationsUsed(session.relationshipId, session.timeZone, now) }).coerceAtLeast(0)
        val fee = if (effective.exempt) FeeQuote(null, 0, Money.zero(session.price.currency), false) else FeeCalculator.cancellation(
            effective.policy.policy, session.startsAt, now, Money(session.price.minor, session.price.currency), freeLeft, CancelledBy.Client,
        )
        val expiresAt = now + BillingService.QUOTE_VALIDITY
        val quoteId = with(billing) { insertQuote(session, session.revision, "CLIENT_CANCEL", effective.policy.id, now, fee, expiresAt) }
        CancellationQuoteDto(
            quoteId = quoteId.toString(),
            sessionId = session.id.toString(),
            revision = session.revision,
            fee = fee.toDto(),
            policyVersionId = effective.policy.policy.versionId,
            freeLateCancellationsLeft = freeLeft,
            chargeKind = when (session.paymentKind) { "PACKAGE" -> ChargeKindDto.PACKAGE_CREDIT; "CASH" -> ChargeKindDto.CASH; else -> ChargeKindDto.ONLINE },
            exempt = effective.exempt,
            quotedAt = now,
            expiresAt = expiresAt,
        )
    }

    /** One transaction: quote checks, the cancellation, the fee decision and its charge or credit, jobs and outbox. */
    suspend fun cancel(client: CurrentUser, sessionId: Uuid, request: CancelRequestDto): SessionDto = db.tx {
        val operationId = runCatching { Uuid.parse(request.operationId) }.getOrNull() ?: throw invalid("operationId")
        with(billing) { decisionExists(operationId) }?.let { decided ->
            if (decided != sessionId) throw ApiException(422, ErrorCode.IDEMPOTENCY_MISMATCH, "key reused")
            return@tx toDto(clientSession(client, sessionId))
        }
        val session = clientSessionForUpdate(client, sessionId, request.revision)
        if (session.status != DbSessionStatus.CONFIRMED) throw conflict(message = "only confirmed sessions can be cancelled")
        val quoteId = runCatching { Uuid.parse(request.quoteId) }.getOrNull() ?: throw invalid("quoteId")
        val quote = with(billing) { quote(quoteId) }
        if (quote == null || quote.sessionId != session.id || quote.action != "CLIENT_CANCEL") throw invalid("quoteId")
        if (quote.sessionRevision != session.revision) throw conflict(ErrorCode.REVISION_CONFLICT, "quote is for another revision")
        val now = dbNow()
        if (quote.expiresAt <= now) throw ApiException(410, ErrorCode.QUOTE_EXPIRED, "quote expired")
        bump(session, now) {
            it[status] = DbSessionStatus.CANCELLED_BY_CLIENT
            it[closedAt] = now.toOffset()
        }
        event(session.id, session.revision + 1, "CANCELLED", client.id)
        closeOpenReschedule(session.id, client.id, now)
        val exempt = effectivePolicy(session).exempt
        with(billing) { decideClientCancel(session, quote, exempt, operationId, now) }
        cancelPrefix("reminder:${session.id}:")
        emit("session", session.id, "session.cancelled", mapOf("trainerId" to session.trainerId, "clientId" to client.id))
        toDto(load(session.id))
    }

    // =============================================================================================================
    // Trainer transitions the client flow depends on (plan §8.7) — reached through /v1/dev in the local profile
    // until the trainer API exists. They run through this code, never through raw SQL.
    // =============================================================================================================

    suspend fun confirm(trainerId: Uuid, sessionId: Uuid): SessionDto = db.tx {
        with(identity) { lockTrainer(trainerId) }
        val session = trainerSessionForUpdate(trainerId, sessionId)
        if (session.status != DbSessionStatus.REQUESTED) throw conflict(message = "not a booking request")
        val now = dbNow()
        if (!isWithinAvailability(trainerId, session.startsAt, session.endsAt)) throw ApiException(409, ErrorCode.SLOT_TAKEN, "outside availability")
        val buffer = with(identity) { sessionBufferMinutes(trainerId) }.minutes
        slotTaken {
            bump(session, now) {
                it[status] = DbSessionStatus.CONFIRMED
                it[confirmedAt] = now.toOffset()
                it[blockedUntil] = (session.endsAt + buffer).toOffset()
            }
        }
        event(session.id, session.revision + 1, "CONFIRMED", trainerId)
        if (with(identity) { activate(session.relationshipId) }) {
            with(messaging) { ensureConversation(session.relationshipId) }
        }
        if (session.paymentKind == "PACKAGE" && !with(billing) { reserveCredit(session) }) {
            throw conflict(message = "no package credit left")
        }
        cancel("request-expiry:${session.id}")
        scheduleReminder(session.id, session.revision + 1, session.startsAt, now)
        emit("session", session.id, "session.confirmed", mapOf("trainerId" to trainerId, "clientId" to session.clientId))
        toDto(load(session.id))
    }

    suspend fun reject(trainerId: Uuid, sessionId: Uuid): SessionDto = db.tx {
        val session = trainerSessionForUpdate(trainerId, sessionId)
        if (session.status != DbSessionStatus.REQUESTED) throw conflict(message = "not a booking request")
        val now = dbNow()
        bump(session, now) {
            it[status] = DbSessionStatus.REJECTED
            it[closedAt] = now.toOffset()
        }
        event(session.id, session.revision + 1, "REJECTED", trainerId)
        cancel("request-expiry:${session.id}")
        emit("session", session.id, "session.rejected", mapOf("trainerId" to trainerId, "clientId" to session.clientId))
        toDto(load(session.id))
    }

    suspend fun propose(trainerId: Uuid, sessionId: Uuid, start: Instant, end: Instant, operationId: Uuid): SessionDto = db.tx {
        val session = trainerSessionForUpdate(trainerId, sessionId)
        if (session.status != DbSessionStatus.CONFIRMED) throw conflict(message = "only confirmed sessions can be moved")
        val now = dbNow()
        validateTimes(start, end, now)
        val requestId = Ids.v7()
        attempt(SqlState.UNIQUE_VIOLATION) {
            RescheduleRequests.insert {
                it[id] = requestId
                it[RescheduleRequests.sessionId] = session.id
                it[baseRevision] = session.revision + 1
                it[proposedBy] = trainerId
                it[proposerRole] = "TRAINER"
                it[proposedStartsAt] = start.toOffset()
                it[proposedEndsAt] = end.toOffset()
                it[status] = RescheduleStatus.PENDING
                it[receivedAt] = now.toOffset()
                it[RescheduleRequests.operationId] = operationId
            }
        } ?: throw conflict(message = "a reschedule request is already open")
        bump(session, now) {}
        schedule(JobQueue.RESCHEDULE_EXPIRY, "reschedule-expiry:$requestId", minOf(session.startsAt, start), mapOf("requestId" to requestId))
        emit("session", session.id, "reschedule.proposed", mapOf("trainerId" to trainerId, "clientId" to session.clientId))
        toDto(load(session.id))
    }

    /** The trainer accepts the client's reschedule request, charging or waiving a proposed late fee. */
    suspend fun acceptClientReschedule(trainerId: Uuid, sessionId: Uuid, chargeFee: Boolean): SessionDto = db.tx {
        val session = load(sessionId)
        if (session.trainerId != trainerId) throw notFound("session")
        val open = openReschedule(sessionId)
        if (open == null || open[RescheduleRequests.proposerRole] != "CLIENT") throw conflict(message = "no client request")
        acceptReschedule(trainerId, sessionId, open[RescheduleRequests.id], session.revision, trainerId)
        open[RescheduleRequests.feeQuoteId]?.let { quoteId ->
            val quote = with(billing) { quote(quoteId) }!!
            with(billing) { decideReschedule(load(sessionId), quote, chargeFee, trainerId, Ids.derived(open[RescheduleRequests.id], "fee"), dbNow()) }
        }
        toDto(load(sessionId))
    }

    suspend fun cancelByTrainer(trainerId: Uuid, sessionId: Uuid, operationId: Uuid): SessionDto = db.tx {
        with(billing) { decisionExists(operationId) }?.let { return@tx toDto(load(it)) }
        val session = trainerSessionForUpdate(trainerId, sessionId)
        if (session.status != DbSessionStatus.CONFIRMED && session.status != DbSessionStatus.REQUESTED) throw conflict(message = "not cancellable")
        val now = dbNow()
        bump(session, now) {
            it[status] = DbSessionStatus.CANCELLED_BY_TRAINER
            it[closedAt] = now.toOffset()
        }
        event(session.id, session.revision + 1, "CANCELLED", trainerId)
        closeOpenReschedule(session.id, trainerId, now)
        with(billing) { decideTrainerCancel(session, trainerId, operationId, now) }
        cancel("request-expiry:${session.id}")
        cancelPrefix("reminder:${session.id}:")
        emit("session", session.id, "session.cancelled", mapOf("trainerId" to trainerId, "clientId" to session.clientId))
        toDto(load(session.id))
    }

    /** Check-in: attendance row, COMPLETED, and the session's charge or package credit (R10). */
    suspend fun checkIn(trainerId: Uuid, sessionId: Uuid): SessionDto = db.tx {
        val session = trainerSessionForUpdate(trainerId, sessionId)
        if (session.status != DbSessionStatus.CONFIRMED) throw conflict(message = "only confirmed sessions can be checked in")
        val now = dbNow()
        attendance(session.id, "ATTENDED", trainerId)
        bump(session, now) {
            it[status] = DbSessionStatus.COMPLETED
            it[closedAt] = now.toOffset()
        }
        event(session.id, session.revision + 1, "CHECKED_IN", trainerId)
        closeOpenReschedule(session.id, trainerId, now)
        with(billing) { chargeCompletedSession(session, now) }
        cancelPrefix("reminder:${session.id}:")
        emit("session", session.id, "session.completed", mapOf("trainerId" to trainerId, "clientId" to session.clientId))
        toDto(load(session.id))
    }

    /** No-show after start + grace: attendance, NO_SHOW, and the policy's no-show fee (credit or charge). */
    suspend fun noShow(trainerId: Uuid, sessionId: Uuid): SessionDto = db.tx {
        val session = trainerSessionForUpdate(trainerId, sessionId)
        if (session.status != DbSessionStatus.CONFIRMED) throw conflict(message = "only confirmed sessions")
        val now = dbNow()
        val effective = effectivePolicy(session)
        if (now < session.startsAt + effective.policy.policy.graceMinutes.minutes) throw conflict(message = "grace period not over")
        attendance(session.id, "NO_SHOW", trainerId)
        bump(session, now) {
            it[status] = DbSessionStatus.NO_SHOW
            it[closedAt] = now.toOffset()
        }
        event(session.id, session.revision + 1, "NO_SHOW", trainerId)
        val percent = if (effective.exempt) 0 else effective.policy.policy.noShowPercent
        val fee = Money(session.price.minor, session.price.currency).percentOf(percent)
        val quote = FeeQuote(null, percent, fee, false)
        val quoteId = with(billing) { insertQuote(session, session.revision + 1, "NO_SHOW", effective.policy.id, now, quote, now) }
        with(billing) { decideNoShow(session, quoteId, effective.policy.id, percent, MoneyDto(fee.minor, fee.currency), trainerId, Ids.derived(session.id, "no-show"), now) }
        cancelPrefix("reminder:${session.id}:")
        emit("session", session.id, "session.no_show", mapOf("trainerId" to trainerId, "clientId" to session.clientId))
        toDto(load(session.id))
    }

    // =============================================================================================================
    // Worker jobs
    // =============================================================================================================

    /** `request-expiry:<session>`: REQUESTED → EXPIRED, by the system (actor null). */
    suspend fun expireRequest(sessionId: Uuid) = db.tx {
        val session = Sessions.selectAll().where { Sessions.id eq sessionId }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
            ?.let(SessionRecord::from) ?: return@tx
        if (session.status != DbSessionStatus.REQUESTED) return@tx
        val now = dbNow()
        bump(session, now) {
            it[status] = DbSessionStatus.EXPIRED
            it[closedAt] = now.toOffset()
        }
        event(session.id, session.revision + 1, "EXPIRED", null)
        emit("session", session.id, "session.expired", mapOf("trainerId" to session.trainerId, "clientId" to session.clientId))
    }

    suspend fun expireReschedule(requestId: Uuid) = db.tx {
        val request = RescheduleRequests.selectAll().where { RescheduleRequests.id eq requestId }.singleOrNull() ?: return@tx
        if (request[RescheduleRequests.status] !in RescheduleStatus.OPEN) return@tx
        val session = Sessions.selectAll().where { Sessions.id eq request[RescheduleRequests.sessionId] }
            .forUpdate(ForUpdateOption.ForUpdate).single().let(SessionRecord::from)
        val now = dbNow()
        decideReschedule(requestId, RescheduleStatus.EXPIRED, null, now)
        bump(session, now) {}
        emit("session", session.id, "reschedule.expired", mapOf("trainerId" to session.trainerId, "clientId" to session.clientId))
    }

    /** Whether the reminder for (session, revision) is still due: a moved or cancelled session never gets it. */
    suspend fun reminderStillValid(sessionId: Uuid, revision: Int): SessionRecord? = db.tx {
        Sessions.selectAll().where { Sessions.id eq sessionId }.singleOrNull()?.let(SessionRecord::from)
            ?.takeIf { it.revision == revision && it.status == DbSessionStatus.CONFIRMED }
    }

    // =============================================================================================================
    // Internals
    // =============================================================================================================

    /**
     * Accepting a reschedule (database-design §3): lock trainer → lock session → revision and base revision →
     * availability rules and blocks in code → move (exclusion constraints reject a clash: original time unchanged) →
     * request ACCEPTED → MOVED event → reminders of the old revision replaced → outbox.
     */
    private fun JdbcTransaction.acceptReschedule(trainerId: Uuid, sessionId: Uuid, requestId: Uuid, revision: Int, actorId: Uuid) {
        with(identity) { lockTrainer(trainerId) }
        val session = Sessions.selectAll().where { Sessions.id eq sessionId }.forUpdate(ForUpdateOption.ForUpdate).single().let(SessionRecord::from)
        val request = RescheduleRequests.selectAll().where { RescheduleRequests.id eq requestId }.single()
        if (session.revision != revision || request[RescheduleRequests.baseRevision] != session.revision) {
            throw conflict(ErrorCode.REVISION_CONFLICT, "session changed")
        }
        if (session.status != DbSessionStatus.CONFIRMED || request[RescheduleRequests.status] !in RescheduleStatus.OPEN) {
            throw conflict(message = "request no longer open")
        }
        val start = request[RescheduleRequests.proposedStartsAt].toKotlin()
        val end = request[RescheduleRequests.proposedEndsAt].toKotlin()
        val now = dbNow()
        if (start <= now) throw conflict(message = "proposed time has passed")
        if (!isWithinAvailability(trainerId, start, end, ignoreSession = session.id)) throw ApiException(409, ErrorCode.SLOT_TAKEN, "time is no longer free")
        val buffer = with(identity) { sessionBufferMinutes(trainerId) }.minutes
        slotTaken {
            bump(session, now) {
                it[startsAt] = start.toOffset()
                it[endsAt] = end.toOffset()
                it[blockedUntil] = (end + buffer).toOffset()
            }
        }
        decideReschedule(requestId, RescheduleStatus.ACCEPTED, actorId, now)
        SessionEvents.insert {
            it[SessionEvents.sessionId] = session.id
            it[SessionEvents.revision] = session.revision + 1
            it[type] = "MOVED"
            it[SessionEvents.actorId] = actorId
            it[oldStartsAt] = session.startsAt.toOffset()
            it[oldEndsAt] = session.endsAt.toOffset()
        }
        cancel("reschedule-expiry:$requestId")
        cancelPrefix("reminder:${session.id}:")
        scheduleReminder(session.id, session.revision + 1, start, now)
        emit("session", session.id, "session.moved", mapOf("trainerId" to trainerId, "clientId" to session.clientId))
    }

    /** Runs a session update that the exclusion constraints may reject; `23P01` → `409 SLOT_TAKEN`. */
    private fun JdbcTransaction.slotTaken(block: () -> Unit) {
        try {
            savepoint("slot") { block() }
        } catch (e: Exception) {
            if (e.sqlState() == SqlState.EXCLUSION_VIOLATION) throw ApiException(409, ErrorCode.SLOT_TAKEN, "time is no longer free")
            throw e
        }
    }

    private data class EffectivePolicy(val policy: PolicyRecord, val exempt: Boolean)

    /** The session's accepted version, or the relationship's override (`EXEMPT` → no fees, `OTHER_POLICY`). */
    private fun JdbcTransaction.effectivePolicy(session: SessionRecord): EffectivePolicy {
        val override = with(catalogue) { policyOverride(session.relationshipId) }
        val versionId = if (override?.first == "OTHER_POLICY") override.second!! else session.policyVersionId
        return EffectivePolicy(with(catalogue) { policy(versionId) }!!, exempt = override?.first == "EXEMPT")
    }

    private fun JdbcTransaction.rescheduleFee(session: SessionRecord, now: Instant): Triple<FeeQuote, PolicyRecord, Boolean>? {
        val effective = effectivePolicy(session)
        if (effective.exempt || !effective.policy.lateRescheduleIsCancel) return null
        val fee = FeeCalculator.reschedule(effective.policy.policy, session.startsAt, now, Money(session.price.minor, session.price.currency))
        if (fee.free) return null
        return Triple(fee, effective.policy, false)
    }

    /** A trainer the client may book: public, or one they already have a relationship with. */
    private fun JdbcTransaction.requireBookableTrainer(client: CurrentUser, trainerId: Uuid): com.trainingapp.server.identity.Relationship? {
        val relationship = with(identity) { openRelationship(trainerId, client.id) }
        if (relationship == null && !with(catalogue) { isPublicTrainer(trainerId) }) throw notFound("trainer")
        return relationship
    }

    /** Price snapshot: the relationship's override for the default offer, else the cheapest public single session. */
    private fun JdbcTransaction.bookingPrice(trainerId: Uuid, relationshipId: Uuid?): Pair<MoneyDto, Int> {
        val offer = with(catalogue) { defaultSessionOffer(trainerId) } ?: throw conflict(message = "trainer has no bookable offer")
        val offerId = offer[com.trainingapp.server.catalogue.Offers.id]
        val override = relationshipId?.let { with(catalogue) { priceOverride(it, offerId) } }
        val price = override ?: MoneyDto(offer[com.trainingapp.server.catalogue.Offers.priceMinor], offer[com.trainingapp.server.catalogue.Offers.currency])
        return price to offer[com.trainingapp.server.catalogue.Offers.durationMinutes]
    }

    private fun JdbcTransaction.resolvePlace(trainerId: Uuid, place: String): Pair<Uuid?, String?> {
        val trimmed = place.trim()
        if (trimmed.isEmpty() || trimmed.length > 200) throw invalid("place")
        val gym = with(catalogue) { trainerGyms(trainerId) }.firstOrNull { it.second.equals(trimmed, ignoreCase = true) }
        return if (gym != null) gym.first to null else null to trimmed
    }

    private fun validateTimes(start: Instant, end: Instant, now: Instant) {
        if (end <= start || end - start < MIN_DURATION || end - start > MAX_DURATION) throw invalid("start", "end")
        if (start <= now) throw invalid("start")
    }

    /** The rule windows of [date] as instants, each expanded in its rule's own zone (DST-safe). */
    private fun JdbcTransaction.availabilityWindows(trainerId: Uuid, date: LocalDate): List<Pair<Instant, Instant>> =
        AvailabilityRules.selectAll().where { (AvailabilityRules.trainerId eq trainerId) and (AvailabilityRules.weekday eq date.dayOfWeek.value.toShort()) }
            .filter { row -> !date.isBefore(row[AvailabilityRules.validFrom]) && (row[AvailabilityRules.validTo]?.let { !date.isAfter(it) } ?: true) }
            .map { row ->
                val zone = ZoneId.of(row[AvailabilityRules.timeZone])
                date.atTime(row[AvailabilityRules.startTime]).atZone(zone).toInstant().toKotlinInstant() to
                    date.atTime(row[AvailabilityRules.endTime]).atZone(zone).toInstant().toKotlinInstant()
            }

    private fun JdbcTransaction.blocksBetween(trainerId: Uuid, from: Instant, to: Instant): List<Pair<Instant, Instant>> =
        CalendarBlocks.selectAll().where {
            (CalendarBlocks.trainerId eq trainerId) and CalendarBlocks.deletedAt.isNull() and
                (CalendarBlocks.startsAt less to.toOffset()) and (CalendarBlocks.endsAt greater from.toOffset())
        }.map { it[CalendarBlocks.startsAt].toKotlin() to it[CalendarBlocks.endsAt].toKotlin() }

    /**
     * Availability rules and calendar blocks cannot be part of an exclusion constraint, so they are checked here,
     * after the trainer row is locked (database-design §3). Session overlaps are left to the constraints.
     */
    private fun JdbcTransaction.isWithinAvailability(trainerId: Uuid, start: Instant, end: Instant, ignoreSession: Uuid? = null): Boolean {
        val zones = AvailabilityRules.selectAll().where { AvailabilityRules.trainerId eq trainerId }.map { it[AvailabilityRules.timeZone] }.distinct()
        val dates = zones.flatMap { zone ->
            val z = ZoneId.of(zone)
            listOf(start.toJavaInstant().atZone(z).toLocalDate(), end.toJavaInstant().atZone(z).toLocalDate())
        }.distinct()
        val inRule = dates.flatMap { availabilityWindows(trainerId, it) }.any { (from, to) -> start >= from && end <= to }
        return inRule && blocksBetween(trainerId, start, end).isEmpty()
    }

    private fun overlaps(aStart: Instant, aEnd: Instant, bStart: Instant, bEnd: Instant) = aStart < bEnd && bStart < aEnd

    private fun JdbcTransaction.scheduleReminder(sessionId: Uuid, revision: Int, start: Instant, now: Instant) {
        val at = start - REMINDER_BEFORE
        if (at > now) schedule(JobQueue.REMINDER, "reminder:$sessionId:$revision:24h", at, mapOf("sessionId" to sessionId, "revision" to revision))
    }

    private fun JdbcTransaction.load(id: Uuid): SessionRecord =
        Sessions.selectAll().where { Sessions.id eq id }.single().let(SessionRecord::from)

    private fun JdbcTransaction.existingByOperation(client: CurrentUser, operationId: Uuid): SessionRecord? {
        val row = Sessions.selectAll().where { Sessions.operationId eq operationId }.singleOrNull()?.let(SessionRecord::from) ?: return null
        if (row.clientId != client.id) throw ApiException(422, ErrorCode.IDEMPOTENCY_MISMATCH, "key reused")
        return row
    }

    /** The caller's session; another client's session is `404` and an audited denial (acceptance test 4). */
    private fun JdbcTransaction.clientSession(client: CurrentUser, id: Uuid): SessionRecord {
        val session = Sessions.selectAll().where { Sessions.id eq id }.singleOrNull()?.let(SessionRecord::from) ?: throw notFound("session")
        if (session.clientId != client.id) throw notFound("session", DeniedSubject("session", id, session.relationshipId))
        return session
    }

    private fun JdbcTransaction.clientSessionForUpdate(client: CurrentUser, id: Uuid, revision: Int): SessionRecord {
        val session = Sessions.selectAll().where { Sessions.id eq id }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
            ?.let(SessionRecord::from) ?: throw notFound("session")
        if (session.clientId != client.id) throw notFound("session", DeniedSubject("session", id, session.relationshipId))
        if (session.revision != revision) throw conflict(ErrorCode.REVISION_CONFLICT, "session changed")
        return session
    }

    private fun JdbcTransaction.trainerSessionForUpdate(trainerId: Uuid, id: Uuid): SessionRecord {
        val session = Sessions.selectAll().where { Sessions.id eq id }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
            ?.let(SessionRecord::from) ?: throw notFound("session")
        if (session.trainerId != trainerId) throw notFound("session", DeniedSubject("session", id, session.relationshipId))
        return session
    }

    /** `revision` is checked in SQL (plan §3 rule 4): 0 rows → someone else changed the session. */
    private fun JdbcTransaction.bump(
        session: SessionRecord,
        @Suppress("UNUSED_PARAMETER") now: Instant,
        body: Sessions.(org.jetbrains.exposed.v1.core.statements.UpdateStatement) -> Unit,
    ) {
        val updated = Sessions.update({ (Sessions.id eq session.id) and (Sessions.revision eq session.revision) }) {
            body(this, it)
            it[revision] = session.revision + 1
        }
        if (updated == 0) throw conflict(ErrorCode.REVISION_CONFLICT, "session changed")
    }

    private fun JdbcTransaction.event(sessionId: Uuid, revision: Int, type: String, actorId: Uuid?) {
        SessionEvents.insert {
            it[SessionEvents.sessionId] = sessionId
            it[SessionEvents.revision] = revision
            it[SessionEvents.type] = type
            it[SessionEvents.actorId] = actorId
        }
    }

    private fun JdbcTransaction.attendance(sessionId: Uuid, outcome: String, by: Uuid) {
        Attendances.insert {
            it[id] = Ids.v7()
            it[Attendances.sessionId] = sessionId
            it[Attendances.outcome] = outcome
            it[markedBy] = by
        }
    }

    private fun JdbcTransaction.openReschedule(sessionId: Uuid) =
        RescheduleRequests.selectAll().where { (RescheduleRequests.sessionId eq sessionId) and (RescheduleRequests.status inList RescheduleStatus.OPEN) }
            .singleOrNull()

    private fun JdbcTransaction.decideReschedule(requestId: Uuid, status: String, by: Uuid?, now: Instant) {
        RescheduleRequests.update({ RescheduleRequests.id eq requestId }) {
            it[RescheduleRequests.status] = status
            it[decidedBy] = by
            it[decidedAt] = now.toOffset()
        }
    }

    /** A cancelled or completed session closes its open reschedule request (the client's withdrawn, the trainer's rejected). */
    private fun JdbcTransaction.closeOpenReschedule(sessionId: Uuid, by: Uuid, now: Instant) {
        val open = openReschedule(sessionId) ?: return
        val status = if (open[RescheduleRequests.proposerRole] == "CLIENT") RescheduleStatus.WITHDRAWN else RescheduleStatus.REJECTED
        decideReschedule(open[RescheduleRequests.id], status, by, now)
        cancel("reschedule-expiry:${open[RescheduleRequests.id]}")
    }

    private fun FeeQuote.toDto() = FeeDto(tier?.hoursBefore, policyPercent, MoneyDto(amount.minor, amount.currency), usesFreeCancellation)

    private fun JdbcTransaction.toDto(session: SessionRecord): SessionDto = toDtos(listOf(session)).single()

    /** The client read model: location name, open proposal and package usage, without other clients' data. */
    private fun JdbcTransaction.toDtos(sessions: List<SessionRecord>): List<SessionDto> {
        if (sessions.isEmpty()) return emptyList()
        val gymNames = with(catalogue) { gymNames(sessions.mapNotNull { it.gymId }) }
        val proposals = RescheduleRequests.selectAll().where {
            (RescheduleRequests.sessionId inList sessions.map { it.id }) and (RescheduleRequests.status inList RescheduleStatus.OPEN)
        }.associateBy { it[RescheduleRequests.sessionId] }
        val usage = sessions.filter { it.paymentKind == "PACKAGE" }.map { it.relationshipId }.distinct()
            .associateWith { with(billing) { packageUsage(it) } }
        return sessions.map { s ->
            val proposal = proposals[s.id]
            SessionDto(
                id = s.id.toString(),
                trainerId = s.trainerId.toString(),
                clientId = s.clientId.toString(),
                start = s.startsAt,
                end = s.endsAt,
                timeZone = s.timeZone,
                location = s.gymId?.let { gymNames[it] } ?: s.placeNote.orEmpty(),
                status = SessionStatusMapper.fromDb(s.status),
                revision = s.revision,
                proposal = proposal?.let {
                    ProposalDto(
                        start = it[RescheduleRequests.proposedStartsAt].toKotlin(),
                        end = it[RescheduleRequests.proposedEndsAt].toKotlin(),
                        author = if (it[RescheduleRequests.proposerRole] == "TRAINER") ProposalAuthorDto.TRAINER else ProposalAuthorDto.CLIENT,
                        requestedAt = it[RescheduleRequests.receivedAt].toKotlin(),
                    )
                },
                payment = when (s.paymentKind) {
                    "PACKAGE" -> usage[s.relationshipId].let { SessionPaymentDto(PaymentKindDto.PACKAGE, it?.first, it?.second) }
                    "CASH" -> SessionPaymentDto(PaymentKindDto.CASH)
                    else -> SessionPaymentDto(PaymentKindDto.ONLINE)
                },
                policyVersionId = s.policyVersionId.toString(),
                price = s.price,
                requestedAt = s.requestedAt,
            )
        }
    }
}
