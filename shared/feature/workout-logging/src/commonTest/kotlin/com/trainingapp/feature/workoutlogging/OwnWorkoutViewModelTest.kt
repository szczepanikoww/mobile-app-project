package com.trainingapp.feature.workoutlogging

import androidx.lifecycle.ViewModelStore
import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.plan.PlanBodyDto
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlanVersionDto
import com.trainingapp.contract.plan.PlannedExerciseDto
import com.trainingapp.contract.plan.PlannedSetDto
import com.trainingapp.domain.workout.TechniqueVideoOutbox
import com.trainingapp.domain.workout.TodayWorkout
import com.trainingapp.domain.workout.TodayWorkoutSource
import com.trainingapp.feature.workoutbuilder.common.ExercisePickerCoordinator
import com.trainingapp.feature.workoutlogging.logger.LiveDialog
import com.trainingapp.feature.workoutlogging.logger.LivePhase
import com.trainingapp.feature.workoutlogging.logger.LiveWorkoutEvent
import com.trainingapp.feature.workoutlogging.logger.LiveWorkoutViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Własny trening" (as in Strong): start empty, add and remove exercises while logging, plan or not. */
class OwnWorkoutViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val day = PlanDayDto(
        "d1", "Push A",
        listOf(PlannedExerciseDto("pe1", "bench", restSeconds = 60, sets = listOf(PlannedSetDto("s1", SetType.WORKING, 8, 8, 80_000)))),
    )
    private val withPlan = TodayWorkoutSource { TodayWorkout(PlanVersionDto("v1", "t1", 1, 0, body = PlanBodyDto("Plan", listOf(day))), "d1") }
    private val noPlan = TodayWorkoutSource { null }

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun before() = Dispatchers.setMain(dispatcher)
    @AfterTest fun after() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    private fun live(workouts: FakeWorkouts, today: TodayWorkoutSource, picker: ExercisePickerCoordinator) =
        LiveWorkoutViewModel(workouts, today, NoExercises, NoMedia, TechniqueVideoOutbox.None, TestClient, FakeNotifier(), FakeClock(1_000_000), FakeIds(), Polish, picker)
            .also { vm -> stores += ViewModelStore().also { it.put("live", vm) } }

    private fun TestScope.events(vm: LiveWorkoutViewModel) = mutableListOf<LiveWorkoutEvent>().also { list ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { list += it } }
    }

    /** What the library does when the client picks [ids] and taps "Dodaj". */
    private fun ExercisePickerCoordinator.pick(key: String, vararg ids: String) {
        ids.forEach { select(key, it) }
        finish(key)
    }

    @Test
    fun withoutAPlanAnEmptyWorkoutStartsAndOpensThePicker() = runTest(dispatcher) {
        val workouts = FakeWorkouts()
        val picker = ExercisePickerCoordinator()
        val vm = live(workouts, noPlan, picker)
        val events = events(vm)
        advanceUntilIdle()
        assertEquals(LivePhase.NoPlan, vm.state.value.phase)

        vm.startEmpty("Własny trening")
        vm.startEmpty("Własny trening")
        advanceUntilIdle()
        val log = vm.state.value.log!!
        assertEquals(LivePhase.Active, vm.state.value.phase)
        assertEquals("Własny trening", log.name)
        assertNull(log.planVersionId)
        assertTrue(log.exercises.isEmpty())
        // Saved on the device at once, like any workout, and only one was started.
        assertEquals(setOf(log.id), workouts.logs.value.keys)
        val request = assertIs<LiveWorkoutEvent.PickExercises>(events.single())

        picker.pick(request.requestKey, "squat", "row")
        advanceUntilIdle()
        val grown = vm.state.value.log!!
        assertEquals(listOf("squat", "row"), grown.exercises.map { it.exerciseId })
        assertTrue(grown.exercises.all { it.sets.size == 1 })
        assertEquals(grown, workouts.logs.value.getValue(log.id))

        // The selection is consumed once: a later emission adds nothing.
        picker.pick("other-request", "lunge")
        advanceUntilIdle()
        assertEquals(2, vm.state.value.log!!.exercises.size)
    }

    @Test
    fun aPlanWorkoutGrowsAndShrinksWithoutChangingThePlan() = runTest(dispatcher) {
        val workouts = FakeWorkouts()
        val picker = ExercisePickerCoordinator()
        val vm = live(workouts, withPlan, picker)
        val events = events(vm)
        advanceUntilIdle()
        vm.start()
        vm.onAddExerciseClick()
        advanceUntilIdle()
        picker.pick(assertIs<LiveWorkoutEvent.PickExercises>(events.single()).requestKey, "row")
        advanceUntilIdle()

        val log = vm.state.value.log!!
        assertEquals(listOf("bench", "row"), log.exercises.map { it.exerciseId })
        assertEquals(1, log.currentExerciseIndex)
        assertEquals("v1", log.planVersionId)

        // A ticked set is lost on removal, so it is confirmed first.
        val bench = log.exercises[0]
        vm.onToggleSet(bench.sets[0].id)
        vm.onRemoveExerciseClick(bench.id)
        assertEquals(LiveDialog.ConfirmRemoveExercise(bench.id, 1), vm.state.value.dialog)
        vm.confirmRemoveExercise()
        advanceUntilIdle()
        assertEquals(listOf("row"), vm.state.value.log!!.exercises.map { it.exerciseId })
        assertNull(vm.state.value.dialog)

        // An exercise without ticked sets goes at once.
        vm.onRemoveExerciseClick(vm.state.value.log!!.exercises[0].id)
        advanceUntilIdle()
        assertTrue(vm.state.value.log!!.exercises.isEmpty())
        assertTrue(workouts.logs.value.values.single().exercises.isEmpty())
    }

    @Test
    fun finishingAWorkoutWithoutExercisesOffersToDiscardIt() = runTest(dispatcher) {
        val vm = live(FakeWorkouts(), noPlan, ExercisePickerCoordinator())
        advanceUntilIdle()
        vm.startEmpty("Własny trening")
        vm.onFinishClick()
        assertEquals(LiveDialog.ConfirmDiscard, vm.state.value.dialog)
    }

    @Test
    fun anEmptyWorkoutCanAlsoReplaceTodaysPlan() = runTest(dispatcher) {
        val vm = live(FakeWorkouts(), withPlan, ExercisePickerCoordinator())
        advanceUntilIdle()
        assertEquals(LivePhase.NotStarted, vm.state.value.phase)
        vm.startEmpty("Własny trening")
        assertNull(vm.state.value.log!!.planVersionId)
        assertTrue(vm.state.value.log!!.exercises.isEmpty())
    }
}
