package pl.trainerapp.feature.trainerfinance.data

import kotlinx.coroutines.delay
import pl.trainerapp.feature.trainerfinance.TrainerFinanceRepository
import pl.trainerapp.feature.trainerfinance.model.FinancePeriod
import pl.trainerapp.feature.trainerfinance.model.FinanceSnapshot
import pl.trainerapp.feature.trainerfinance.model.FinanceSummary
import pl.trainerapp.feature.trainerfinance.model.PaymentItem
import pl.trainerapp.feature.trainerfinance.model.PaymentMethod
import pl.trainerapp.feature.trainerfinance.model.Pln
import pl.trainerapp.feature.trainerfinance.model.RevenueBar
import java.time.Instant
import java.time.ZoneId

/** Deterministic synthetic data. Used by previews, tests and the explicit demo data source. */
object TrainerFinanceFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Warsaw")

    /** Wednesday 09:15 in Warsaw. */
    val now: Instant = Instant.parse("2026-10-28T08:15:00Z")

    private fun zl(amount: Long) = Pln.zl(amount)

    val payments = listOf(
        PaymentItem("p1", "Katarzyna Nowak", "Pakiet 8 sesji", PaymentMethod.Blik, zl(960), Instant.parse("2026-10-28T06:30:00Z"), zone),
        PaymentItem("p2", "Tomek Wójcik", "Sesja pojedyncza", PaymentMethod.Cash, zl(120), Instant.parse("2026-10-27T16:45:00Z"), zone),
        PaymentItem(
            "p3", "Piotr Szymański", "Odwołanie późne (50%)", PaymentMethod.Card, zl(60),
            Instant.parse("2026-10-20T10:00:00Z"), zone, isFee = true,
        ),
    )

    val week = FinanceSummary(
        period = FinancePeriod.Week,
        generatedAt = now,
        revenue = zl(3480),
        previousRevenue = zl(2960),
        goal = zl(4000),
        bars = listOf("Pn" to 480L, "Wt" to 720L, "Śr" to 960L, "Cz" to 600L, "Pt" to 420L, "So" to 300L, "Nd" to 0L)
            .map { (label, amount) -> RevenueBar(label, zl(amount)) },
        currentBar = 2,
        sessionsDone = 24,
        sessionsValue = zl(2880),
        cancellationFees = zl(180),
        cancellationFeeCount = 3,
        overdue = zl(240),
        overdueClients = 2,
        paymentsTotal = zl(3240),
        lastPayout = zl(2950),
        stripeBalance = zl(530),
        payments = payments,
    )

    val month = week.copy(
        period = FinancePeriod.Month,
        revenue = zl(13_920),
        previousRevenue = zl(12_400),
        goal = zl(16_000),
        bars = listOf("T1" to 3200L, "T2" to 3600L, "T3" to 3640L, "T4" to 3480L).map { (l, a) -> RevenueBar(l, zl(a)) },
        currentBar = 3,
        sessionsDone = 98,
        sessionsValue = zl(11_760),
        cancellationFees = zl(540),
        cancellationFeeCount = 9,
        paymentsTotal = zl(13_200),
    )

    val year = week.copy(
        period = FinancePeriod.Year,
        revenue = zl(118_400),
        previousRevenue = zl(96_000),
        goal = null,
        bars = listOf("sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru")
            .zip(listOf(7200L, 8100L, 9400L, 9800L, 10_600L, 10_200L, 9000L, 8800L, 11_200L, 13_920L, 0L, 0L))
            .map { (l, a) -> RevenueBar(l, zl(a)) },
        currentBar = 9,
        sessionsDone = 1020,
        sessionsValue = zl(112_000),
        cancellationFees = zl(4800),
        cancellationFeeCount = 71,
        paymentsTotal = zl(116_000),
    )

    /** Quiet period: nothing earned yet and nothing owed. */
    val empty = week.copy(
        revenue = zl(0),
        previousRevenue = zl(0),
        bars = week.bars.map { it.copy(revenue = zl(0)) },
        sessionsDone = 0,
        sessionsValue = zl(0),
        cancellationFees = zl(0),
        cancellationFeeCount = 0,
        overdue = zl(0),
        overdueClients = 0,
        paymentsTotal = zl(0),
        lastPayout = zl(0),
        stripeBalance = zl(0),
        payments = emptyList(),
    )

    fun forPeriod(period: FinancePeriod): FinanceSummary = when (period) {
        FinancePeriod.Week -> week
        FinancePeriod.Month -> month
        FinancePeriod.Year -> year
    }
}

/** Demo data source: not connected to any backend. Wired in explicitly by the app entry point. */
class DemoTrainerFinanceRepository(
    private val latencyMillis: Long = 600,
) : TrainerFinanceRepository {

    override suspend fun load(period: FinancePeriod): FinanceSnapshot {
        delay(latencyMillis)
        val summary = TrainerFinanceFixtures.forPeriod(period)
        return FinanceSnapshot(summary, isOffline = false, lastUpdated = summary.generatedAt)
    }
}
