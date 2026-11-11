package com.trainingapp.server.billing

import com.trainingapp.contract.api.MoneyDto
import com.trainingapp.contract.policy.FeeQuote
import com.trainingapp.contract.schedule.ChargeKindDto
import com.trainingapp.contract.wallet.ChargeDto
import com.trainingapp.contract.wallet.ChargeReasonDto
import com.trainingapp.contract.wallet.ChargeStateDto
import com.trainingapp.contract.wallet.PayResponseDto
import com.trainingapp.contract.wallet.PaymentDto
import com.trainingapp.contract.wallet.PaymentMethodDto
import com.trainingapp.contract.wallet.PaymentStateDto
import com.trainingapp.contract.wallet.SessionPackageDto
import com.trainingapp.contract.wallet.WalletDto
import com.trainingapp.server.catalogue.CatalogueService
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
import com.trainingapp.server.identity.CurrentUser
import com.trainingapp.server.identity.IdentityService
import com.trainingapp.server.platform.JobQueue
import com.trainingapp.server.platform.JobQueue.schedule
import com.trainingapp.server.platform.Outbox.emit
import com.trainingapp.server.scheduling.SessionRecord
import com.trainingapp.server.scheduling.Sessions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.ZoneId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The Billing module: fee quotes and decisions, the append-only ledger, package credits and payments (plan §8.2).
 * Lock order inside a transaction: trainer → session → charge rows (plan §3 rule 3).
 */
