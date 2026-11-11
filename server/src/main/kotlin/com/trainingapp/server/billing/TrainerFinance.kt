package com.trainingapp.server.billing

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.greaterEq
import com.trainingapp.contract.api.ChargeDto
import com.trainingapp.contract.api.ChargeKind
import com.trainingapp.contract.api.ClientBalanceDto
import com.trainingapp.contract.api.FinancePeriod
import com.trainingapp.contract.api.FinanceSummaryDto
import com.trainingapp.contract.api.PaymentDto
import com.trainingapp.contract.api.PaymentMethod
import com.trainingapp.contract.api.PaymentStatus
import com.trainingapp.contract.api.RecordCashPaymentRequest
import com.trainingapp.contract.api.RevenueBucketDto
import com.trainingapp.contract.api.Settlement
import com.trainingapp.contract.money.Money
import com.trainingapp.server.auth.Access
import com.trainingapp.server.db.Allocations
import com.trainingapp.server.db.AppUsers
import com.trainingapp.server.db.ChargeBalanceView
import com.trainingapp.server.db.Charges
import com.trainingapp.server.db.Payments
import com.trainingapp.server.db.RelationshipOverdueView
import com.trainingapp.server.db.Sessions
import com.trainingapp.server.db.TrainerClients
import com.trainingapp.server.db.TrainerProfiles
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.conflict
import com.trainingapp.server.platform.invalid
import com.trainingapp.server.platform.newId
import com.trainingapp.server.platform.toInstantK
import com.trainingapp.server.platform.toOffset
import com.trainingapp.server.platform.toUuid
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * Trainer finance figures and cash recording (architecture §8). Charges, money collected and outstanding debt are
 * separate values. Ids named `clientId` in this API are relationship ids (as in ClientSummaryDto.id).
 */
class TrainerFinance(private val db: Db, private val clock: ServerClock) {

    suspend fun summary(trainerId: UUID, period: FinancePeriod): FinanceSummaryDto = db.tx {
        val zone = trainerZone(trainerId)
        val currency = trainerCurrency(trainerId)
        val today = clock.now().toZoned(zone).toLocalDate()
        val start = periodStart(today, period).atStartOfDay(zone)
        val end = step(start, period, 1)
        val prevStart = step(start, period, -1)
        val from = start.toInstant().toKotlinInstant()
        val to = end.toInstant().toKotlinInstant()

        val done = Sessions.selectAll()
            .where {
                (Sessions.trainerId eq trainerId) and (Sessions.status eq "COMPLETED") and (Sessions.currency eq currency) and
                    (Sessions.startsAt greaterEq from.toOffset()) and (Sessions.startsAt less to.toOffset())
            }.toList()

        val fees = Charges.join(TrainerClients, JoinType.INNER, Charges.relationshipId, TrainerClients.id)
            .selectAll()
            .where {
                (TrainerClients.trainerId eq trainerId) and (Charges.currency eq currency) and
                    (Charges.kind inList FEE_KINDS) and
                    (Charges.createdAt greaterEq from.toOffset()) and (Charges.createdAt less to.toOffset())
            }.toList()

        val settled = settledPayments(trainerId, currency, prevStart.toInstant().toKotlinInstant(), to)
        fun settledAt(r: ResultRow) = r[Payments.settledAt]!!.toInstantK()
        val inPeriod = settled.filter { settledAt(it) >= from }
        val previous = settled.filter { settledAt(it) < from }

        val buckets = buckets(start, end, period, zone).map { (bucketStart, bucketEnd) ->
            val b0 = bucketStart.toInstant().toKotlinInstant()
            val b1 = bucketEnd.toInstant().toKotlinInstant()
            RevenueBucketDto(b0, Money(inPeriod.filter { settledAt(it) >= b0 && settledAt(it) < b1 }.sumOf { it[Payments.amountMinor] }, currency))
        }

        val payments = Payments.join(TrainerClients, JoinType.INNER, Payments.relationshipId, TrainerClients.id)
            .join(AppUsers, JoinType.INNER, TrainerClients.clientId, AppUsers.id)
            .selectAll()
            .where {
                (TrainerClients.trainerId eq trainerId) and (Payments.currency eq currency) and
                    (Payments.createdAt greaterEq from.toOffset()) and (Payments.createdAt less to.toOffset())
            }
            .orderBy(Payments.createdAt, SortOrder.DESC)
            .limit(50)
            .map { it.toPayment() }

        FinanceSummaryDto(
            period = period,
            from = from,
            to = to,
            sessionsDone = done.size,
            sessionsValue = Money(done.sumOf { it[Sessions.priceMinor] }, currency),
            cancellationFees = Money(fees.sumOf { it[Charges.amountMinor] }, currency),
            cancellationFeeCount = fees.size,
            collected = Money(inPeriod.sumOf { it[Payments.amountMinor] }, currency),
            previousCollected = Money(previous.sumOf { it[Payments.amountMinor] }, currency),
            outstanding = Money(outstanding(trainerId, currency), currency),
            overdueClients = RelationshipOverdueView.join(TrainerClients, JoinType.INNER, RelationshipOverdueView.relationshipId, TrainerClients.id)
                .selectAll().where { TrainerClients.trainerId eq trainerId }
                .count { it[RelationshipOverdueView.hasOverdue] == true },
            buckets = buckets,
            payments = payments,
        )
    }

