package com.trainingapp.contract

import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.plan.ExerciseSettingField
import com.trainingapp.contract.plan.PlanBodyDto
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlanIssue
import com.trainingapp.contract.plan.PlanValidator
import com.trainingapp.contract.plan.PlannedExerciseDto
import com.trainingapp.contract.plan.PlannedSetDto
import com.trainingapp.contract.plan.SetField
import com.trainingapp.contract.plan.SetProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanValidatorTest {

    private val tracking = mapOf(
        "bench" to TrackingType.WEIGHT_REPS,
        "pushup" to TrackingType.REPS,
        "plank" to TrackingType.TIME,
        "run" to TrackingType.DISTANCE_TIME,
    )

    private fun validate(body: PlanBodyDto) = PlanValidator.validate(body) { tracking[it] }

    private fun set(id: String = "s1", repsMin: Int? = 8, repsMax: Int? = repsMin, weightGrams: Int? = null, rpe: Double? = null,
                    durationSeconds: Int? = null, distanceMeters: Int? = null) =
        PlannedSetDto(id, SetType.WORKING, repsMin, repsMax, weightGrams, durationSeconds, distanceMeters, rpe)

    private fun exercise(exerciseId: String = "bench", sets: List<PlannedSetDto> = listOf(set()), rpeEnabled: Boolean = false,
                         restSeconds: Int? = null, tempo: String? = null) =
        PlannedExerciseDto("pe1", exerciseId, rpeEnabled = rpeEnabled, restSeconds = restSeconds, tempo = tempo, sets = sets)

    private fun body(vararg exercises: PlannedExerciseDto, name: String = "Góra A") =
        PlanBodyDto(name, listOf(PlanDayDto("d1", "Dzień A", exercises.toList())))

    @Test
    fun validPlanHasNoIssues() {
        assertEquals(emptyList(), validate(body(exercise(sets = listOf(set(weightGrams = 82_500, repsMin = 8, repsMax = 10))))))
    }

    @Test
    fun nameIsRequiredAndLimitedTo80Characters() {
        assertEquals(listOf<PlanIssue>(PlanIssue.NameBlank), validate(body(exercise(), name = "   ")))
        assertEquals(listOf<PlanIssue>(PlanIssue.NameTooLong), validate(body(exercise(), name = "a".repeat(81))))
        assertEquals(emptyList(), validate(body(exercise(), name = "a".repeat(80))))
    }

    @Test
    fun planNeedsADayAndEachDayAnExerciseAndEachExerciseASet() {
        assertTrue(PlanIssue.NoDays in validate(PlanBodyDto("Plan", emptyList())))
        assertEquals(listOf<PlanIssue>(PlanIssue.DayHasNoExercises("d1")), validate(body()))
        assertEquals(listOf<PlanIssue>(PlanIssue.ExerciseHasNoSets("d1", "pe1")), validate(body(exercise(sets = emptyList()))))
    }

    @Test
    fun repsMustBeWithinOneToHundredWithMinNotAboveMax() {
        fun repsProblem(min: Int?, max: Int?) =
            validate(body(exercise(sets = listOf(set(repsMin = min, repsMax = max))))).filterIsInstance<PlanIssue.SetValueInvalid>()
                .singleOrNull()?.problem

        assertEquals(SetProblem.REQUIRED, repsProblem(null, null))
        assertEquals(SetProblem.OUT_OF_RANGE, repsProblem(0, 0))
        assertEquals(SetProblem.OUT_OF_RANGE, repsProblem(8, 101))
        assertEquals(SetProblem.INVALID, repsProblem(10, 8))
        assertEquals(null, repsProblem(1, 100))
        assertEquals(null, repsProblem(8, null))
    }

    @Test
    fun weightMustBeWithinZeroToThousandKilograms() {
        val tooHeavy = validate(body(exercise(sets = listOf(set(weightGrams = 1_000_001)))))
        assertEquals(listOf<PlanIssue>(PlanIssue.SetValueInvalid("d1", "pe1", "s1", SetField.WEIGHT, SetProblem.OUT_OF_RANGE)), tooHeavy)
        assertEquals(emptyList(), validate(body(exercise(sets = listOf(set(weightGrams = 0))))))
        assertEquals(emptyList(), validate(body(exercise(sets = listOf(set(weightGrams = 1_000_000))))))
    }

    @Test
    fun rpeMustBeOneToTenInHalfSteps() {
        fun rpeIssues(rpe: Double) = validate(body(exercise(rpeEnabled = true, sets = listOf(set(rpe = rpe)))))
        assertEquals(emptyList(), rpeIssues(8.5))
        assertEquals(emptyList(), rpeIssues(1.0))
        assertEquals(emptyList(), rpeIssues(10.0))
        assertEquals(SetProblem.INVALID, (rpeIssues(8.3).single() as PlanIssue.SetValueInvalid).problem)
        assertEquals(SetProblem.OUT_OF_RANGE, (rpeIssues(10.5).single() as PlanIssue.SetValueInvalid).problem)
        assertEquals(SetProblem.OUT_OF_RANGE, (rpeIssues(0.5).single() as PlanIssue.SetValueInvalid).problem)
    }

    @Test
    fun requiredValueFollowsTrackingType() {
        val repsOnly = validate(body(exercise("pushup", sets = listOf(set(repsMin = 12)))))
        assertEquals(emptyList(), repsOnly)

        val plankWithoutTime = validate(body(exercise("plank", sets = listOf(set(repsMin = null)))))
        assertEquals(SetField.DURATION, (plankWithoutTime.single() as PlanIssue.SetValueInvalid).field)
        assertEquals(emptyList(), validate(body(exercise("plank", sets = listOf(set(repsMin = null, durationSeconds = 60))))))

        val runWithoutDistance = validate(body(exercise("run", sets = listOf(set(repsMin = null, durationSeconds = 600)))))
        assertEquals(SetField.DISTANCE, (runWithoutDistance.single() as PlanIssue.SetValueInvalid).field)
        assertEquals(emptyList(), validate(body(exercise("run", sets = listOf(set(repsMin = null, distanceMeters = 5_000))))))
    }

    @Test
    fun unknownExerciseIsReported() {
        assertEquals(listOf<PlanIssue>(PlanIssue.UnknownExercise("d1", "pe1")), validate(body(exercise("missing"))))
    }

    @Test
    fun exerciseSettingsAreRangeChecked() {
        val issues = validate(body(exercise(restSeconds = 3_601, tempo = "3".repeat(21))))
        assertEquals(
            setOf(ExerciseSettingField.REST, ExerciseSettingField.TEMPO),
            issues.filterIsInstance<PlanIssue.ExerciseSettingInvalid>().map { it.field }.toSet(),
        )
    }
}
