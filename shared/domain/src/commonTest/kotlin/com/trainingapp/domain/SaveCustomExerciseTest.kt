package com.trainingapp.domain

import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.ExerciseStatus
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.domain.common.CurrentUser
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.domain.exercise.DemoVideoChange
import com.trainingapp.domain.exercise.ExerciseInput
import com.trainingapp.domain.media.MediaState
import com.trainingapp.domain.media.PickedVideo
import com.trainingapp.domain.media.VideoProblem
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveCustomExerciseTest {

    private val repository = FakeExerciseRepository(listOf(exercise("global", "Bench press")))
    private val inUse = mutableSetOf<String>()
    private val user = object : CurrentUser { override val trainerId = "t1" }
    private val media = FakeMediaRepository()
    private val save = SaveCustomExercise(repository, media, ExerciseUsage { it in inUse }, user, SequentialIds("ex"), TimeSource { 42 })
    private val video = PickedVideo(localPath = "/videos/hip.mp4", sizeBytes = 5_000_000, durationMs = 20_000, thumbnailPath = null)
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
    fun demoVideoIsQueuedAndAttachedThenKeptOrRemoved() = runTest {
        val created = assertIs<SaveExerciseResult.Saved>(save(null, input.copy(demoVideo = DemoVideoChange.Replace(video)), "pl")).exercise
        val mediaId = assertNotNull(created.demoMediaId)
        assertEquals(MediaState.QUEUED, media.assets.value.getValue(mediaId).state)

        val kept = assertIs<SaveExerciseResult.Saved>(save(created.id, input.copy(name = "Hip thrust (maszyna)"), "pl")).exercise
        assertEquals(mediaId, kept.demoMediaId)

        val removed = assertIs<SaveExerciseResult.Saved>(save(created.id, input.copy(demoVideo = DemoVideoChange.Remove), "pl")).exercise
        assertNull(removed.demoMediaId)
    }

    @Test
    fun tooLongOrTooLargeVideoIsRejectedBeforeAnythingIsSaved() = runTest {
        val tooLong = save(null, input.copy(demoVideo = DemoVideoChange.Replace(video.copy(durationMs = 61_000))), "pl")
        assertEquals(SaveExerciseResult.VideoInvalid(VideoProblem.TOO_LONG), tooLong)
        val tooLarge = save(null, input.copy(demoVideo = DemoVideoChange.Replace(video.copy(sizeBytes = 101L * 1024 * 1024))), "pl")
        assertEquals(SaveExerciseResult.VideoInvalid(VideoProblem.TOO_LARGE), tooLarge)
        assertTrue(media.assets.value.isEmpty())
        assertEquals(1, repository.exercises.value.size)
    }

    @Test
    fun invalidInputIsRejected() = runTest {
        assertIs<SaveExerciseResult.Invalid>(save(null, input.copy(name = "x", equipment = null), "pl"))
        assertEquals(1, repository.exercises.value.size)
    }
}
