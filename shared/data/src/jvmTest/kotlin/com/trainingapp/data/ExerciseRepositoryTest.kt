package com.trainingapp.data

import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.ExerciseStatus
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.exercise.UpsertExerciseRequest
import com.trainingapp.data.db.createTrainingDatabase
import com.trainingapp.data.exercise.ExerciseRemoteDataSource
import com.trainingapp.data.exercise.ExerciseSeedParser
import com.trainingapp.data.exercise.LocalExerciseRepository
import com.trainingapp.domain.exercise.ExerciseFilter
import com.trainingapp.domain.exercise.ExerciseInput
import com.trainingapp.domain.exercise.ExerciseSearch
import com.trainingapp.domain.exercise.ExerciseUsage
import com.trainingapp.domain.exercise.SaveCustomExercise
import com.trainingapp.domain.exercise.SaveExerciseResult
import com.trainingapp.domain.exercise.SyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExerciseRepositoryTest {

    private class RecordingRemote(var succeed: Boolean = true) : ExerciseRemoteDataSource {
        val upserts = mutableListOf<UpsertExerciseRequest>()

        /** When set, the server does not answer until it completes. */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun upsert(request: UpsertExerciseRequest): Boolean {
            gate?.await()
            upserts += request
            return succeed
        }
        override suspend fun setHidden(id: String, hidden: Boolean) = succeed
    }

    private val seed = """[{"id":"g1","source":"free-exercise-db","sourceId":"Barbell_Hip_Thrust","names":{"en":"Barbell Hip Thrust","pl":"Hip thrust ze sztangą"},
        |"primaryMuscles":["GLUTES"],"equipment":"BARBELL","trackingType":"WEIGHT_REPS","featured":true}]""".trimMargin()

    private val network = TestNetwork()
    private val remote = RecordingRemote()

    /**
     * Runs [block] with a repository whose background sync runs on the test scheduler. The scope is not
     * `backgroundScope`, because `advanceUntilIdle()` does not wait for background work.
     */
    private fun repositoryTest(block: suspend TestScope.(LocalExerciseRepository) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        try {
            block(LocalExerciseRepository(createTrainingDatabase(memoryDriver()), { seed }, remote, network, TestClock(), scope, dispatcher))
        } finally {
            scope.cancel()
        }
    }

    private fun TestScope.save(repository: LocalExerciseRepository) =
        SaveCustomExercise(repository, ExerciseUsage { false }, trainer, TestIds("ex"), TestClock())

    private val input = ExerciseInput("Hip thrust na maszynie", MuscleGroup.GLUTES, Equipment.MACHINE, TrackingType.WEIGHT_REPS)

    @Test
    fun createdExerciseIsPendingThenSyncedInTheBackground() = repositoryTest { repository ->
        val response = CompletableDeferred<Unit>().also { remote.gate = it }
        val saved = assertIs<SaveExerciseResult.Saved>(save(repository)(null, input, "pl")).exercise
        advanceUntilIdle()
        assertEquals(SyncState.PENDING, repository.getExercise(saved.id)?.syncState)
        response.complete(Unit)
        advanceUntilIdle()
        assertEquals(SyncState.SYNCED, repository.getExercise(saved.id)?.syncState)
        assertEquals(listOf(saved.id), remote.upserts.map { it.id })
        // Global and own exercises are listed together, so the new exercise can be added to a plan at once.
        assertEquals(setOf("g1", saved.id), repository.observeExercises().first().map { it.id }.toSet())
    }

    @Test
    fun offlineExerciseStaysPendingAndSyncsWhenBackOnline() = repositoryTest { repository ->
        network.isOnline.value = false
        advanceUntilIdle()
        val saved = assertIs<SaveExerciseResult.Saved>(save(repository)(null, input, "pl")).exercise
        advanceUntilIdle()
        assertEquals(SyncState.PENDING, repository.getExercise(saved.id)?.syncState)
        assertTrue(remote.upserts.isEmpty())

        network.isOnline.value = true
        advanceUntilIdle()
        assertEquals(SyncState.SYNCED, repository.getExercise(saved.id)?.syncState)
    }

    @Test
    fun serverFailureIsShownAsFailedAndRetried() = repositoryTest { repository ->
        remote.succeed = false
        val saved = assertIs<SaveExerciseResult.Saved>(save(repository)(null, input, "pl")).exercise
        advanceUntilIdle()
        assertEquals(SyncState.FAILED, repository.getExercise(saved.id)?.syncState)

        remote.succeed = true
        assertEquals(emptyList(), repository.syncPending())
        assertEquals(SyncState.SYNCED, repository.getExercise(saved.id)?.syncState)
    }

    @Test
    fun savingTheSameIdTwiceKeepsOneRecord() = repositoryTest { repository ->
        val save = save(repository)
        val saved = assertIs<SaveExerciseResult.Saved>(save(null, input, "pl")).exercise
        save(saved.id, input.copy(name = "Hip thrust (maszyna)"), "pl")
        advanceUntilIdle()
        assertEquals(1, repository.observeExercises().first().count { it.isCustom })
        assertEquals(setOf(saved.id), remote.upserts.map { it.id }.toSet())
    }

    @Test
    fun hiddenExerciseStillResolvesButIsNotSearchable() = repositoryTest { repository ->
        val saved = assertIs<SaveExerciseResult.Saved>(save(repository)(null, input, "pl")).exercise
        repository.setHidden(saved.id, true)
        advanceUntilIdle()
        assertEquals(ExerciseStatus.HIDDEN, repository.getExercise(saved.id)?.status)
        val results = ExerciseSearch.search(repository.observeExercises().first(), ExerciseFilter("hip thrust"), "pl")
        assertEquals(listOf("g1"), results.map { it.id })
    }

    @Test
    fun recentlyUsedKeepsSelectionOrderNewestFirst() = repositoryTest { repository ->
        repository.markUsed(listOf("a", "b"))
        repository.markUsed(listOf("c"))
        assertEquals(listOf("c", "b", "a"), repository.observeRecentlyUsedIds().first())
    }

    @Test
    fun bundledSeedParsesWithPolishNamesAndUniqueIds() {
        val path = System.getProperty("exerciseSeedPath")
        val exercises = ExerciseSeedParser.parse(File(path).readText())
        assertTrue(exercises.size >= 150, "curated subset has ${exercises.size} exercises")
        assertEquals(exercises.size, exercises.map { it.id }.toSet().size)
        assertTrue(exercises.all { it.names["pl"]?.isNotBlank() == true && it.featured && it.primaryMuscles.isNotEmpty() })
        assertTrue(exercises.any { it.trackingType == TrackingType.TIME })
        assertTrue(exercises.any { it.trackingType == TrackingType.DISTANCE_TIME })
    }
}
