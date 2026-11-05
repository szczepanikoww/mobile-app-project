package com.trainingapp.domain

import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlannedExerciseDto
import com.trainingapp.contract.plan.PlannedSetDto
import com.trainingapp.domain.workout.RestTimer
import com.trainingapp.domain.workout.SetField
import com.trainingapp.domain.workout.WorkoutLog
import com.trainingapp.domain.workout.WorkoutLogReducer
import com.trainingapp.domain.workout.WorkoutLogReducer.EditResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal val testDay = PlanDayDto(
    id = "day1",
    name = "Push A",
    exercises = listOf(
        PlannedExerciseDto(
            "pe1", "bench", restSeconds = 120, rpeEnabled = true,
            sets = listOf(
                PlannedSetDto("ps1", SetType.WARM_UP, repsMin = 10, repsMax = 10, weightGrams = 60_000),
                PlannedSetDto("ps2", repsMin = 8, repsMax = 8, weightGrams = 80_000),
                PlannedSetDto("ps3", repsMin = 8, repsMax = 10, weightGrams = 82_500, restSecondsOverride = 150),
            ),
        ),
        PlannedExerciseDto("pe2", "plank", sets = listOf(PlannedSetDto("ps4", durationSeconds = 60))),
    ),
)

internal fun startTestWorkout(now: Long = 1_000_000L, ids: SequentialIds = SequentialIds("w")): WorkoutLog =
    WorkoutLogReducer.startFromPlan(
        id = "log1", clientId = "client1", name = "Push A", planVersionId = "v1", day = testDay,
        trackingTypes = mapOf("bench" to TrackingType.WEIGHT_REPS, "plank" to TrackingType.TIME),
        nowEpochMs = now, ids = ids,
    )

class WorkoutLogReducerTest {

    @Test
    fun plannedValuesPrefillTheSets() {
        val log = startTestWorkout()
        val bench = log.exercises[0]
        assertEquals(listOf(60_000, 80_000, 82_500), bench.sets.map { it.weightGrams })
        // A planned range pre-fills its lower bound.
        assertEquals(listOf(10, 8, 8), bench.sets.map { it.reps })
        assertEquals(10, bench.sets[2].planned?.repsMax)
        assertEquals(TrackingType.TIME, log.exercises[1].trackingType)
        assertEquals(60, log.exercises[1].sets[0].durationSeconds)
        assertTrue(log.allSets.none { it.isCompleted })
    }

    @Test
    fun tickingCompletesTheSetAndStartsRestFromStoredEndTime() {
        val log = startTestWorkout()
        val set = log.exercises[0].sets[1]
        val ticked = WorkoutLogReducer.toggleComplete(log, set.id, nowEpochMs = 2_000_000L)
        assertEquals(2_000_000L, ticked.set(set.id)?.completedAtEpochMs)
        assertEquals(RestTimer(2_000_000L + 120_000L, set.id, 120), ticked.rest)
    }

    @Test
    fun setRestOverrideWinsOverExerciseRest() {
        val log = startTestWorkout()
        val set = log.exercises[0].sets[2]
        val ticked = WorkoutLogReducer.toggleComplete(log, set.id, 0)
        assertEquals(150, ticked.rest?.totalSeconds)
    }

    @Test
    fun tickingAgainUnticksAndStopsItsRest() {
        val log = startTestWorkout()
        val set = log.exercises[0].sets[1]
        val ticked = WorkoutLogReducer.toggleComplete(log, set.id, 10)
        val unticked = WorkoutLogReducer.toggleComplete(ticked, set.id, 20)
        assertFalse(unticked.set(set.id)!!.isCompleted)
        assertNull(unticked.rest)
    }

    @Test
    fun aSetWithoutRequiredValuesCannotBeTicked() {
        val log = startTestWorkout()
        val set = log.exercises[0].sets[1]
        val cleared = (WorkoutLogReducer.edit(log, set.id, SetField.REPS, "") as EditResult.Changed).log
        assertSame(cleared, WorkoutLogReducer.toggleComplete(cleared, set.id, 10))
    }

