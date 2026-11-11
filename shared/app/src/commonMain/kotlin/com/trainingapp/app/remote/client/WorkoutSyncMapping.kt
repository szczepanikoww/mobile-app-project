package com.trainingapp.app.remote.client

import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.sync.MeasurementDto
import com.trainingapp.contract.sync.MeasurementKindDto
import com.trainingapp.contract.sync.SetLogDto
import com.trainingapp.contract.sync.WorkoutExerciseDto
import com.trainingapp.contract.sync.WorkoutLogDto
import com.trainingapp.contract.sync.WorkoutStateDto
import com.trainingapp.domain.workout.BodyMeasurement
import com.trainingapp.domain.workout.LoggedExercise
import com.trainingapp.domain.workout.MeasurementKind
import com.trainingapp.domain.workout.SetLog
import com.trainingapp.domain.workout.WorkoutLog
import com.trainingapp.domain.workout.WorkoutState
import com.trainingapp.domain.workout.WorkoutSyncState
import kotlin.time.Instant

/** A local workout log as the three kinds of sync payload. Revisions are server-owned and left at 0 here. */
internal data class LogBundle(
    val log: WorkoutLogDto,
    val exercises: List<WorkoutExerciseDto>,
    val sets: List<SetLogDto>,
)

private fun ms(value: Long) = Instant.fromEpochMilliseconds(value)

internal fun WorkoutLog.toBundle(timeZone: String): LogBundle {
    val finished = state == WorkoutState.FINISHED
    val logDto = WorkoutLogDto(
        id = id,
        sessionId = sessionId,
        planVersionId = planVersionId,
        planDayId = planDayId,
        name = name.trim().take(80).ifBlank { "Trening" },
        state = if (finished) WorkoutStateDto.FINISHED else WorkoutStateDto.IN_PROGRESS,
        startedAt = ms(startedAtEpochMs),
        finishedAt = if (finished) ms(finishedAtEpochMs ?: startedAtEpochMs) else null,
        timeZone = timeZone,
        place = place?.take(120),
        note = note.takeIf { it.isNotBlank() }?.take(2000),
    )
    val exerciseDtos = exercises.mapIndexed { index, e ->
        WorkoutExerciseDto(id = e.id, workoutLogId = id, position = index, exerciseId = e.exerciseId, plannedExerciseId = e.plannedExerciseId)
    }
    val setDtos = exercises.flatMap { e -> e.sets.mapIndexed { index, s -> s.toDto(e.id, index) } }
    return LogBundle(logDto, exerciseDtos, setDtos)
}

private fun SetLog.toDto(exerciseEntryId: String, position: Int) = SetLogDto(
    id = id,
    workoutExerciseId = exerciseEntryId,
    position = position,
    type = type,
    weightGrams = weightGrams,
    reps = reps,
    rpe = rpe,
    durationSeconds = durationSeconds,
    distanceMeters = distanceMeters,
    completedAt = completedAtEpochMs?.let(::ms),
    techniqueMediaId = techniqueMediaId,
)

/**
 * A finished workout the server has and this device does not (new install). The plan's tracking type is unknown here,
 * so it is inferred from the values that were logged.
 */
internal fun fromServer(
    clientId: String,
    log: WorkoutLogDto,
    exercises: List<WorkoutExerciseDto>,
    sets: List<SetLogDto>,
): WorkoutLog {
    val setsByExercise = sets.filter { it.deletedAt == null }.groupBy { it.workoutExerciseId }
    val logged = exercises.filter { it.deletedAt == null }.sortedBy { it.position }.map { e ->
        val own = setsByExercise[e.id].orEmpty().sortedBy { it.position }
        LoggedExercise(
            id = e.id,
            exerciseId = e.exerciseId,
            trackingType = when {
                own.any { it.distanceMeters != null } -> TrackingType.DISTANCE_TIME
                own.any { it.weightGrams != null } -> TrackingType.WEIGHT_REPS
                own.any { it.durationSeconds != null && it.reps == null } -> TrackingType.TIME
                else -> TrackingType.REPS
            },
            plannedExerciseId = e.plannedExerciseId,
            rpeEnabled = own.any { it.rpe != null },
            sets = own.map {
                SetLog(
                    id = it.id, type = it.type, weightGrams = it.weightGrams, reps = it.reps, rpe = it.rpe,
                    durationSeconds = it.durationSeconds, distanceMeters = it.distanceMeters,
                    completedAtEpochMs = it.completedAt?.toEpochMilliseconds(), techniqueMediaId = it.techniqueMediaId,
                )
            },
        )
    }
    return WorkoutLog(
        id = log.id,
        clientId = clientId,
        name = log.name,
        sessionId = log.sessionId,
        planVersionId = log.planVersionId,
        planDayId = log.planDayId,
        startedAtEpochMs = log.startedAt.toEpochMilliseconds(),
        finishedAtEpochMs = log.finishedAt?.toEpochMilliseconds(),
        note = log.note.orEmpty(),
        state = WorkoutState.FINISHED,
        syncState = WorkoutSyncState.SYNCED,
        exercises = logged,
        place = log.place,
    )
}

internal fun MeasurementKind.toDto() = when (this) {
    MeasurementKind.BODY_WEIGHT -> MeasurementKindDto.BODY_WEIGHT
    MeasurementKind.WAIST -> MeasurementKindDto.WAIST
    MeasurementKind.HIPS -> MeasurementKindDto.HIPS
}

internal fun MeasurementDto.toLocal(clientId: String) = BodyMeasurement(
    id = id,
    clientId = clientId,
    kind = when (kind) {
        MeasurementKindDto.BODY_WEIGHT -> MeasurementKind.BODY_WEIGHT
        MeasurementKindDto.WAIST -> MeasurementKind.WAIST
        MeasurementKindDto.HIPS -> MeasurementKind.HIPS
    },
    valueThousandths = valueThousandths.toInt(),
    measuredAtEpochMs = measuredAt.toEpochMilliseconds(),
)