class BillingService(
    private val db: Db,
    private val identity: IdentityService,
    private val catalogue: CatalogueService,
    private val paymentProvider: PaymentProvider,
    private val fakeConfirmDelaySeconds: Long = 3,
) {
    companion object {
        /** R8: how long a cancellation quote stays valid. */
        val QUOTE_VALIDITY = 300.seconds

        /** R10: online session charges are due 7 days after check-in. */
        val ONLINE_SESSION_DUE = 7.days

        const val UNITS_PER_SESSION = 100
    }

    // ----- Fee quotes and decisions (used inside Scheduling's transactions) -----

    /**
     * Free late cancellations already used this calendar month, counted in the session's time zone (G9) from the
     * server clock.
     */
    fun JdbcTransaction.freeCancellationsUsed(relationshipId: Uuid, timeZone: String, now: Instant): Int {
        val zone = ZoneId.of(timeZone)
        val local = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds()).atZone(zone)
        val monthStart = local.toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toOffsetDateTime()
        val nextMonth = local.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toOffsetDateTime()
        return (FeeDecisions innerJoin Sessions).selectAll().where {
            (Sessions.relationshipId eq relationshipId) and (FeeDecisions.usedFreeCancellation eq true) and
                (FeeDecisions.decidedAt greaterEq monthStart) and (FeeDecisions.decidedAt less nextMonth)
        }.count().toInt()
    }

    fun JdbcTransaction.insertQuote(
        session: SessionRecord,
        revision: Int,
        action: String,
        policyVersionId: Uuid,
        receivedAt: Instant,
        fee: FeeQuote,
        expiresAt: Instant,
    ): Uuid {
        val id = Ids.v7()
        FeeQuotes.insert {
            it[FeeQuotes.id] = id
            it[sessionId] = session.id
            it[sessionRevision] = revision
            it[FeeQuotes.action] = action
            it[FeeQuotes.policyVersionId] = policyVersionId
            it[FeeQuotes.receivedAt] = receivedAt.toOffset()
            it[tierHoursBefore] = fee.tier?.hoursBefore
            it[feePercent] = fee.policyPercent
            it[amountMinor] = fee.amount.minor
            it[currency] = fee.amount.currency
            it[usesFreeCancellation] = fee.usesFreeCancellation
            it[FeeQuotes.expiresAt] = expiresAt.toOffset()
        }
        return id
    }

    data class QuoteRecord(
        val id: Uuid, val sessionId: Uuid, val sessionRevision: Int, val action: String, val policyVersionId: Uuid,
        val receivedAt: Instant, val tierHoursBefore: Int?, val feePercent: Int, val amount: MoneyDto,
        val usesFreeCancellation: Boolean, val expiresAt: Instant,
    )

    fun JdbcTransaction.quote(id: Uuid): QuoteRecord? = FeeQuotes.selectAll().where { FeeQuotes.id eq id }.singleOrNull()?.let {
        QuoteRecord(
            it[FeeQuotes.id], it[FeeQuotes.sessionId], it[FeeQuotes.sessionRevision], it[FeeQuotes.action], it[FeeQuotes.policyVersionId],
            it[FeeQuotes.receivedAt].toKotlin(), it[FeeQuotes.tierHoursBefore], it[FeeQuotes.feePercent],
            MoneyDto(it[FeeQuotes.amountMinor], it[FeeQuotes.currency]), it[FeeQuotes.usesFreeCancellation], it[FeeQuotes.expiresAt].toKotlin(),
        )
    }

    fun JdbcTransaction.decisionExists(operationId: Uuid): Uuid? =
        FeeDecisions.selectAll().where { FeeDecisions.operationId eq operationId }.singleOrNull()?.get(FeeDecisions.sessionId)

    /**
     * The client cancelled with [quote]: one fee decision, then by payment kind a money charge, or the package credit
     * consumed by the fee percent (released when the fee is 0) — unless the policy charges money for package fees.
     */
    fun JdbcTransaction.decideClientCancel(session: SessionRecord, quote: QuoteRecord, exempt: Boolean, operationId: Uuid, now: Instant) {
        val decisionId = insertDecision(
            session, quote.id, "CLIENT_CANCEL", quote.receivedAt, quote.policyVersionId, quote.feePercent,
            quoted = quote.amount, usedFree = quote.usesFreeCancellation, exempt = exempt, decidedBy = null, operationId = operationId,
        )
        settleFee(session, decisionId, "LATE_CANCELLATION_FEE", quote.amount, quote.feePercent, quote.policyVersionId, operationId, now)
    }

    /** A trainer cancellation never charges the client (DB check); package credits are released. */
    fun JdbcTransaction.decideTrainerCancel(session: SessionRecord, actorId: Uuid, operationId: Uuid, now: Instant) {
        insertDecision(session, null, "TRAINER_CANCEL", now, session.policyVersionId, 0, MoneyDto(0, session.price.currency), false, false, actorId, operationId)
        releaseCredit(session.id, now)
    }

    /** The trainer's decision on a late reschedule fee: charge it, or waive it in full (D8). */
    fun JdbcTransaction.decideReschedule(session: SessionRecord, quote: QuoteRecord, charge: Boolean, actorId: Uuid, operationId: Uuid, now: Instant) {
        val decisionId = insertDecision(
            session, quote.id, "LATE_RESCHEDULE", quote.receivedAt, quote.policyVersionId, quote.feePercent, quote.amount,
            usedFree = false, exempt = false, decidedBy = actorId, operationId = operationId, waived = if (charge) 0 else quote.amount.minor,
        )
        if (charge && quote.amount.minor > 0) {
            insertCharge(session, "LATE_RESCHEDULE_FEE", settlementFor(session, quote.policyVersionId), quote.amount, decisionId, now, Ids.derived(operationId, "charge"))
        }
    }

    /** No-show (after start + grace): decision, then a fee charge or the package credit consumed by the percent. */
    fun JdbcTransaction.decideNoShow(session: SessionRecord, quoteId: Uuid, policyVersionId: Uuid, percent: Int, fee: MoneyDto, actorId: Uuid, operationId: Uuid, now: Instant) {
        val decisionId = insertDecision(session, quoteId, "NO_SHOW", now, policyVersionId, percent, fee, false, false, actorId, operationId)
        settleFee(session, decisionId, "NO_SHOW_FEE", fee, percent, policyVersionId, operationId, now)
    }

    private fun JdbcTransaction.settleFee(
        session: SessionRecord, decisionId: Uuid, kind: String, fee: MoneyDto, percent: Int, policyVersionId: Uuid, operationId: Uuid, now: Instant,
    ) {
        if (session.paymentKind == "PACKAGE" && packageFeeMode(policyVersionId) == "USE_CREDIT") {
            if (fee.minor > 0) consumeCredit(session.id, percent, now) else releaseCredit(session.id, now)
            return
        }
        releaseCredit(session.id, now)
        if (fee.minor > 0) insertCharge(session, kind, settlementFor(session, policyVersionId), fee, decisionId, now, Ids.derived(operationId, "charge"))
    }

    private fun JdbcTransaction.packageFeeMode(policyVersionId: Uuid): String =
        with(catalogue) { policy(policyVersionId)?.packageFeeMode } ?: "USE_CREDIT"

    private fun settlementFor(session: SessionRecord, @Suppress("UNUSED_PARAMETER") policyVersionId: Uuid): String =
        if (session.paymentKind == "CASH") "CASH" else "ONLINE"

    private fun JdbcTransaction.insertDecision(
        session: SessionRecord, quoteId: Uuid?, action: String, receivedAt: Instant, policyVersionId: Uuid?, percent: Int,
        quoted: MoneyDto, usedFree: Boolean, exempt: Boolean, decidedBy: Uuid?, operationId: Uuid, waived: Long = 0,
    ): Uuid {
        val id = Ids.v7()
        FeeDecisions.insert {
            it[FeeDecisions.id] = id
            it[sessionId] = session.id
            it[FeeDecisions.quoteId] = quoteId
            it[FeeDecisions.action] = action
            it[FeeDecisions.receivedAt] = receivedAt.toOffset()
            it[FeeDecisions.policyVersionId] = policyVersionId
            it[feePercent] = percent
            it[quotedMinor] = quoted.minor
            it[waivedMinor] = waived
            it[currency] = quoted.currency
            it[usedFreeCancellation] = usedFree
            it[FeeDecisions.exempt] = exempt
            it[FeeDecisions.decidedBy] = decidedBy
            it[FeeDecisions.operationId] = operationId
        }
        return id
    }

    private fun JdbcTransaction.insertCharge(
        session: SessionRecord, kind: String, settlement: String, amount: MoneyDto, decisionId: Uuid?, dueAt: Instant, operationId: Uuid,
    ) {
        Charges.insertIgnore {
            it[id] = Ids.v7()
            it[relationshipId] = session.relationshipId
            it[Charges.kind] = kind
            it[Charges.settlement] = settlement
            it[sessionId] = session.id
            it[feeDecisionId] = decisionId
            it[amountMinor] = amount.minor
            it[currency] = amount.currency
            it[Charges.dueAt] = dueAt.toOffset()
            it[Charges.operationId] = operationId
        }
        emit("charge", session.relationshipId, "charge.created", mapOf("sessionId" to session.id, "clientId" to session.clientId))
    }

    /** R10: the session's own charge is written at check-in, so a cancellation never needs a reversal. */
    fun JdbcTransaction.chargeCompletedSession(session: SessionRecord, now: Instant) {
        if (session.paymentKind == "PACKAGE") {
            consumeCredit(session.id, UNITS_PER_SESSION, now)
            return
        }
        if (session.price.minor <= 0) return
        val (settlement, due) = if (session.paymentKind == "CASH") "CASH" to session.startsAt else "ONLINE" to (now + ONLINE_SESSION_DUE)
        insertCharge(session, "SESSION", settlement, session.price, null, due, Ids.derived(session.id, "session-charge"))
    }

    // ----- Package credits -----

    /** Reserves one session (100 units) from the oldest granted period that still has units. False when none. */
    fun JdbcTransaction.reserveCredit(session: SessionRecord): Boolean {
        val agreements = PackageAgreements.selectAll()
            .where { (PackageAgreements.relationshipId eq session.relationshipId) and (PackageAgreements.status eq "ACTIVE") }
            .map { it[PackageAgreements.id] }
        if (agreements.isEmpty()) return false
        val periods = BillingPeriods.selectAll()
            .where { (BillingPeriods.agreementId inList agreements) and (BillingPeriods.grantedAt.isNotNull()) }
            .orderBy(BillingPeriods.periodStart, SortOrder.ASC)
            .forUpdate(ForUpdateOption.ForUpdate).toList()
        for (period in periods) {
            val pid = period[BillingPeriods.id]
            val used = CreditReservations.selectAll().where { CreditReservations.billingPeriodId eq pid }.sumOf {
                when (it[CreditReservations.status]) {
                    "RESERVED" -> it[CreditReservations.reservedUnits]
                    "CONSUMED" -> it[CreditReservations.consumedUnits] ?: 0
                    else -> 0
                }
            }
            if (period[BillingPeriods.creditUnitsGranted] - used >= UNITS_PER_SESSION) {
                CreditReservations.insert {
                    it[id] = Ids.v7()
                    it[billingPeriodId] = pid
                    it[sessionId] = session.id
                    it[reservedUnits] = UNITS_PER_SESSION
                    it[status] = "RESERVED"
                }
                return true
            }
        }
        return false
    }

    fun JdbcTransaction.consumeCredit(sessionId: Uuid, units: Int, now: Instant) {
        CreditReservations.update({ (CreditReservations.sessionId eq sessionId) and (CreditReservations.status eq "RESERVED") }) {
            it[status] = "CONSUMED"
            it[consumedUnits] = units.coerceIn(0, UNITS_PER_SESSION)
            it[settledAt] = now.toOffset()
        }
    }

    fun JdbcTransaction.releaseCredit(sessionId: Uuid, now: Instant) {
        CreditReservations.update({ (CreditReservations.sessionId eq sessionId) and (CreditReservations.status eq "RESERVED") }) {
            it[status] = "RELEASED"
            it[settledAt] = now.toOffset()
        }
    }

    /** True when the relationship has an active package with at least one free session. */
    fun JdbcTransaction.hasFreeCredit(relationshipId: Uuid): Boolean =
        PackageCreditBalance.selectAll().where { PackageCreditBalance.relationshipId eq relationshipId }
            .any { it[PackageCreditBalance.availableUnits] >= UNITS_PER_SESSION }

    /** `(used, total)` sessions of the relationship's packages, for the session's payment line. */
    fun JdbcTransaction.packageUsage(relationshipId: Uuid): Pair<Int, Int>? {
        val rows = PackageCreditBalance.selectAll().where { PackageCreditBalance.relationshipId eq relationshipId }.toList()
        if (rows.isEmpty()) return null
        return (rows.sumOf { it[PackageCreditBalance.usedUnits] } / UNITS_PER_SESSION).toInt() to
            (rows.sumOf { it[PackageCreditBalance.grantedUnits] } / UNITS_PER_SESSION).toInt()
    }

    // ----- Wallet -----

    suspend fun wallet(client: CurrentUser): WalletDto = db.tx {
        val now = dbNow()
        val relationships = with(identity) { relationshipsOfClient(client.id) }
        val relIds = relationships.map { it.id }
        val trainerOf = relationships.associate { it.id to it.trainerId }
        val refs = with(identity) { refs(relationships.map { it.trainerId }) }
        fun ref(relationshipId: Uuid) = refs.getValue(trainerOf.getValue(relationshipId))

        val packages = if (relIds.isEmpty()) emptyList() else
            (PackageAgreements innerJoin PackageCreditBalance).selectAll().where { PackageAgreements.relationshipId inList relIds }.map { row ->
                SessionPackageDto(
                    id = row[PackageAgreements.id].toString(),
                    name = with(catalogue) { offerName(row[PackageAgreements.offerId]) } ?: "",
                    trainer = ref(row[PackageAgreements.relationshipId]),
                    used = (row[PackageCreditBalance.usedUnits] / UNITS_PER_SESSION).toInt(),
                    total = (row[PackageCreditBalance.grantedUnits] / UNITS_PER_SESSION).toInt(),
                )
            }

        val chargeRows = if (relIds.isEmpty()) emptyList() else Charges.selectAll().where { Charges.relationshipId inList relIds }
            .orderBy(Charges.createdAt, SortOrder.DESC).toList()
        val chargeIds = chargeRows.map { it[Charges.id] }
        val open = if (chargeIds.isEmpty()) emptyMap() else
            ChargeBalance.selectAll().where { ChargeBalance.chargeId inList chargeIds }.associate { it[ChargeBalance.chargeId] to it[ChargeBalance.openMinor] }
        val allocations = if (chargeIds.isEmpty()) emptyList() else
            (Allocations innerJoin Payments).selectAll().where { Allocations.chargeId inList chargeIds }.toList()
        val pendingCharges = allocations.filter { it[Payments.status] == "PENDING" }.map { it[Allocations.chargeId] }.toSet()
        val sessionIds = chargeRows.mapNotNull { it[Charges.sessionId] }
        val sessionStarts = if (sessionIds.isEmpty()) emptyMap() else
            Sessions.selectAll().where { Sessions.id inList sessionIds }.associate { it[Sessions.id] to it[Sessions.startsAt].toKotlin() }

        val charges = chargeRows.map { row ->
            val id = row[Charges.id]
            val openMinor = open[id] ?: row[Charges.amountMinor]
            ChargeDto(
                id = id.toString(),
                reason = reasonOf(row[Charges.kind]),
                sessionId = row[Charges.sessionId]?.toString(),
                sessionStart = row[Charges.sessionId]?.let { sessionStarts[it] },
                trainer = ref(row[Charges.relationshipId]),
                amount = MoneyDto(row[Charges.amountMinor], row[Charges.currency]),
                open = MoneyDto(openMinor.coerceAtLeast(0), row[Charges.currency]),
                kind = when (row[Charges.settlement]) { "CASH" -> ChargeKindDto.CASH; "PACKAGE_CREDIT" -> ChargeKindDto.PACKAGE_CREDIT; else -> ChargeKindDto.ONLINE },
                state = when {
                    openMinor <= 0 -> ChargeStateDto.SETTLED
                    id in pendingCharges -> ChargeStateDto.PAYMENT_PENDING
                    else -> ChargeStateDto.UNPAID
                },
                createdAt = row[Charges.createdAt].toKotlin(),
                dueAt = row[Charges.dueAt].toKotlin(),
            )
        } + packageCreditFees(client.id, ::ref)

        val paymentRows = if (relIds.isEmpty()) emptyList() else Payments.selectAll().where { Payments.relationshipId inList relIds }
            .orderBy(Payments.createdAt, SortOrder.DESC).toList()
        val chargeKinds = chargeRows.associate { it[Charges.id] to it[Charges.kind] }
        val payments = paymentRows.map { row ->
            val pid = row[Payments.id]
            val firstCharge = Allocations.selectAll().where { Allocations.paymentId eq pid }.orderBy(Allocations.createdAt).firstOrNull()?.get(Allocations.chargeId)
            val refunded = Refunds.selectAll().where { (Refunds.paymentId eq pid) and (Refunds.status eq "SUCCEEDED") }.sumOf { it[Refunds.amountMinor] }
            PaymentDto(
                id = pid.toString(),
                chargeId = firstCharge?.toString(),
                reason = firstCharge?.let { chargeKinds[it] }?.let(::reasonOf),
                trainer = ref(row[Payments.relationshipId]),
                amount = MoneyDto(row[Payments.amountMinor], row[Payments.currency]),
                method = PaymentMethodDto.valueOf(row[Payments.method]),
                state = when {
                    row[Payments.status] == "SUCCEEDED" && refunded >= row[Payments.amountMinor] -> PaymentStateDto.REFUNDED
                    row[Payments.status] == "SUCCEEDED" -> PaymentStateDto.PAID
                    row[Payments.status] == "FAILED" -> PaymentStateDto.FAILED
                    else -> PaymentStateDto.PENDING
                },
                at = (row[Payments.settledAt] ?: row[Payments.createdAt]).toKotlin(),
            )
        }
        WalletDto(packages, charges, payments, now)
    }

    /** Package fees taken from credits have no money charge: built from fee_decision + consumed reservation. */
    private fun JdbcTransaction.packageCreditFees(clientId: Uuid, ref: (Uuid) -> com.trainingapp.contract.schedule.TrainerRefDto): List<ChargeDto> =
        (FeeDecisions innerJoin Sessions).selectAll().where {
            (Sessions.clientId eq clientId) and (Sessions.paymentKind eq "PACKAGE") and (FeeDecisions.quotedMinor greater 0L) and
                (FeeDecisions.action inList listOf("CLIENT_CANCEL", "NO_SHOW"))
        }.mapNotNull { row ->
            val reservation = CreditReservations.selectAll()
                .where { (CreditReservations.sessionId eq row[Sessions.id]) and (CreditReservations.status eq "CONSUMED") }.singleOrNull()
                ?: return@mapNotNull null
            val units = reservation[CreditReservations.consumedUnits] ?: 0
            if (units == 0) return@mapNotNull null
            ChargeDto(
                id = row[FeeDecisions.id].toString(),
                reason = if (row[FeeDecisions.action] == "NO_SHOW") ChargeReasonDto.NO_SHOW else ChargeReasonDto.LATE_CANCELLATION,
                sessionId = row[Sessions.id].toString(),
                sessionStart = row[Sessions.startsAt].toKotlin(),
                trainer = ref(row[Sessions.relationshipId]),
                amount = MoneyDto(row[FeeDecisions.quotedMinor], row[FeeDecisions.currency]),
                open = MoneyDto(0, row[FeeDecisions.currency]),
                kind = ChargeKindDto.PACKAGE_CREDIT,
                state = ChargeStateDto.SETTLED,
                createdAt = row[FeeDecisions.decidedAt].toKotlin(),
                packagePercent = units,
            )
        }

    private fun reasonOf(kind: String) = when (kind) {
        "LATE_CANCELLATION_FEE" -> ChargeReasonDto.LATE_CANCELLATION
        "LATE_RESCHEDULE_FEE" -> ChargeReasonDto.LATE_RESCHEDULE
        "NO_SHOW_FEE" -> ChargeReasonDto.NO_SHOW
        "PACKAGE_PERIOD" -> ChargeReasonDto.PACKAGE_PERIOD
        else -> ChargeReasonDto.SESSION
    }

    // ----- Payments -----

    /**
     * Starts an online payment of the charge's open amount. Never returns PAID: the payment is PENDING until the
     * provider's webhook arrives. Idempotent by [operationId] (`payment.operation_id`).
     */
    suspend fun pay(client: CurrentUser, chargeId: Uuid, method: PaymentMethodDto, operationId: Uuid): PayResponseDto {
        if (method == PaymentMethodDto.CASH) throw invalid("method")
        // Phase 1: payment + allocation in one transaction with the charge row locked.
        val paymentId = db.tx {
            Payments.selectAll().where { Payments.operationId eq operationId }.singleOrNull()?.let { existing ->
                val alloc = Allocations.selectAll().where { Allocations.paymentId eq existing[Payments.id] }.firstOrNull()
                if (alloc?.get(Allocations.chargeId) != chargeId) throw ApiException(422, ErrorCode.IDEMPOTENCY_MISMATCH, "key used for another charge")
                return@tx existing[Payments.id]
            }
            val charge = Charges.selectAll().where { Charges.id eq chargeId }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
                ?: throw notFound("charge")
            val relationship = with(identity) { relationship(charge[Charges.relationshipId]) }
            if (relationship == null || relationship.clientId != client.id) {
                throw notFound("charge", DeniedSubject("charge", chargeId, charge[Charges.relationshipId]))
            }
            if (charge[Charges.settlement] != "ONLINE") throw conflict(message = "cash charges are paid to the trainer")
            val openMinor = ChargeBalance.selectAll().where { ChargeBalance.chargeId eq chargeId }.single()[ChargeBalance.openMinor]
            if (openMinor <= 0) throw conflict(message = "charge already settled")
            val pending = (Allocations innerJoin Payments).selectAll()
                .where { (Allocations.chargeId eq chargeId) and (Payments.status eq "PENDING") }.any()
            if (pending) throw conflict(message = "a payment is already pending")
            val id = Ids.v7()
            Payments.insert {
                it[Payments.id] = id
                it[relationshipId] = charge[Charges.relationshipId]
                it[Payments.method] = method.name
                it[status] = "PENDING"
                it[amountMinor] = openMinor
                it[currency] = charge[Charges.currency]
                it[Payments.provider] = paymentProvider.name
                it[Payments.operationId] = operationId
            }
            Allocations.insert {
                it[Allocations.id] = Ids.v7()
                it[paymentId] = id
                it[Allocations.chargeId] = chargeId
                it[amountMinor] = openMinor
                it[Allocations.operationId] = Ids.derived(operationId, "allocation")
            }
            emit("payment", id, "payment.started", mapOf("chargeId" to chargeId, "clientId" to client.id))
            id
        }
        // Phase 2: the provider call, outside any transaction, with the same idempotency key.
        val row = db.tx { Payments.selectAll().where { Payments.id eq paymentId }.single() }
        val intent = paymentProvider.createIntent(paymentId, row[Payments.amountMinor], row[Payments.currency], row[Payments.method], operationId)
        db.tx {
            Payments.update({ (Payments.id eq paymentId) and Payments.providerRef.isNull() }) { it[providerRef] = intent.ref }
            if (paymentProvider is FakePaymentProvider) {
                schedule(JobQueue.FAKE_PAYMENT_CONFIRM, "fake-confirm:$paymentId", dbNow() + fakeConfirmDelaySeconds.seconds, mapOf("paymentId" to paymentId))
            }
        }
        val payment = wallet(client).payments.first { it.id == paymentId.toString() }
        return PayResponseDto(payment, intent.clientSecret)
    }

    /**
     * Applies a provider event once (`provider_event` primary key). Duplicates are ignored; for an event that is
     * out of order the provider's current status is fetched and applied instead. Acceptance test 2.
     */
    suspend fun handleProviderEvent(providerName: String, eventId: String, type: String, paymentRef: String, payload: JsonObject): Boolean = db.tx {
        // Stored once by (provider, event_id): a duplicate delivery hits the primary key and is ignored.
        val inserted = attempt(SqlState.UNIQUE_VIOLATION) {
            ProviderEvents.insert {
                it[provider] = providerName
                it[ProviderEvents.eventId] = eventId
                it[ProviderEvents.type] = type
                it[ProviderEvents.payload] = payload
            }
        }
        if (inserted == null) return@tx false
        val payment = Payments.selectAll().where { (Payments.provider eq providerName) and (Payments.providerRef eq paymentRef) }
            .forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
        if (payment != null && payment[Payments.status] == "PENDING") {
            val claimed = when (type) {
                "payment.succeeded" -> "SUCCEEDED"
                "payment.failed" -> "FAILED"
                else -> null
            }
            // Out-of-order or unknown events: trust the provider's current state, not the event.
            val status = paymentProvider.fetchStatus(paymentRef).takeIf { it == "SUCCEEDED" || it == "FAILED" } ?: claimed
            if (status != null) {
                val now = dbNow()
                Payments.update({ Payments.id eq payment[Payments.id] }) {
                    it[Payments.status] = status
                    if (status == "SUCCEEDED") it[settledAt] = now.toOffset()
                }
                emit("payment", payment[Payments.id], "payment.${status.lowercase()}", mapOf("relationshipId" to payment[Payments.relationshipId]))
            }
        }
        ProviderEvents.update({ (ProviderEvents.provider eq providerName) and (ProviderEvents.eventId eq eventId) }) {
            it[processedAt] = dbNow().toOffset()
        }
        true
    }

    /** Worker job for the fake provider: a synthetic signed event through the same handler as real webhooks. */
    suspend fun confirmFakePayment(paymentId: Uuid) {
        val ref = db.tx { Payments.selectAll().where { Payments.id eq paymentId }.singleOrNull()?.get(Payments.providerRef) } ?: return
        handleProviderEvent("fake", "evt_$paymentId", "payment.succeeded", ref, JsonObject(mapOf("payment" to JsonPrimitive(ref))))
    }

    // ----- Trainer transitions the client flow depends on (plan §8.7; dev routes until the trainer API exists) -----

    /** Cash received by the trainer: one SUCCEEDED payment allocated to open cash charges, oldest due first. */
    suspend fun recordCash(trainerId: Uuid, relationshipId: Uuid, amount: MoneyDto, operationId: Uuid): Uuid = db.tx {
        Payments.selectAll().where { Payments.operationId eq operationId }.singleOrNull()?.let { return@tx it[Payments.id] }
        val relationship = with(identity) { relationship(relationshipId) }
        if (relationship == null || relationship.trainerId != trainerId) throw notFound("relationship")
        if (amount.minor <= 0) throw invalid("amount")
        val charges = Charges.selectAll().where { (Charges.relationshipId eq relationshipId) and (Charges.settlement eq "CASH") }
            .orderBy(Charges.dueAt, SortOrder.ASC).forUpdate(ForUpdateOption.ForUpdate).toList()
        val now = dbNow()
        val id = Ids.v7()
        Payments.insert {
            it[Payments.id] = id
            it[Payments.relationshipId] = relationshipId
            it[method] = "CASH"
            it[status] = "SUCCEEDED"
            it[amountMinor] = amount.minor
            it[currency] = amount.currency
            it[recordedBy] = trainerId
            it[settledAt] = now.toOffset()
            it[Payments.operationId] = operationId
        }
        var left = amount.minor
        for (charge in charges) {
            if (left <= 0) break
            val cid = charge[Charges.id]
            val open = ChargeBalance.selectAll().where { ChargeBalance.chargeId eq cid }.single()[ChargeBalance.openMinor]
            if (open <= 0 || charge[Charges.currency] != amount.currency) continue
            val part = minOf(open, left)
            Allocations.insert {
                it[Allocations.id] = Ids.v7()
                it[paymentId] = id
                it[chargeId] = cid
                it[amountMinor] = part
                it[Allocations.operationId] = Ids.derived(operationId, "allocation:$cid")
            }
            left -= part
        }
        emit("payment", id, "payment.succeeded", mapOf("relationshipId" to relationshipId, "clientId" to relationship.clientId))
        id
    }

    /** A paid package period (D1 package purchase is out of scope; this grants credits for testing and seed data). */
    fun JdbcTransaction.grantPackage(relationshipId: Uuid, offerId: Uuid, operationId: Uuid, now: Instant): Uuid {
        PackageAgreements.selectAll().where { PackageAgreements.operationId eq operationId }.singleOrNull()?.let { return it[PackageAgreements.id] }
        val offer = com.trainingapp.server.catalogue.Offers.selectAll().where { com.trainingapp.server.catalogue.Offers.id eq offerId }.single()
        val sessions = offer[com.trainingapp.server.catalogue.Offers.sessionsPerPeriod] ?: throw invalid("offerId")
        val id = Ids.v7()
        PackageAgreements.insert {
            it[PackageAgreements.id] = id
            it[PackageAgreements.relationshipId] = relationshipId
            it[PackageAgreements.offerId] = offerId
            it[sessionsPerPeriod] = sessions
            it[priceMinor] = offer[com.trainingapp.server.catalogue.Offers.priceMinor]
            it[currency] = offer[com.trainingapp.server.catalogue.Offers.currency]
            it[status] = "ACTIVE"
            it[PackageAgreements.operationId] = operationId
        }
        val today = java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now.toEpochMilliseconds()), ZoneId.of("Europe/Warsaw"))
        BillingPeriods.insert {
            it[BillingPeriods.id] = Ids.v7()
            it[agreementId] = id
            it[periodStart] = today
            it[periodEnd] = today.plusMonths(1)
            it[creditUnitsGranted] = sessions * UNITS_PER_SESSION
            it[grantedAt] = now.toOffset()
        }
        return id
    }
}

