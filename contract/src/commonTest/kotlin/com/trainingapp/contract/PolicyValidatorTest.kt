package com.trainingapp.contract

import com.trainingapp.contract.policy.PolicyError
import com.trainingapp.contract.policy.PolicyPreset
import com.trainingapp.contract.policy.PolicyPresets
import com.trainingapp.contract.policy.PolicyTier
import com.trainingapp.contract.policy.PolicyValidator
import com.trainingapp.contract.policy.PolicyWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyValidatorTest {
    @Test
    fun presetsAreValidAndRecognised() {
        PolicyPreset.entries.forEach { preset ->
            val rules = PolicyPresets.rules(preset)
            assertTrue(PolicyValidator.errors(rules).isEmpty(), "$preset")
            assertEquals(preset, rules.preset)
        }
    }

    @Test
    fun changingAnyValueMakesItCustom() {
        val standard = PolicyPresets.rules(PolicyPreset.Standard)
        assertNull(standard.copy(graceMinutes = 10).preset)
        assertNull(standard.copy(tiers = listOf(PolicyTier(24, 50), PolicyTier(6, 100))).preset)
    }

    @Test
    fun tierOrderIsCheckedAfterSorting() {
        val reversed = PolicyPresets.rules(PolicyPreset.Standard).let { it.copy(tiers = it.tiers.reversed()) }
        assertEquals(PolicyPreset.Standard, reversed.preset)
        val decreasing = reversed.copy(tiers = listOf(PolicyTier(24, 100), PolicyTier(12, 50)))
        assertTrue(PolicyError.TierOrder in PolicyValidator.errors(decreasing))
    }

    @Test
    fun invalidValuesAreReported() {
        val rules = PolicyPresets.rules(PolicyPreset.Flexible)
        assertTrue(PolicyError.Percent in PolicyValidator.errors(rules.copy(noShowPercent = 30)))
        assertTrue(PolicyError.Grace in PolicyValidator.errors(rules.copy(graceMinutes = 45)))
        assertTrue(PolicyError.NoTiers in PolicyValidator.errors(rules.copy(tiers = emptyList())))
        assertTrue(PolicyError.TierHours in PolicyValidator.errors(rules.copy(tiers = listOf(PolicyTier(0, 50)))))
    }

    @Test
    fun aggressiveSettingsWarnButDoNotBlock() {
        val strict = PolicyPresets.rules(PolicyPreset.Strict)
        assertTrue(PolicyValidator.warnings(strict).isEmpty())
        val early = strict.copy(tiers = listOf(PolicyTier(48, 100)))
        assertEquals(setOf(PolicyWarning.FullFeeEarly), PolicyValidator.warnings(early))
        assertTrue(PolicyValidator.errors(early).isEmpty())
        val noLeniency = strict.copy(freeLateCancellationsPerMonth = 0)
        assertEquals(setOf(PolicyWarning.FullFeeWithoutLeniency), PolicyValidator.warnings(noLeniency))
    }
}
