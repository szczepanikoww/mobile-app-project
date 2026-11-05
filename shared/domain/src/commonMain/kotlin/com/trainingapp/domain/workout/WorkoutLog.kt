package com.trainingapp.domain.workout

import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.exercise.TrackingType
import kotlinx.serialization.Serializable

enum class WorkoutState { IN_PROGRESS, FINISHED }

/**
 * Where a workout log is on its way to the server. Only [LOCAL] is produced in this phase (no backend); the other
 * states are modelled so sync can plug in without changing the screens.
 */
enum class WorkoutSyncState { LOCAL, PENDING, SYNCED, FAILED, CONFLICT }

/**
 * One workout the client logged: from an assigned plan day or independent (no plan, no session).
 * Every change is written to the device at once, so a killed app resumes the workout.
 */
data class WorkoutLog(
    val id: String,
    val clientId: String,
    val name: String,
    val sessionId: String? = null,
    /** The assigned, immutable plan version this workout follows. `null` for an independent workout. */
    val planVersionId: String? = null,
    val planDayId: String? = null,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val note: String = "",
    val state: WorkoutState = WorkoutState.IN_PROGRESS,
    val syncState: WorkoutSyncState = WorkoutSyncState.LOCAL,
    val exercises: List<LoggedExercise>,
    val currentExerciseIndex: Int = 0,
    val rest: RestTimer? = null,
    /** Where the workout took place, e.g. the gym of the linked session. Display only. */
    val place: String? = null,
) {
    val isFinished: Boolean get() = state == WorkoutState.FINISHED
    val allSets: List<SetLog> get() = exercises.flatMap { it.sets }
    val currentExercise: LoggedExercise? get() = exercises.getOrNull(currentExerciseIndex)

    fun exercise(entryId: String): LoggedExercise? = exercises.firstOrNull { it.id == entryId }
    fun set(setId: String): SetLog? = allSets.firstOrNull { it.id == setId }
    fun exerciseOfSet(setId: String): LoggedExercise? = exercises.firstOrNull { e -> e.sets.any { it.id == setId } }
}

/** One exercise in a workout. [plannedExerciseId] links it to the plan, so planned and done can be compared. */
data class LoggedExercise(
    val id: String,
    val exerciseId: String,
    val trackingType: TrackingType,
    val plannedExerciseId: String? = null,
    /** Rest after a set, from the plan; [RestTimer.DEFAULT_SECONDS] when the plan has none. */
    val restSeconds: Int? = null,
    val rpeEnabled: Boolean = false,
    val sets: List<SetLog>,
) {
    val plannedSetCount: Int get() = sets.count { it.planned != null }
    val completedSets: List<SetLog> get() = sets.filter { it.isCompleted }

    /** The first set that is not ticked yet: the "Teraz" set. */
    val currentSet: SetLog? get() = sets.firstOrNull { !it.isCompleted }
}

/**
 * One set. [id] is generated on the device and never changes, so saving or syncing it again updates one record.
 * Weights are integer grams and distances integer metres, as in [com.trainingapp.domain.plan.SetInput].
 */
data class SetLog(
    val id: String,
    val type: SetType = SetType.WORKING,
    val planned: PlannedValues? = null,
    val weightGrams: Int? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val durationSeconds: Int? = null,
    val distanceMeters: Int? = null,
    val completedAtEpochMs: Long? = null,
    val techniqueMediaId: String? = null,
) {
    val isCompleted: Boolean get() = completedAtEpochMs != null

    /** Warm-ups never count towards volume, records or progress. */
    val isWorking: Boolean get() = type != SetType.WARM_UP
}

/** What the plan asked for, copied when the workout starts, so later plan edits never change the log. */
@Serializable
data class PlannedValues(
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val weightGrams: Int? = null,
    val durationSeconds: Int? = null,
    val distanceMeters: Int? = null,
    val rpe: Double? = null,
    val restSeconds: Int? = null,
)

/** A logged measurement of the client's body. Private health data: never logged, sent to analytics or put in push text. */
data class BodyMeasurement(
    val id: String,
    val clientId: String,
    val kind: MeasurementKind,
    /** Thousandths of [MeasurementKind.unit]: 72.5 kg is 72_500. */
    val valueThousandths: Int,
    val measuredAtEpochMs: Long,
) {
    override fun toString() = "BodyMeasurement(redacted)"
}

enum class MeasurementKind(val unit: MeasurementUnit) {
    BODY_WEIGHT(MeasurementUnit.KG),
    WAIST(MeasurementUnit.CM),
    HIPS(MeasurementUnit.CM),
}

enum class MeasurementUnit { KG, CM }
