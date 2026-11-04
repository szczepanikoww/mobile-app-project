package com.trainingapp.contract.policy

import kotlin.time.Instant

/**
 * One fee step: cancelling less than [hoursBefore] hours before the session start costs [feePercent] of its price.
 * Cancelling exactly [hoursBefore] hours before is still outside this step.
 */
data class PolicyTier(val hoursBefore: Int, val feePercent: Int)

/** The presets offered in the policy editor (decision D7). A policy with any other values is "Własna". */
enum class PolicyPreset { Flexible, Standard, Strict }

/**
 * A trainer's cancellation policy version. Versions are immutable: saving creates a new one, and every session keeps
 * the version the client accepted when booking.
 */
data class CancellationPolicy(
    val versionId: String,
    val trainerId: String,
    val version: Int,
    val createdAt: Instant,
    val tiers: List<PolicyTier>,
    val noShowPercent: Int,
    val graceMinutes: Int,
    val freeLateCancellationsPerMonth: Int,
) {
    /** The tiers from the earliest (largest hours) to the latest. */
    val sortedTiers: List<PolicyTier> get() = tiers.sortedByDescending { it.hoursBefore }

    /** Cancelling at least this many hours before the start is free; 0 when every cancellation is free. */
    val freeUntilHours: Int get() = tiers.maxOfOrNull { it.hoursBefore } ?: 0

    /** The values the editor compares with the presets; identity and version are ignored. */
    val rules: PolicyRules get() = PolicyRules(sortedTiers, noShowPercent, graceMinutes, freeLateCancellationsPerMonth)
}

/** The editable part of a policy, without identity or version. */
data class PolicyRules(
    val tiers: List<PolicyTier>,
    val noShowPercent: Int,
    val graceMinutes: Int,
    val freeLateCancellationsPerMonth: Int,
) {
    val sortedTiers: List<PolicyTier> get() = tiers.sortedByDescending { it.hoursBefore }

    /** The preset these values match, or null for "Własna konfiguracja". */
    val preset: PolicyPreset? get() = PolicyPreset.entries.firstOrNull { PolicyPresets.rules(it) == copy(tiers = sortedTiers) }

    fun toPolicy(versionId: String, trainerId: String, version: Int, createdAt: Instant) = CancellationPolicy(
        versionId, trainerId, version, createdAt, sortedTiers, noShowPercent, graceMinutes, freeLateCancellationsPerMonth,
    )
}

object PolicyPresets {
    /** All presets: no-show 100%, grace period 15 min, 1 free late cancellation per month (D7). */
    fun rules(preset: PolicyPreset): PolicyRules = PolicyRules(
        tiers = when (preset) {
            PolicyPreset.Flexible -> listOf(PolicyTier(12, 50))
            PolicyPreset.Standard -> listOf(PolicyTier(24, 50), PolicyTier(12, 100))
            PolicyPreset.Strict -> listOf(PolicyTier(48, 50), PolicyTier(24, 100))
        },
        noShowPercent = 100,
        graceMinutes = 15,
        freeLateCancellationsPerMonth = 1,
    )
}

/** Why a policy cannot be saved. */
enum class PolicyError { NoTiers, TierHours, TierOrder, Percent, Grace, FreeCancellations }

/** A warning shown before saving; it never blocks saving (architecture 3.2). */
enum class PolicyWarning {
    /** A 100% fee applies earlier than 24 hours before the session. */
    FullFeeEarly,

    /** A 100% fee applies and the client has no free late cancellations. */
    FullFeeWithoutLeniency,
}

object PolicyValidator {
    val allowedPercents = listOf(0, 25, 50, 75, 100)
    val graceRange = 5..30
    const val MAX_HOURS = 168
    const val MAX_FREE_CANCELLATIONS = 4

    fun errors(rules: PolicyRules): Set<PolicyError> = buildSet {
        val tiers = rules.sortedTiers
        if (tiers.isEmpty()) add(PolicyError.NoTiers)
        if (tiers.any { it.hoursBefore !in 1..MAX_HOURS }) add(PolicyError.TierHours)
        if (tiers.zipWithNext().any { (earlier, later) -> earlier.hoursBefore == later.hoursBefore || earlier.feePercent > later.feePercent }) {
            add(PolicyError.TierOrder)
        }
        if ((tiers.map { it.feePercent } + rules.noShowPercent).any { it !in allowedPercents }) add(PolicyError.Percent)
        if (rules.graceMinutes !in graceRange) add(PolicyError.Grace)
        if (rules.freeLateCancellationsPerMonth !in 0..MAX_FREE_CANCELLATIONS) add(PolicyError.FreeCancellations)
    }

    fun warnings(rules: PolicyRules): Set<PolicyWarning> = buildSet {
        val fullFee = rules.tiers.filter { it.feePercent == 100 }
        if (fullFee.any { it.hoursBefore > 24 }) add(PolicyWarning.FullFeeEarly)
        if (fullFee.isNotEmpty() && rules.freeLateCancellationsPerMonth == 0) add(PolicyWarning.FullFeeWithoutLeniency)
    }
}