    suspend fun balance(trainerId: UUID, clientId: String): ClientBalanceDto = db.tx {
        val relationshipId = clientId.toUuid("clientId")
        Access.trainerRelationship(trainerId, relationshipId)
        val currency = trainerCurrency(trainerId)
        val open = ChargeBalanceView.selectAll().where { ChargeBalanceView.relationshipId eq relationshipId }
            .associate { it[ChargeBalanceView.chargeId] to it[ChargeBalanceView.openMinor] }
        val charges = Charges.selectAll().where { Charges.relationshipId eq relationshipId }
            .orderBy(Charges.dueAt, SortOrder.DESC)
            .map {
                val cur = it[Charges.currency]
                ChargeDto(
                    id = it[Charges.id].toString(),
                    kind = ChargeKind.valueOf(it[Charges.kind]),
                    settlement = Settlement.valueOf(it[Charges.settlement]),
                    sessionId = it[Charges.sessionId]?.toString(),
                    amount = Money(it[Charges.amountMinor], cur),
                    // Package-credit charges are not in the view: nothing is owed in money.
                    open = Money((open[it[Charges.id]] ?: 0L).coerceAtLeast(0L), cur),
                    dueAt = it[Charges.dueAt].toInstantK(),
                    createdAt = it[Charges.createdAt].toInstantK(),
                )
            }
        val payments = Payments.join(TrainerClients, JoinType.INNER, Payments.relationshipId, TrainerClients.id)
            .join(AppUsers, JoinType.INNER, TrainerClients.clientId, AppUsers.id)
            .selectAll().where { Payments.relationshipId eq relationshipId }
            .orderBy(Payments.createdAt, SortOrder.DESC)
            .map { it.toPayment() }
        ClientBalanceDto(
            clientId = clientId,
            charges = charges,
            payments = payments,
            outstanding = Money(charges.filter { it.open.currency == currency }.sumOf { it.open.minor }, currency),
        )
    }

    /**
     * Records cash received by the trainer. Idempotent by [RecordCashPaymentRequest.operationId]. The amount is
     * allocated to the relationship's open CASH charges, oldest due date first (charge rows locked); any remainder
     * stays unallocated as client credit and is not applied to later charges automatically.
     */
    suspend fun recordCash(trainerId: UUID, request: RecordCashPaymentRequest): PaymentDto = db.tx {
        val relationshipId = request.clientId.toUuid("clientId")
        val operationId = request.operationId.toUuid("operationId")
        Access.activeRelationship(trainerId, relationshipId)
        val existing = Payments.join(TrainerClients, JoinType.INNER, Payments.relationshipId, TrainerClients.id)
            .join(AppUsers, JoinType.INNER, TrainerClients.clientId, AppUsers.id)
            .selectAll().where { Payments.operationId eq operationId }.singleOrNull()
        if (existing != null) {
            if (existing[Payments.relationshipId] != relationshipId || existing[Payments.amountMinor] != request.amount.minor) {
                conflict("Operation id already used")
            }
            return@tx existing.toPayment()
        }
        val currency = trainerCurrency(trainerId)
        if (request.amount.minor <= 0) invalid("Amount must be positive")
        if (request.amount.currency != currency) invalid("Currency must be $currency")
        val now = clock.now()
        val paymentId = newId()
        Payments.insert {
            it[id] = paymentId
            it[Payments.relationshipId] = relationshipId
            it[method] = PaymentMethod.CASH.name
            it[status] = PaymentStatus.SUCCEEDED.name
            it[amountMinor] = request.amount.minor
            it[Payments.currency] = currency
            it[provider] = null
            it[recordedBy] = trainerId
            it[createdAt] = now.toOffset()
            it[settledAt] = now.toOffset()
            it[Payments.operationId] = operationId
        }
        val cashCharges = Charges.selectAll()
            .where { (Charges.relationshipId eq relationshipId) and (Charges.settlement eq Settlement.CASH.name) and (Charges.currency eq currency) }
            .orderBy(Charges.dueAt, SortOrder.ASC)
            .forUpdate()
            .toList()
        val open = ChargeBalanceView.selectAll().where { ChargeBalanceView.relationshipId eq relationshipId }
            .associate { it[ChargeBalanceView.chargeId] to it[ChargeBalanceView.openMinor] }
        var left = request.amount.minor
        for (charge in cashCharges) {
            if (left <= 0) break
            val owed = open[charge[Charges.id]] ?: 0L
            if (owed <= 0) continue
            val part = minOf(owed, left)
            Allocations.insert {
                it[id] = newId()
                it[Allocations.paymentId] = paymentId
                it[chargeId] = charge[Charges.id]
                it[amountMinor] = part
                it[createdAt] = now.toOffset()
                it[Allocations.operationId] = newId()
            }
            left -= part
        }
        Payments.join(TrainerClients, JoinType.INNER, Payments.relationshipId, TrainerClients.id)
            .join(AppUsers, JoinType.INNER, TrainerClients.clientId, AppUsers.id)
            .selectAll().where { Payments.id eq paymentId }.single().toPayment()
    }