    @Test
    fun editingParsesPolishAndEnglishDecimalsAndRejectsGarbage() {
        val log = startTestWorkout()
        val set = log.exercises[0].sets[1]
        val comma = WorkoutLogReducer.edit(log, set.id, SetField.WEIGHT, "82,5")
        assertEquals(82_500, (comma as EditResult.Changed).log.set(set.id)?.weightGrams)
        val dot = WorkoutLogReducer.edit(log, set.id, SetField.RPE, "8.5")
        assertEquals(8.5, (dot as EditResult.Changed).log.set(set.id)?.rpe)
        assertIs<EditResult.Invalid>(WorkoutLogReducer.edit(log, set.id, SetField.WEIGHT, "8x"))
        assertIs<EditResult.Invalid>(WorkoutLogReducer.edit(log, set.id, SetField.REPS, "8-10"))
        assertIs<EditResult.Invalid>(WorkoutLogReducer.edit(log, set.id, SetField.RPE, "11"))
    }

    @Test
    fun addedSetCopiesTheLastSetAndIsNotPlanned() {
        val log = startTestWorkout()
        val bench = log.exercises[0]
        val added = WorkoutLogReducer.addSet(log, bench.id, "new")
        val newSet = added.set("new")
        assertNotNull(newSet)
        assertEquals(82_500, newSet.weightGrams)
        assertEquals(SetType.WORKING, newSet.type)
        assertNull(newSet.planned)
        assertEquals(4, added.exercises[0].sets.size)
    }

    @Test
    fun removingASetRemovesItAndItsRest() {
        val log = startTestWorkout()
        val set = log.exercises[0].sets[1]
        val ticked = WorkoutLogReducer.toggleComplete(log, set.id, 0)
        val removed = WorkoutLogReducer.removeSet(ticked, set.id)
        assertNull(removed.set(set.id))
        assertNull(removed.rest)
    }

    @Test
    fun theLastSetOfTheWorkoutStartsNoRest() {
        var log = startTestWorkout()
        log.allSets.forEach { log = WorkoutLogReducer.toggleComplete(log, it.id, 10) }
        assertNull(log.rest)
        assertTrue(log.allSets.all { it.isCompleted })
    }

    @Test
    fun finishingKeepsTheFirstFinishTime() {
        val log = startTestWorkout()
        val finished = WorkoutLogReducer.finish(log, 5_000)
        assertTrue(finished.isFinished)
        assertEquals(5_000, WorkoutLogReducer.finish(finished, 9_000).finishedAtEpochMs)
    }

    @Test
    fun noteIsLimited() {
        val log = WorkoutLogReducer.setNote(startTestWorkout(), "a".repeat(800))
        assertEquals(WorkoutLogReducer.NOTE_MAX_LENGTH, log.note.length)
    }
}

class RestTimerTest {
    @Test
    fun timeLeftComesFromTheStoredEndTime() {
        val timer = RestTimer.start("s1", 90, nowEpochMs = 10_000)
        assertEquals(90, timer.remainingSeconds(10_000))
        assertEquals(60, timer.remainingSeconds(40_000))
        // 0.5 s left is still shown as one second.
        assertEquals(1, timer.remainingSeconds(99_500))
        assertTrue(timer.isOver(100_000))
        // "Restored" much later: never negative.
        assertEquals(0, timer.remainingSeconds(1_000_000))
    }

    @Test
    fun adjustingMovesTheEndButNeverIntoThePast() {
        val timer = RestTimer.start("s1", 30, nowEpochMs = 0)
        assertEquals(45_000, timer.adjusted(15, 0).endsAtEpochMs)
        assertEquals(15_000, timer.adjusted(-15, 0).endsAtEpochMs)
        assertEquals(20_000, timer.adjusted(-15, 20_000).endsAtEpochMs)
    }

    @Test
    fun reducerAdjustsAndSkips() {
        val log = startTestWorkout()
        val set = log.exercises[0].sets[1]
        val resting = WorkoutLogReducer.toggleComplete(log, set.id, 0)
        assertEquals(135_000, WorkoutLogReducer.adjustRest(resting, 15, 0).rest?.endsAtEpochMs)
        assertNull(WorkoutLogReducer.skipRest(resting).rest)
    }
}
