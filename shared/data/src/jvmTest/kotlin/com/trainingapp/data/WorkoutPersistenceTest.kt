package com.trainingapp.data

import com.trainingapp.data.db.createTrainingDatabase
import com.trainingapp.data.workout.DemoWorkoutData
import com.trainingapp.data.workout.LocalMeasurementRepository
import com.trainingapp.data.workout.LocalWorkoutRepository
import com.trainingapp.domain.workout.BodyMeasurement
import com.trainingapp.domain.workout.MeasurementKind
import com.trainingapp.domain.workout.SetField
import com.trainingapp.domain.workout.WorkoutLogReducer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutPersistenceTest {

    private fun start(ids: TestIds = TestIds("w")) = WorkoutLogReducer.startFromPlan(
        "log1", "client1", "Push A", DemoWorkoutData.planVersion.id, DemoWorkoutData.pushDay,
        DemoWorkoutData.trackingTypes, 1_000L, ids,
    )

    @Test
    fun inProgressWorkoutSurvivesRecreatingTheRepository() = runBlocking {
        val file = File.createTempFile("workout", ".db").also { it.delete(); it.deleteOnExit() }
        var log = start()
        val set = log.exercises[0].sets[1]
        log = (WorkoutLogReducer.edit(log, set.id, SetField.WEIGHT, "82,5") as WorkoutLogReducer.EditResult.Changed).log
        log = WorkoutLogReducer.toggleComplete(log, set.id, 5_000)
        log = WorkoutLogReducer.attachTechniqueVideo(log, set.id, "media-1")
        log = WorkoutLogReducer.selectExercise(log, 1)

        val first = LocalWorkoutRepository(createTrainingDatabase(fileDriver(file)), Dispatchers.IO)
        first.save(log)

        // A new database connection, as after process death.
        val restored = LocalWorkoutRepository(createTrainingDatabase(fileDriver(file)), Dispatchers.IO).getActive("client1")
        assertNotNull(restored)
        assertEquals(log, restored)
        assertEquals(82_500, restored.set(set.id)?.weightGrams)
        assertEquals(5_000L + 120_000L, restored.rest?.endsAtEpochMs)
        assertEquals(1, restored.currentExerciseIndex)
    }

    @Test
    fun savingAgainNeverDuplicatesSets() = runBlocking {
        val db = createTrainingDatabase(memoryDriver())
        val repo = LocalWorkoutRepository(db, Dispatchers.IO)
        var log = start()
        repo.save(log)
        repo.save(log)
        log = WorkoutLogReducer.toggleComplete(log, log.allSets.first().id, 10)
        repo.save(log)
        val count = db.workoutQueries.selectSets("log1").executeAsList().size
        assertEquals(log.allSets.size, count)
        assertEquals(log.allSets.map { it.id }, repo.get("log1")!!.allSets.map { it.id })
    }

    @Test
    fun removedSetsAreDeletedAndOrderIsKept() = runBlocking {
        val repo = LocalWorkoutRepository(createTrainingDatabase(memoryDriver()), Dispatchers.IO)
        var log = start()
        val bench = log.exercises[0]
        log = WorkoutLogReducer.addSet(log, bench.id, "added")
        log = WorkoutLogReducer.removeSet(log, bench.sets[1].id)
        repo.save(log)
        val stored = repo.get("log1")!!
        assertEquals(log.exercises[0].sets.map { it.id }, stored.exercises[0].sets.map { it.id })
        assertEquals("added", stored.exercises[0].sets.last().id)
    }

    @Test
    fun finishedWorkoutsFormTheHistoryAndDiscardDeletes() = runBlocking {
        val repo = LocalWorkoutRepository(createTrainingDatabase(memoryDriver()), Dispatchers.IO)
        val log = start()
        repo.save(log)
        assertTrue(repo.history("client1").isEmpty())
        repo.save(WorkoutLogReducer.finish(log, 9_000))
        assertNull(repo.getActive("client1"))
        assertEquals(listOf("log1"), repo.observeHistory("client1").first().map { it.id })
        repo.delete("log1")
        assertNull(repo.get("log1"))
    }

    @Test
    fun measurementsAreStoredNewestFirst() = runBlocking {
        val repo = LocalMeasurementRepository(createTrainingDatabase(memoryDriver()), Dispatchers.IO)
        repo.add(BodyMeasurement("m1", "client1", MeasurementKind.BODY_WEIGHT, 74_000, 1_000))
        repo.add(BodyMeasurement("m2", "client1", MeasurementKind.WAIST, 80_500, 2_000))
        repo.add(BodyMeasurement("m3", "other", MeasurementKind.WAIST, 90_000, 3_000))
        assertEquals(listOf("m2", "m1"), repo.observe("client1").first().map { it.id })
    }

    @Test
    fun demoSeedRunsOnceAndProducesProgress() = runBlocking {
        val db = createTrainingDatabase(memoryDriver())
        val workouts = LocalWorkoutRepository(db, Dispatchers.IO)
        val measurements = LocalMeasurementRepository(db, Dispatchers.IO)
        val ids = TestIds("seed")
        DemoWorkoutData.seedIfEmpty("client1", workouts, measurements, ids, nowEpochMs = 100L * 24 * 3_600_000)
        DemoWorkoutData.seedIfEmpty("client1", workouts, measurements, ids, nowEpochMs = 100L * 24 * 3_600_000)
        assertEquals(4, workouts.history("client1").size)
        assertEquals(6, measurements.observe("client1").first().size)
    }
}
