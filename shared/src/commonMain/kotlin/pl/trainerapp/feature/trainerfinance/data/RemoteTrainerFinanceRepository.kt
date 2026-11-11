package pl.trainerapp.feature.trainerfinance.data

import com.trainingapp.contract.api.FinanceSummaryDto
import com.trainingapp.contract.api.PaymentDto
import com.trainingapp.contract.api.PaymentStatus
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import pl.trainer.data.remote.TrainerApiClient
import pl.trainer.domain.Failure
import pl.trainer.domain.TrainerException
import pl.trainerapp.feature.trainerfinance.TrainerFinanceRepository
import pl.trainerapp.feature.trainerfinance.model.FinancePeriod
import pl.trainerapp.feature.trainerfinance.model.FinanceSnapshot
import pl.trainerapp.feature.trainerfinance.model.FinanceSummary
import pl.trainerapp.feature.trainerfinance.model.PaymentItem
import pl.trainerapp.feature.trainerfinance.model.PaymentMethod
import pl.trainerapp.feature.trainerfinance.model.Pln
import pl.trainerapp.feature.trainerfinance.model.RevenueBar
import kotlin.time.Clock
import kotlin.time.Instant
import com.trainingapp.contract.api.FinancePeriod as ApiPeriod
import com.trainingapp.contract.api.PaymentMethod as ApiMethod

/**
 * Trainer finance figures from `GET trainer/finance?period=`.
 *
 * Mapping notes:
 * - The screen's [Pln] has no currency; the server reports the trainer's default currency (PLN for now) and only the
 *   minor units are taken.
 * - [FinanceSummary.revenue] is the money collected (settled) in the period, not the value of sessions; sessions value,
 *   fees and collected stay separate. [FinanceSummary.overdue] is the server's total outstanding balance (all open
 *   charges), and [FinanceSummary.paymentsTotal] equals the collected money.
 * - Not in the DTO and therefore neutral: [FinanceSummary.goal] = null, [FinanceSummary.lastPayout] and
 *   [FinanceSummary.stripeBalance] = 0, [PaymentItem.description] = "" and [PaymentItem.isFee] = false.
 * - Only settled (SUCCEEDED) payments are listed; pending and failed ones are not money received.
 * - [now] only drives the display ("current bar", [FinanceSummary.generatedAt]) because the DTO has no server time;
 *   the period bounds come from the server. Bars are labelled in [zone] (the trainer's zone is not in the DTO).
 *   The month view groups the server's daily buckets into weeks (T1..).
 * - The last successful snapshot per period is cached in memory and returned flagged offline without a connection.
 */
class RemoteTrainerFinanceRepository(
    private val api: TrainerApiClient,
    private val zone: TimeZone = TimeZone.of("Europe/Warsaw"),
    private val now: () -> Instant = { Clock.System.now() },
) : TrainerFinanceRepository {
    private val cache = mutableMapOf<FinancePeriod, FinanceSnapshot>()

    override suspend fun load(period: FinancePeriod): FinanceSnapshot {
        val dto = try {
            api.get<FinanceSummaryDto>("trainer/finance?period=${period.toApi().name}")
        } catch (e: TrainerException) {
            val cached = cache[period]
            if (e.failure == Failure.Offline && cached != null) return cached.copy(isOffline = true)
            throw e
        }
        val at = now()
        return FinanceSnapshot(dto.toSummary(period, at, zone), isOffline = false, lastUpdated = at).also { cache[period] = it }
    }
}

private fun FinancePeriod.toApi() = when (this) {
    FinancePeriod.Week -> ApiPeriod.WEEK
    FinancePeriod.Month -> ApiPeriod.MONTH
    FinancePeriod.Year -> ApiPeriod.YEAR
}

private fun ApiMethod.toModel() = when (this) {
    ApiMethod.CASH -> PaymentMethod.Cash
    ApiMethod.BLIK -> PaymentMethod.Blik
    // The screen has no separate bank-transfer or test-mode method.
    ApiMethod.CARD, ApiMethod.PRZELEWY24, ApiMethod.TEST_MODE -> PaymentMethod.Card
}

private val WeekDays = mapOf(
    DayOfWeek.MONDAY to "Pn", DayOfWeek.TUESDAY to "Wt", DayOfWeek.WEDNESDAY to "Śr", DayOfWeek.THURSDAY to "Cz",
    DayOfWeek.FRIDAY to "Pt", DayOfWeek.SATURDAY to "So", DayOfWeek.SUNDAY to "Nd",
)
private val Months = listOf("sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru")

internal fun FinanceSummaryDto.toSummary(period: FinancePeriod, now: Instant, zone: TimeZone): FinanceSummary {
    // (start, end exclusive, label, revenue) per bar.
    data class Bar(val start: Instant, val end: Instant, val label: String, val revenue: Long)

    val starts = buckets.map { it.start } + to
    val daily = buckets.mapIndexed { i, b ->
        val local = b.start.toLocalDateTime(zone)
        val label = when (period) {
            FinancePeriod.Week -> WeekDays.getValue(local.dayOfWeek)
            FinancePeriod.Month -> local.day.toString()
            FinancePeriod.Year -> Months[local.month.ordinal]
        }
        Bar(b.start, starts[i + 1], label, b.collected.minor)
    }
    val bars = if (period == FinancePeriod.Month) {
        daily.chunked(7).mapIndexed { i, week ->
            Bar(week.first().start, week.last().end, "T${i + 1}", week.sumOf { it.revenue })
        }
    } else daily

    return FinanceSummary(
        period = period,
        generatedAt = now,
        revenue = Pln(collected.minor),
        previousRevenue = Pln(previousCollected.minor),
        goal = null,
        bars = bars.map { RevenueBar(it.label, Pln(it.revenue)) },
        currentBar = bars.indexOfFirst { now >= it.start && now < it.end }.takeIf { it >= 0 },
        sessionsDone = sessionsDone,
        sessionsValue = Pln(sessionsValue.minor),
        cancellationFees = Pln(cancellationFees.minor),
        cancellationFeeCount = cancellationFeeCount,
        overdue = Pln(outstanding.minor),
        overdueClients = overdueClients,
        paymentsTotal = Pln(collected.minor),
        lastPayout = Pln(0),
        stripeBalance = Pln(0),
        payments = payments.filter { it.status == PaymentStatus.SUCCEEDED }.map { it.toItem(zone) },
    )
}

private fun PaymentDto.toItem(zone: TimeZone) = PaymentItem(
    id = id,
    clientName = clientName,
    description = "",
    method = method.toModel(),
    amount = Pln(amount.minor),
    at = settledAt ?: createdAt,
    zone = zone,
)