    // ------------------------------------------------------------------ helpers

    private fun trainerCurrency(trainerId: UUID): String =
        TrainerProfiles.selectAll().where { TrainerProfiles.userId eq trainerId }.single()[TrainerProfiles.defaultCurrency]

    private fun trainerZone(trainerId: UUID): ZoneId =
        runCatching { ZoneId.of(AppUsers.selectAll().where { AppUsers.id eq trainerId }.single()[AppUsers.timeZone]) }
            .getOrDefault(ZoneId.of("UTC"))

    private fun settledPayments(trainerId: UUID, currency: String, from: Instant, to: Instant): List<ResultRow> =
        Payments.join(TrainerClients, JoinType.INNER, Payments.relationshipId, TrainerClients.id)
            .selectAll()
            .where {
                (TrainerClients.trainerId eq trainerId) and (Payments.status eq PaymentStatus.SUCCEEDED.name) and
                    (Payments.currency eq currency) and
                    (Payments.settledAt greaterEq from.toOffset()) and (Payments.settledAt less to.toOffset())
            }.toList()

    private fun outstanding(trainerId: UUID, currency: String): Long =
        ChargeBalanceView.join(TrainerClients, JoinType.INNER, ChargeBalanceView.relationshipId, TrainerClients.id)
            .selectAll()
            .where { (TrainerClients.trainerId eq trainerId) and (ChargeBalanceView.currency eq currency) and (ChargeBalanceView.openMinor greater 0L) }
            .sumOf { it[ChargeBalanceView.openMinor] }

    private fun ResultRow.toPayment(): PaymentDto {
        val name = if (this[AppUsers.deletedAt] != null) "—" else this[AppUsers.displayName]
        return PaymentDto(
            id = this[Payments.id].toString(),
            clientId = this[Payments.relationshipId].toString(),
            clientName = name,
            method = PaymentMethod.valueOf(this[Payments.method]),
            status = PaymentStatus.valueOf(this[Payments.status]),
            amount = Money(this[Payments.amountMinor], this[Payments.currency]),
            createdAt = this[Payments.createdAt].toInstantK(),
            settledAt = this[Payments.settledAt]?.toInstantK(),
        )
    }

    private fun Instant.toZoned(zone: ZoneId): ZonedDateTime =
        java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()).atZone(zone)

    private fun periodStart(today: LocalDate, period: FinancePeriod): LocalDate = when (period) {
        FinancePeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        FinancePeriod.MONTH -> today.withDayOfMonth(1)
        FinancePeriod.YEAR -> today.withDayOfYear(1)
    }

    private fun step(start: ZonedDateTime, period: FinancePeriod, n: Long): ZonedDateTime = when (period) {
        FinancePeriod.WEEK -> start.plusWeeks(n)
        FinancePeriod.MONTH -> start.plusMonths(n)
        FinancePeriod.YEAR -> start.plusYears(n)
    }

    /** Every bucket of the period (day, or month for a year), zeros included. */
    private fun buckets(start: ZonedDateTime, end: ZonedDateTime, period: FinancePeriod, zone: ZoneId): List<Pair<ZonedDateTime, ZonedDateTime>> {
        val out = mutableListOf<Pair<ZonedDateTime, ZonedDateTime>>()
        var cur = start
        while (cur.isBefore(end)) {
            val next = if (period == FinancePeriod.YEAR) cur.plusMonths(1) else cur.toLocalDate().plusDays(1).atStartOfDay(zone)
            out += cur to next
            cur = next
        }
        return out
    }

    private companion object {
        val FEE_KINDS = listOf(ChargeKind.LATE_CANCELLATION_FEE.name, ChargeKind.LATE_RESCHEDULE_FEE.name, ChargeKind.NO_SHOW_FEE.name)
    }
}
