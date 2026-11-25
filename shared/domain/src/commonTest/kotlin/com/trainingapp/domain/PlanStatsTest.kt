package com.trainingapp.domain

import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.domain.plan.DraftDay
import com.trainingapp.domain.plan.DraftExercise
import com.trainingapp.domain.plan.DraftSet
import com.trainingapp.domain.plan.PlanStatsCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanStatsTest {

    private fun set(rpe: String = "", rest: String = "", duration: String = "") = DraftSet("s", rpe = rpe, rest = rest, duration = duration)

    @Test
    fun countsSetsEstimatesTimeAndAveragesRpe() {
        val day = DraftDay(
            "d",
            exercises = listOf(
                DraftExercise("a", "bench", sets = listOf(set("8", "120"), set("8,5", "120"), set("9", "150"))),
                DraftExercise("b", "row", restSeconds = 90, sets = listOf(set("8"), set("8,5"), set("9", "120"))),
            ),
        )
        val stats = PlanStatsCalculator.of(day) { TrackingType.WEIGHT_REPS }
        assertEquals(6, stats.totalSets)
        // 6 × 45 s work + rests 120 + 120 + 90 (default) + 90 + 90 + 90 (exercise rest) = 870 s.
        // The last set's own rest (150, 120) has no block in the editor, so the exercise's rest follows it.
        assertEquals(15, stats.estimatedMinutes)
        assertEquals(8.5, stats.averageRpe)
    }

    @Test
    fun timedSetsUseTheirDurationAndMissingRpeIsNull() {
        val day = DraftDay("d", exercises = listOf(DraftExercise("a", "plank", sets = listOf(set(duration = "1:00", rest = "30")))))
        val stats = PlanStatsCalculator.of(day) { TrackingType.TIME }
        // 60 s work + 90 s default rest (a lone set has no rest block).
        assertEquals(3, stats.estimatedMinutes)
        assertNull(stats.averageRpe)
    }

    @Test
    fun rpeOfExercisesWithRpeOffIsIgnoredAndRestsAcceptMinutes() {
        val day = DraftDay(
            "d",
            exercises = listOf(
                DraftExercise("a", "bench", rpeEnabled = false, restSeconds = 60, sets = listOf(set("10", "2:00"), set("10"))),
            ),
        )
        val stats = PlanStatsCalculator.of(day) { TrackingType.WEIGHT_REPS }
        assertNull(stats.averageRpe)
        // 2 × 45 s work + 120 s between the sets + 60 s after the exercise = 270 s.
        assertEquals(5, stats.estimatedMinutes)
    }
}
