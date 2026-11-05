package com.trainingapp.feature.workoutlogging

import com.trainingapp.domain.workout.TechniqueVideoOutbox
import com.trainingapp.domain.workout.TodayWorkout
import com.trainingapp.domain.workout.TodayWorkoutSource
import com.trainingapp.domain.workout.WorkoutState
import com.trainingapp.feature.workoutlogging.logger.LivePhase
import com.trainingapp.feature.workoutlogging.logger.LiveWorkoutViewModel
import com.trainingapp.feature.workoutlogging.summary.WorkoutSummaryViewModel
import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.plan.PlanBodyDto
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlanVersionDto
import com.trainingapp.contract.plan.PlannedExerciseDto
import com.trainingapp.contract.plan.PlannedSetDto
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkoutViewModelsTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val day = PlanDayDto(
        "d1", "Push A",
        listOf(
            PlannedExerciseDto(
                "pe1", "bench", restSeconds = 60,
                sets = listOf(
                    PlannedSetDto("s1", SetType.WORKING, 8, 8, 80_000),
                    PlannedSetDto("s2", SetType.WORKING, 8, 8, 80_000),
                ),
            ),
        ),
    )
    private val today = TodayWorkoutSource { TodayWorkout(PlanVersionDto("v1", "t1", 1, 0, body = PlanBodyDto("Plan", listOf(day))), "d1") }

    @BeforeTest fun before() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun after() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    private fun live(workouts: FakeWorkouts, notifier: FakeNotifier, clock: FakeClock) =
        LiveWorkoutViewModel(workouts, today, NoExercises, NoMedia, TechniqueVideoOutbox.None, TestClient, notifier, clock, FakeIds(), Polish)
            .also { vm -> stores += ViewModelStore().also { it.put("live", vm) } }

    @Test
    fun restStartSchedulesAdjustReschedulesAndSkipCancels() = runTest {
        val workouts = FakeWorkouts()
        val notifier = FakeNotifier()
        val clock = FakeClock(1_000_000)
        val vm = live(workouts, notifier, clock)
        advanceUntilIdle()
        assertEquals(LivePhase.NotStarted, vm.state.value.phase)
        vm.start()
        val first = vm.state.value.log!!.allSets.first().id
        vm.onToggleSet(first)
        vm.onAdjustRest(15)
        vm.onSkipRest()
        advanceUntilIdle()
        assertEquals(listOf("schedule:1060000", "schedule:1075000", "cancel"), notifier.calls)
        // Every change was written to the device.
        assertNotNull(workouts.logs.value.values.single().allSets.first().completedAtEpochMs)
    }

    @Test
    fun restoredWorkoutWithFutureRestReschedulesOnce() = runTest {
        val workouts = FakeWorkouts()
        val clock = FakeClock(1_000_000)
        val first = live(workouts, FakeNotifier(), clock)
        advanceUntilIdle()
        first.start()
        first.onToggleSet(first.state.value.log!!.allSets.first().id)
        advanceUntilIdle()

        // "Process death": a new view model reads the stored workout.
        clock.now += 10_000
        val notifier = FakeNotifier()
        val restored = live(workouts, notifier, clock)
        advanceUntilIdle()
        assertEquals(LivePhase.Active, restored.state.value.phase)
        assertEquals(listOf("schedule:1060000"), notifier.calls)
    }

    @Test
    fun saveRunsOnceOnADoubleTap() = runTest {
        val workouts = FakeWorkouts()
        val clock = FakeClock()
        val vm = live(workouts, FakeNotifier(), clock)
        advanceUntilIdle()
        vm.start()
        vm.onToggleSet(vm.state.value.log!!.allSets.first().id)
        vm.confirmFinish()
        advanceUntilIdle()
        val id = workouts.logs.value.keys.single()
        assertEquals(WorkoutState.IN_PROGRESS, workouts.logs.value.getValue(id).state)

        val summary = WorkoutSummaryViewModel(WorkoutSummaryDestination(id), workouts, NoExercises, TestClient, clock, Polish)
            .also { s -> stores += ViewModelStore().also { it.put("summary", s) } }
        advanceUntilIdle()
        summary.onNoteChange("Ciężko, ale dobrze")
        val before = workouts.saves
        summary.save()
        summary.save()
        advanceUntilIdle()
        assertEquals(before + 1, workouts.saves)
        assertEquals(WorkoutState.FINISHED, workouts.logs.value.getValue(id).state)
        assertEquals("Ciężko, ale dobrze", workouts.logs.value.getValue(id).note)
        assertNull(workouts.logs.value.getValue(id).rest)
    }
}
