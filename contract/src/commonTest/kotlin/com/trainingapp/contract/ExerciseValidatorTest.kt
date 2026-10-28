package com.trainingapp.contract

import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.ExerciseField
import com.trainingapp.contract.exercise.ExerciseIssue
import com.trainingapp.contract.exercise.ExerciseProblem
import com.trainingapp.contract.exercise.ExerciseValidator
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.exercise.UpsertExerciseRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class ExerciseValidatorTest {

    @Test
    fun completeExerciseIsValid() {
        assertEquals(
            emptyList(),
            ExerciseValidator.validate("Hip thrust na maszynie", MuscleGroup.GLUTES, Equipment.MACHINE, TrackingType.WEIGHT_REPS),
        )
    }

    @Test
    fun requiredFieldsAreReported() {
        assertEquals(
            listOf(
                ExerciseIssue(ExerciseField.NAME, ExerciseProblem.REQUIRED),
                ExerciseIssue(ExerciseField.PRIMARY_MUSCLE, ExerciseProblem.REQUIRED),
                ExerciseIssue(ExerciseField.EQUIPMENT, ExerciseProblem.REQUIRED),
                ExerciseIssue(ExerciseField.TRACKING_TYPE, ExerciseProblem.REQUIRED),
            ),
            ExerciseValidator.validate(" ", null, null, null),
        )
    }

    @Test
    fun nameMustBeTwoToEightyCharacters() {
        fun nameIssue(name: String) =
            ExerciseValidator.validate(name, MuscleGroup.CHEST, Equipment.BARBELL, TrackingType.WEIGHT_REPS).singleOrNull()?.problem
        assertEquals(ExerciseProblem.TOO_SHORT, nameIssue("a"))
        assertEquals(null, nameIssue("ab"))
        assertEquals(null, nameIssue("a".repeat(80)))
        assertEquals(ExerciseProblem.TOO_LONG, nameIssue("a".repeat(81)))
    }

    @Test
    fun primaryMuscleCannotAlsoBeSecondary() {
        val issues = ExerciseValidator.validate(
            "Wiosłowanie", MuscleGroup.LATS, Equipment.CABLE, TrackingType.WEIGHT_REPS, secondaryMuscles = listOf(MuscleGroup.LATS),
        )
        assertEquals(listOf(ExerciseIssue(ExerciseField.SECONDARY_MUSCLES, ExerciseProblem.INVALID)), issues)
    }

    @Test
    fun serverSideRequestValidationChecksEveryLocaleName() {
        val request = UpsertExerciseRequest(
            id = "e1",
            names = mapOf("pl" to "Martwy ciąg", "en" to "x"),
            primaryMuscle = MuscleGroup.HAMSTRINGS,
            equipment = Equipment.BARBELL,
            trackingType = TrackingType.WEIGHT_REPS,
        )
        assertEquals(listOf(ExerciseIssue(ExerciseField.NAME, ExerciseProblem.TOO_SHORT)), ExerciseValidator.validate(request))
    }
}
