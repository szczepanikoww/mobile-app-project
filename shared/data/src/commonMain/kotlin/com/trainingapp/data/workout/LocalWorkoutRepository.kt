package com.trainingapp.data.workout

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.data.db.DataJson
import com.trainingapp.data.db.Set_log
import com.trainingapp.data.db.TrainingDatabase
import com.trainingapp.data.db.Workout_log
import com.trainingapp.domain.workout.BodyMeasurement
import com.trainingapp.domain.workout.LoggedExercise
import com.trainingapp.domain.workout.MeasurementKind
import com.trainingapp.domain.workout.MeasurementRepository
import com.trainingapp.domain.workout.PlannedValues
import com.trainingapp.domain.workout.RestTimer
import com.trainingapp.domain.workout.SetLog
import com.trainingapp.domain.workout.WorkoutLog
import com.trainingapp.domain.workout.WorkoutRepository
import com.trainingapp.domain.workout.WorkoutState
import com.trainingapp.domain.workout.WorkoutSyncState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Workout logs in the local SQLite database. A log and all of its sets are written in one transaction on every
 * change; set rows are keyed by their device-generated id, so saving again never duplicates a set.
 */
class LocalWorkoutRepository(
    private val db: TrainingDatabase,
    private val io: CoroutineDispatcher,
) : WorkoutRepository {

    private val queries get() = db.workoutQueries

    override fun observeActive(clientId: String): Flow<WorkoutLog?> =
        queries.selectActiveWorkout(clientId).asFlow().mapToList(io).map { rows -> rows.firstOrNull()?.let { load(it) } }

    override suspend fun getActive(clientId: String): WorkoutLog? = withContext(io) {
        queries.selectActiveWorkout(clientId).executeAsOneOrNull()?.let { load(it) }
    }

    override fun observe(id: String): Flow<WorkoutLog?> =
        queries.selectWorkout(id).asFlow().mapToList(io).map { rows -> rows.firstOrNull()?.let { load(it) } }

    override suspend fun get(id: String): WorkoutLog? = withContext(io) {
        queries.selectWorkout(id).executeAsOneOrNull()?.let { load(it) }
    }

    override suspend fun save(log: WorkoutLog): Unit = withContext(io) {
        db.transaction {
            queries.upsertWorkout(
                id = log.id,
                client_id = log.clientId,
                name = log.name,
                session_id = log.sessionId,
                plan_version_id = log.planVersionId,
                plan_day_id = log.planDayId,
                started_at = log.startedAtEpochMs,
                finished_at = log.finishedAtEpochMs,
                note = log.note,
                state = log.state.name,
                sync_state = log.syncState.name,
                current_exercise = log.currentExerciseIndex.toLong(),
                rest_ends_at = log.rest?.endsAtEpochMs,
                rest_after_set_id = log.rest?.afterSetId,
                rest_total_seconds = log.rest?.totalSeconds?.toLong(),
                place = log.place,
                exercises_json = DataJson.encodeToString(log.exercises.map { it.toStored() }),
            )
            val keep = mutableSetOf<String>()
            var order = 0L
            log.exercises.forEach { exercise ->
                exercise.sets.forEach { set ->
                    keep += set.id
                    queries.upsertSet(
                        id = set.id,
                        workout_log_id = log.id,
                        exercise_entry_id = exercise.id,
                        exercise_id = exercise.exerciseId,
                        set_order = order++,
                        type = set.type.name,
                        planned_json = set.planned?.let { DataJson.encodeToString(it) },
                        weight_grams = set.weightGrams?.toLong(),
                        reps = set.reps?.toLong(),
                        rpe = set.rpe,
                        duration_seconds = set.durationSeconds?.toLong(),
                        distance_meters = set.distanceMeters?.toLong(),
                        completed_at = set.completedAtEpochMs,
                        technique_media_id = set.techniqueMediaId,
                    )
                }
            }
            queries.selectSetIds(log.id).executeAsList().filterNot { it in keep }.forEach { queries.deleteSet(it) }
        }
    }

    override suspend fun delete(id: String): Unit = withContext(io) {
        db.transaction {
            queries.deleteSetsOfWorkout(id)
            queries.deleteWorkout(id)
        }
    }

    override fun observeHistory(clientId: String): Flow<List<WorkoutLog>> =
        queries.selectFinishedWorkouts(clientId).asFlow().mapToList(io).map { rows -> rows.map { load(it) } }

    override suspend fun history(clientId: String): List<WorkoutLog> = withContext(io) {
        queries.selectFinishedWorkouts(clientId).executeAsList().map { load(it) }
    }

    private fun load(row: Workout_log): WorkoutLog {
        val sets = queries.selectSets(row.id).executeAsList().groupBy { it.exercise_entry_id }
        val exercises = DataJson.decodeFromString<List<StoredExercise>>(row.exercises_json).map { stored ->
            LoggedExercise(
                id = stored.id,
                exerciseId = stored.exerciseId,
                trackingType = stored.trackingType,
                plannedExerciseId = stored.plannedExerciseId,
                restSeconds = stored.restSeconds,
                rpeEnabled = stored.rpeEnabled,
                sets = sets[stored.id].orEmpty().map { it.toSetLog() },
            )
        }
        val restEnd = row.rest_ends_at
        val restSet = row.rest_after_set_id
        return WorkoutLog(
            id = row.id,
            clientId = row.client_id,
            name = row.name,
            sessionId = row.session_id,
            planVersionId = row.plan_version_id,
            planDayId = row.plan_day_id,
            startedAtEpochMs = row.started_at,
            finishedAtEpochMs = row.finished_at,
            note = row.note,
            state = WorkoutState.valueOf(row.state),
            syncState = WorkoutSyncState.valueOf(row.sync_state),
            exercises = exercises,
            currentExerciseIndex = row.current_exercise.toInt().coerceIn(0, (exercises.size - 1).coerceAtLeast(0)),
            rest = if (restEnd != null && restSet != null) RestTimer(restEnd, restSet, (row.rest_total_seconds ?: 0).toInt()) else null,
            place = row.place,
        )
    }

    private fun Set_log.toSetLog() = SetLog(
        id = id,
        type = SetType.valueOf(type),
        planned = planned_json?.let { DataJson.decodeFromString<PlannedValues>(it) },
        weightGrams = weight_grams?.toInt(),
        reps = reps?.toInt(),
        rpe = rpe,
        durationSeconds = duration_seconds?.toInt(),
        distanceMeters = distance_meters?.toInt(),
        completedAtEpochMs = completed_at,
        techniqueMediaId = technique_media_id,
    )

    @Serializable
    private data class StoredExercise(
        val id: String,
        val exerciseId: String,
        val trackingType: TrackingType,
        val plannedExerciseId: String? = null,
        val restSeconds: Int? = null,
        val rpeEnabled: Boolean = false,
    )

    private fun LoggedExercise.toStored() = StoredExercise(id, exerciseId, trackingType, plannedExerciseId, restSeconds, rpeEnabled)
}

/** Body measurements on this device. Private health data: never logged. */
class LocalMeasurementRepository(
    private val db: TrainingDatabase,
    private val io: CoroutineDispatcher,
) : MeasurementRepository {
    override fun observe(clientId: String): Flow<List<BodyMeasurement>> =
        db.workoutQueries.selectMeasurements(clientId).asFlow().mapToList(io).map { rows ->
            rows.map { BodyMeasurement(it.id, it.client_id, MeasurementKind.valueOf(it.kind), it.value_thousandths.toInt(), it.measured_at) }
        }

    override suspend fun add(measurement: BodyMeasurement): Unit = withContext(io) {
        db.workoutQueries.insertMeasurement(
            measurement.id,
            measurement.clientId,
            measurement.kind.name,
            measurement.valueThousandths.toLong(),
            measurement.measuredAtEpochMs,
        )
    }
}
