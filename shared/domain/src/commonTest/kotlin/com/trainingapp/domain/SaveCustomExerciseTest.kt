package com.trainingapp.domain

import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.ExerciseStatus
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.domain.common.CurrentUser
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.domain.exercise.ExerciseInput
import com.trainingapp.domain.exercise.ExerciseUsage
import com.trainingapp.domain.exercise.SaveCustomExercise
import com.trainingapp.domain.exercise.SaveExerciseResult
import com.trainingapp.domain.exercise.SetExerciseHidden
import com.trainingapp.domain.exercise.SyncState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SaveCustomExerciseTest {

    private val repository = FakeExerciseRepository(listOf(exercise("global", "Bench press")))
    private val inUse = mutableSetOf<String>()
    private val user = object : CurrentUser { override val trainerId = "t1" }
    private val save = SaveCustomExercise(repository, ExerciseUsage { it in inUse }, user, SequentialIds("ex"), TimeSource { 42 })
    private val input = ExerciseInput("Hip thrust na maszynie", MuscleGroup.GLUTES, Equipment.MACHINE, TrackingType.WEIGHT_REPS,
        instructions = "Oprzyj łopatki.\n\nWypchnij biodra.")

    @Test
    fun createdExerciseIsOwnPendingAndInTheTrainersLanguage() = runTest {
        val saved = assertIs<SaveExerciseResult.Saved>(save(null, input, "pl")).exercise
        assertEquals("t1", saved.ownerTrainerId)
        assertEquals(SyncState.PENDING, saved.syncState)
        assertEquals(mapOf("pl" to "Hip thrust na maszynie"), saved.names)
        assertEquals("Hip thrust na maszynie", saved.name("en"))
        assertEquals(listOf("Oprzyj łopatki.", "Wypchnij biodra."), saved.instructions("pl"))
        assertEquals(saved, repository.getExercise(saved.id))
    }

    @Test
    fun savingTheSameIdTwiceUpdatesOneRecord() = runTest {
        val created = assertIs<SaveExerciseResult.Saved>(save(null, input, "pl")).exercise
        assertIs<SaveExerciseResult.Saved>(save(created.id, input.copy(name = "Hip thrust (maszyna)"), "pl"))
        assertEquals(2, repository.exercises.value.size)
        assertEquals("Hip thrust (maszyna)", repository.getExercise(created.id)?.name("pl"))
    }

    @Test
    fun trackingTypeIsLockedOnceTheExerciseIsInUse() = runTest {
        val created = assertIs<SaveExerciseResult.Saved>(save(null, input, "pl")).exercise
        inUse += created.id
        assertEquals(SaveExerciseResult.TrackingTypeLocked, save(created.id, input.copy(trackingType = TrackingType.REPS), "pl"))
        assertIs<SaveExerciseResult.Saved>(save(created.id, input.copy(name = "Nowa nazwa"), "pl"))
    }

    @Test
    fun onlyTheOwnerCanEditOrHide() = runTest {
        assertEquals(SaveExerciseResult.NotOwner, save("global", input, "pl"))
        val hide = SetExerciseHidden(repository, user)
        assertFalse(hide("global", true))
        val created = assertIs<SaveExerciseResult.Saved>(save(null, input, "pl")).exercise
        assertTrue(hide(created.id, true))
        assertEquals(ExerciseStatus.HIDDEN, repository.getExercise(created.id)?.status)
        assertTrue(hide(created.id, false))
        assertEquals(ExerciseStatus.ACTIVE, repository.getExercise(created.id)?.status)
    }

    @Test
    fun invalidInputIsRejected() = runTest {
        assertIs<SaveExerciseResult.Invalid>(save(null, input.copy(name = "x", equipment = null), "pl"))
        assertEquals(1, repository.exercises.value.size)
    }
}
