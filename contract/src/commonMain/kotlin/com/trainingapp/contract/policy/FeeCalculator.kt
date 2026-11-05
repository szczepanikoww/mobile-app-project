package com.trainingapp.contract.policy

import com.trainingapp.contract.money.Money
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

enum class CancelledBy { Client, Trainer }

/** What a late change would cost. [policyPercent] is the tier's percent even when a free cancellation covers it. */
data class FeeQuote(
    val tier: PolicyTier?,
    val policyPercent: Int,
    val amount: Money,
    val usesFreeCancellation: Boolean,
) {
    /** The change falls inside a fee tier (it is "late"), whether or not a fee is charged. */
    val late: Boolean get() = tier != null
    val free: Boolean get() = amount.isZero
}

/**
 * The single fee calculation used by the app's preview and by the server's charge (architecture 3.2). The time is
 * always the server's receipt time of the request, never the device clock.
 */
object FeeCalculator {
    /** The tier that applies [receivedAt] for a session starting at [sessionStart], or null when the change is free. */
    fun tierAt(policy: CancellationPolicy, sessionStart: Instant, receivedAt: Instant): PolicyTier? {
        val remaining = sessionStart - receivedAt
        return policy.tiers.filter { remaining < it.hoursBefore.hours }.minByOrNull { it.hoursBefore }
    }

    /**
     * The fee for cancelling. A trainer's cancellation never charges the client. A late client cancellation uses one of
     * the [freeLateCancellationsLeft] free late cancellations of the month when any are left.
     */
    fun cancellation(
        policy: CancellationPolicy,
        sessionStart: Instant,
        receivedAt: Instant,
        price: Money,
        freeLateCancellationsLeft: Int,
        cancelledBy: CancelledBy,
    ): FeeQuote {
        val zero = Money.zero(price.currency)
        if (cancelledBy == CancelledBy.Trainer) return FeeQuote(null, 0, zero, usesFreeCancellation = false)
        val tier = tierAt(policy, sessionStart, receivedAt) ?: return FeeQuote(null, 0, zero, usesFreeCancellation = false)
        if (tier.feePercent == 0) return FeeQuote(tier, 0, zero, usesFreeCancellation = false)
        if (freeLateCancellationsLeft > 0) return FeeQuote(tier, tier.feePercent, zero, usesFreeCancellation = true)
        return FeeQuote(tier, tier.feePercent, price.percentOf(tier.feePercent), usesFreeCancellation = false)
    }

    /** The fee the policy proposes for a late reschedule request. The trainer decides whether to charge it. */
    fun reschedule(policy: CancellationPolicy, sessionStart: Instant, receivedAt: Instant, price: Money): FeeQuote {
        val tier = tierAt(policy, sessionStart, receivedAt)
            ?: return FeeQuote(null, 0, Money.zero(price.currency), usesFreeCancellation = false)
        return FeeQuote(tier, tier.feePercent, price.percentOf(tier.feePercent), usesFreeCancellation = false)
    }

    /** The no-show fee. */
    fun noShow(policy: CancellationPolicy, price: Money): Money = price.percentOf(policy.noShowPercent)
}
