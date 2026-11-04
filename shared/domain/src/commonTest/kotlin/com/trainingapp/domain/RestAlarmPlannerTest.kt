package com.trainingapp.domain

import com.trainingapp.domain.workout.RestAlarmCommand
import com.trainingapp.domain.workout.RestAlarmPlanner
import com.trainingapp.domain.workout.WorkoutLogReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RestAlarmPlannerTest {
    private val log = startTestWorkout()
    private val set = log.exercises[0].sets[1]
    private val resting = WorkoutLogReducer.toggleComplete(log, set.id, 0)

    @Test
    fun restStartSchedulesAtTheStoredEnd() {
        assertEquals(RestAlarmCommand.Schedule("log1", 120_000), RestAlarmPlanner.onChange(log, resting, 0))
    }

    @Test
    fun adjustingReschedules() {
        val later = WorkoutLogReducer.adjustRest(resting, 15, 1_000)
        assertEquals(RestAlarmCommand.Schedule("log1", 135_000), RestAlarmPlanner.onChange(resting, later, 1_000))
    }

    @Test
    fun skipEarlyTickFinishAndDiscardCancel() {
        assertEquals(RestAlarmCommand.Cancel("log1"), RestAlarmPlanner.onChange(resting, WorkoutLogReducer.skipRest(resting), 1_000))
        // Ticking the next set early starts a new rest: the alarm moves to the new end.
        val next = resting.exercises[0].sets[2]
        val early = WorkoutLogReducer.toggleComplete(resting, next.id, 30_000)
        assertEquals(RestAlarmCommand.Schedule("log1", 30_000 + 150_000), RestAlarmPlanner.onChange(resting, early, 30_000))
        assertEquals(RestAlarmCommand.Cancel("log1"), RestAlarmPlanner.onChange(resting, WorkoutLogReducer.end(resting, 40_000), 40_000))
        assertEquals(RestAlarmCommand.Cancel("log1"), RestAlarmPlanner.onChange(resting, null, 40_000))
    }

    @Test
    fun unrelatedEditsDoNotReschedule() {
        val edited = WorkoutLogReducer.setNote(resting, "ok")
        assertEquals(RestAlarmCommand.None, RestAlarmPlanner.onChange(resting, edited, 5_000))
    }

    @Test
    fun restoredFutureRestReschedulesOnceAndPastRestCancels() {
        assertEquals(RestAlarmCommand.Schedule("log1", 120_000), RestAlarmPlanner.onRestore(resting, 60_000))
        assertEquals(RestAlarmCommand.Cancel("log1"), RestAlarmPlanner.onRestore(resting, 200_000))
        assertEquals(RestAlarmCommand.None, RestAlarmPlanner.onRestore(log, 0))
    }

    @Test
    fun endThenResumeKeepsTheWorkoutOpenAndFinishKeepsTheEndTime() {
        val ended = WorkoutLogReducer.end(resting, 50_000)
        assertNull(ended.rest)
        assertEquals(50_000, ended.finishedAtEpochMs)
        assertNull(WorkoutLogReducer.resume(ended).finishedAtEpochMs)
        assertEquals(50_000, WorkoutLogReducer.finish(ended, 90_000).finishedAtEpochMs)
    }
}
