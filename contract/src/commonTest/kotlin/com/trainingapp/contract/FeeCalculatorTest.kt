package com.trainingapp.contract

import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.CancelledBy
import com.trainingapp.contract.policy.FeeCalculator
import com.trainingapp.contract.policy.PolicyPreset
import com.trainingapp.contract.policy.PolicyPresets
import com.trainingapp.contract.policy.PolicyTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FeeCalculatorTest {
    private val start = Instant.parse("2026-10-29T08:00:00Z")
    private val price = Money.pln(120)
    private val standard = PolicyPresets.rules(PolicyPreset.Standard).toPolicy("p1", "t1", 1, Instant.parse("2026-01-01T00:00:00Z"))

    private fun cancel(before: Duration, freeLeft: Int = 0, by: CancelledBy = CancelledBy.Client) =
        FeeCalculator.cancellation(standard, start, start - before, price, freeLeft, by)

    @Test
    fun cancellingBeforeTheFirstTierIsFree() {
        val quote = cancel(30.hours)
        assertNull(quote.tier)
        assertTrue(quote.free)
        assertFalse(quote.late)
    }

    @Test
    fun exactlyTwentyFourHoursBeforeIsStillFree() {
        assertNull(cancel(24.hours).tier)
        assertEquals(PolicyTier(24, 50), cancel(24.hours - 1.minutes).tier)
    }

    @Test
    fun middleTierChargesHalfInMinorUnits() {
        val quote = cancel(18.hours)
        assertEquals(50, quote.policyPercent)
        assertEquals(Money(6000, "PLN"), quote.amount)
    }

    @Test
    fun exactlyTwelveHoursBeforeIsStillTheMiddleTier() {
        assertEquals(50, cancel(12.hours).policyPercent)
        assertEquals(100, cancel(12.hours - 1.minutes).policyPercent)
    }

    @Test
    fun afterTheStartTheLatestTierApplies() {
        assertEquals(Money.pln(120), cancel(Duration.ZERO - 5.minutes).amount)
    }

    @Test
    fun aFreeLateCancellationCoversTheFee() {
        val quote = cancel(2.hours, freeLeft = 1)
        assertTrue(quote.usesFreeCancellation)
        assertTrue(quote.late)
        assertEquals(100, quote.policyPercent)
        assertTrue(quote.amount.isZero)
    }

    @Test
    fun freeCancellationsAreNotUsedOutsideTheTiers() {
        assertFalse(cancel(48.hours, freeLeft = 1).usesFreeCancellation)
    }

    @Test
    fun trainerCancellationIsAlwaysFree() {
        val quote = cancel(1.hours, by = CancelledBy.Trainer)
        assertTrue(quote.free)
        assertFalse(quote.usesFreeCancellation)
    }

    @Test
    fun theReceiptTimeDecidesNotTheAnswerTime() {
        // Received 25 h before the start: free, even if the server answers later inside the 24 h tier.
        val received = start - 25.hours
        assertNull(FeeCalculator.tierAt(standard, start, received))
        assertEquals(PolicyTier(24, 50), FeeCalculator.tierAt(standard, start, received + 2.hours))
    }

    @Test
    fun amountsRoundHalfUpToWholeMinorUnits() {
        assertEquals(Money(25, "PLN"), Money(99, "PLN").percentOf(25))
        assertEquals(Money(5000, "PLN"), Money(9999, "PLN").percentOf(50))
        assertEquals(Money(7499, "PLN"), Money(9999, "PLN").percentOf(75))
    }

    @Test
    fun noShowUsesThePolicyPercent() {
        assertEquals(Money.pln(120), FeeCalculator.noShow(standard, price))
    }

    @Test
    fun lateRescheduleProposesTheTierFeeWithoutFreeCancellations() {
        val quote = FeeCalculator.reschedule(standard, start, start - 20.hours, price)
        assertEquals(Money.pln(60), quote.amount)
        assertFalse(quote.usesFreeCancellation)
        assertTrue(FeeCalculator.reschedule(standard, start, start - 30.hours, price).free)
    }
}
