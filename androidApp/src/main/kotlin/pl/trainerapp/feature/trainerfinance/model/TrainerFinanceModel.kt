package pl.trainerapp.feature.trainerfinance.model

import java.time.Instant
import java.time.ZoneId

// Local stand-ins for the shared contract (:contract) until it exists. Keep them free of Android types.

enum class FinancePeriod { Week, Month, Year }

/** Money in grosze, so sums never go through floating point. */
@JvmInline
value class Pln(val grosze: Long) {
    operator fun plus(other: Pln) = Pln(grosze + other.grosze)
    operator fun minus(other: Pln) = Pln(grosze - other.grosze)

    companion object {
        fun zl(amount: Long) = Pln(amount * 100)
    }
}

enum class PaymentMethod { Blik, Cash, Card }

data class PaymentItem(
    val id: String,
    val clientName: String,
    /** E.g. "Pakiet 8 sesji" or "Odwołanie późne (50%)". */
    val description: String,
    val method: PaymentMethod,
    val amount: Pln,
    val at: Instant,
    val zone: ZoneId,
    /** True for fees that need the trainer's attention (late cancellations). */
    val isFee: Boolean = false,
)

/** One bar of the revenue chart. [label] is already localized ("Pn", "T1", "sty"). */
data class RevenueBar(val label: String, val revenue: Pln)

data class FinanceSummary(
    val period: FinancePeriod,
    /** Server time of the snapshot; used for display only ("Dzisiaj"), never for policy. */
    val generatedAt: Instant,
    val revenue: Pln,
    val previousRevenue: Pln,
    val goal: Pln?,
    val bars: List<RevenueBar>,
    /** Index of the current day/week/month in [bars]. */
    val currentBar: Int?,
    val sessionsDone: Int,
    val sessionsValue: Pln,
    val cancellationFees: Pln,
    val cancellationFeeCount: Int,
    val overdue: Pln,
    val overdueClients: Int,
    val paymentsTotal: Pln,
    val lastPayout: Pln,
    val stripeBalance: Pln,
    val payments: List<PaymentItem>,
) {
    val change: Pln get() = revenue - previousRevenue

    /** Whole percent change versus the previous period; null when there is nothing to compare with. */
    val changePercent: Int?
        get() = if (previousRevenue.grosze > 0) Math.round(change.grosze * 100.0 / previousRevenue.grosze).toInt() else null

    /** 0..1, capped; null without a goal. */
    val goalProgress: Float?
        get() = goal?.takeIf { it.grosze > 0 }?.let { (revenue.grosze.toFloat() / it.grosze).coerceIn(0f, 1f) }
}

data class FinanceSnapshot(
    val summary: FinanceSummary,
    val isOffline: Boolean,
    val lastUpdated: Instant,
)
