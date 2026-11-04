package com.trainingapp.domain

import com.trainingapp.contract.exercise.SetType
import com.trainingapp.domain.workout.MeasurementKind
import com.trainingapp.domain.workout.MeasurementValidator
import com.trainingapp.domain.workout.PersonalRecords
import com.trainingapp.domain.workout.PreviousPerformance
import com.trainingapp.domain.workout.ProgressRules
import com.trainingapp.domain.workout.SetField
import com.trainingapp.domain.workout.WorkoutLog
import com.trainingapp.domain.workout.WorkoutLogReducer
import com.trainingapp.domain.workout.WorkoutSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A finished workout where every bench set is ticked with the given weights (kg) and reps. */
private fun benchWorkout(id: String, finishedAt: Long, warmUpKg: Double, working: List<Pair<Double, Int>>): WorkoutLog {
    var log = startTestWorkout(now = finishedAt - 3_600_000, ids = SequentialIds(id)).copy(id = id)
    val sets = log.exercises[0].sets
    val values = listOf(warmUpKg to 10) + working
    sets.zip(values).forEach { (set, v) ->
        log = (WorkoutLogReducer.edit(log, set.id, SetField.WEIGHT, v.first.toString()) as WorkoutLogReducer.EditResult.Changed).log
        log = (WorkoutLogReducer.edit(log, set.id, SetField.REPS, v.second.toString()) as WorkoutLogReducer.EditResult.Changed).log
        log = WorkoutLogReducer.toggleComplete(log, set.id, finishedAt - 60_000)
    }
    return WorkoutLogReducer.finish(log, finishedAt)
}

class WorkoutRulesTest {

    @Test
    fun previousPerformanceIsTheLastWorkingSetOfTheLatestWorkout() {
        val older = benchWorkout("a", 1_000_000_000, 60.0, listOf(80.0 to 8, 80.0 to 6))
        val newer = benchWorkout("b", 2_000_000_000, 60.0, listOf(82.5 to 8, 85.0 to 5))
        val previous = PreviousPerformance.lastWorkingSet("bench", listOf(older, newer))
        assertEquals(85_000, previous?.weightGrams)
        assertEquals(5, previous?.reps)
        assertNull(PreviousPerformance.lastWorkingSet("squat", listOf(older, newer)))
    }

    @Test
    fun volumeAndRecordsIgnoreWarmUps() {
        val history = listOf(benchWorkout("a", 1_000_000_000, 60.0, listOf(80.0 to 8, 80.0 to 6)))
        // The warm-up is heavier than anything before, but it never counts.
        val today = benchWorkout("b", 2_000_000_000, 100.0, listOf(80.0 to 8, 80.0 to 5))
        assertTrue(PersonalRecords.find(today, history).isEmpty())
        val summary = WorkoutSummary.of(today, 0)
        assertEquals(80_000L * 8 + 80_000L * 5, summary.volumeGrams)
    }

    @Test
    fun aHeavierWorkingSetIsARecordAndMoreRepsAtTheSameWeightToo() {
        val history = listOf(benchWorkout("a", 1_000_000_000, 60.0, listOf(80.0 to 8, 82.5 to 6)))
        val heavier = benchWorkout("b", 2_000_000_000, 60.0, listOf(80.0 to 8, 85.0 to 3))
        assertEquals(85_000, PersonalRecords.find(heavier, history).single().set.weightGrams)
        val moreReps = benchWorkout("c", 2_000_000_000, 60.0, listOf(80.0 to 8, 82.5 to 7))
        assertEquals(1, PersonalRecords.find(moreReps, history).size)
    }

    @Test
    fun theFirstWorkoutWithAnExerciseIsNotARecord() {
        val first = benchWorkout("a", 1_000_000_000, 60.0, listOf(80.0 to 8, 82.5 to 6))
        assertTrue(PersonalRecords.find(first, emptyList()).isEmpty())
    }

    @Test
    fun summaryComparesPlannedAndDone() {
        var log = startTestWorkout()
        val bench = log.exercises[0]
        log = WorkoutLogReducer.toggleComplete(log, bench.sets[1].id, 2_000_000)
        log = WorkoutLogReducer.addSet(log, bench.id, "extra")
        log = WorkoutLogReducer.toggleComplete(log, "extra", 2_100_000)
        log = WorkoutLogReducer.finish(log, 1_000_000 + 45 * 60_000)
        val summary = WorkoutSummary.of(log, 0)
        assertEquals(45 * 60_000L, summary.durationMs)
        assertEquals(2, summary.completedSets)
        assertEquals(4, summary.plannedSets)
        val benchSummary = summary.exercises[0]
        assertEquals(2, benchSummary.completedSets)
        assertEquals(3, benchSummary.plannedSets)
        // One of three planned sets is done; the extra set does not inflate the plan percentage.
        assertEquals(33, benchSummary.planPercent)
        assertEquals(82_500, benchSummary.maxWeightGrams)
    }

    @Test
    fun progressPlotsTheBestWorkingSetPerWorkoutOldestFirst() {
        val a = benchWorkout("a", 1_000_000_000, 90.0, listOf(80.0 to 8, 82.5 to 5))
        val b = benchWorkout("b", 2_000_000_000, 60.0, listOf(82.5 to 8, 82.5 to 6))
        val points = ProgressRules.bestSetPerWorkout("bench", listOf(b, a))
        assertEquals(listOf("a", "b"), points.map { it.workoutId })
        // The 90 kg warm-up of "a" is ignored.
        assertEquals(82_500, points[0].set.weightGrams)
        assertEquals(5, points[0].set.reps)
        assertEquals(8, points[1].set.reps)
        assertEquals(82_500 to 8, ProgressRules.records(listOf(a, b)).single().let { it.set.weightGrams to it.set.reps })
    }

    @Test
    fun unfinishedWorkoutsAreNotProgress() {
        var log = startTestWorkout()
        log = WorkoutLogReducer.toggleComplete(log, log.exercises[0].sets[1].id, 10)
        assertTrue(ProgressRules.bestSetPerWorkout("bench", listOf(log)).isEmpty())
    }

    @Test
    fun measurementsMustBePositiveLabelledNumbers() {
        assertEquals(MeasurementValidator.Result.Valid(72_500), MeasurementValidator.validate(MeasurementKind.BODY_WEIGHT, "72,5"))
        assertEquals(MeasurementValidator.Result.Valid(80_000), MeasurementValidator.validate(MeasurementKind.WAIST, "80"))
        assertIs<MeasurementValidator.Result.Blank>(MeasurementValidator.validate(MeasurementKind.WAIST, " "))
        assertIs<MeasurementValidator.Result.Invalid>(MeasurementValidator.validate(MeasurementKind.WAIST, "-5"))
        assertIs<MeasurementValidator.Result.OutOfRange>(MeasurementValidator.validate(MeasurementKind.BODY_WEIGHT, "0"))
        assertIs<MeasurementValidator.Result.OutOfRange>(MeasurementValidator.validate(MeasurementKind.BODY_WEIGHT, "999"))
    }

    @Test
    fun warmUpsAreNotWorkingSets() {
        val log = startTestWorkout()
        assertEquals(SetType.WARM_UP, log.exercises[0].sets[0].type)
        assertTrue(!log.exercises[0].sets[0].isWorking)
    }
}
