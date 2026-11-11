package com.trainingapp.contract.sync

import com.trainingapp.contract.api.IsoInstant
import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.plan.PlanVersionDto
import kotlinx.serialization.Serializable

/*
 * Today's workout, assigned plans and the offline workout-log sync (plan §8.3–§8.4). Ids of logs, exercises, sets
 * and measurements are generated on the phone, so a retried upload hits the same row.
 */

/** `GET /v1/client/today` (204 when there is nothing to do today). */
@Serializable
data class TodayWorkoutDto(
    val planVersion: PlanVersionDto,
    val dayId: String,
    val assignedPlanId: String,
    val sessionId: String? = null,
    val place: String? = null,
)

@Serializable
enum class AssignedPlanStatusDto { ACTIVE, REPLACED, ENDED }

@Serializable
data class AssignedPlanDto(
    val id: String,
    val trainerId: String,
    val status: AssignedPlanStatusDto,
    val sessionId: String? = null,
    val assignedAt: IsoInstant,
    val planVersion: PlanVersionDto,
)

@Serializable
enum class WorkoutStateDto { IN_PROGRESS, FINISHED, DISCARDED }

@Serializable
data class WorkoutLogDto(
    val id: String,
    val sessionId: String? = null,
    val assignedPlanId: String? = null,
    val planVersionId: String? = null,
    val planDayId: String? = null,
    val name: String,
    val state: WorkoutStateDto,
    val startedAt: IsoInstant,
    val finishedAt: IsoInstant? = null,
    val timeZone: String,
    val place: String? = null,
    val note: String? = null,
    /** Server-owned; ignored in uploads. */
    val revision: Int = 0,
    val updatedAt: IsoInstant? = null,
    val deletedAt: IsoInstant? = null,
)

@Serializable
data class WorkoutExerciseDto(
    val id: String,
    val workoutLogId: String,
    val position: Int,
    val exerciseId: String,
    val plannedExerciseId: String? = null,
    val supersetGroup: String? = null,
    val note: String? = null,
    val updatedAt: IsoInstant? = null,
    val deletedAt: IsoInstant? = null,
)

@Serializable
data class SetLogDto(
    val id: String,
    val workoutExerciseId: String,
    val position: Int,
    val type: SetType,
    val plannedSetId: String? = null,
    val weightGrams: Int? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val durationSeconds: Int? = null,
    val distanceMeters: Int? = null,
    val completedAt: IsoInstant? = null,
    val techniqueMediaId: String? = null,
    val revision: Int = 0,
    val updatedAt: IsoInstant? = null,
    val deletedAt: IsoInstant? = null,
)

@Serializable
enum class SyncOperationType { UPSERT_LOG, UPSERT_EXERCISE, UPSERT_SET, DELETE_SET, DELETE_EXERCISE, FINISH_LOG, DISCARD_LOG }

/**
 * One queued change. [entityId] is the phone's id of the log, exercise or set. [baseRevision] is the server revision
 * the change was based on (0 for a row the server has never seen). The payload matching [type] is set:
 * [log] for UPSERT_LOG/FINISH_LOG, [exercise] for UPSERT_EXERCISE, [set] for UPSERT_SET.
 */
@Serializable
data class SyncOperationDto(
    val operationId: String,
    val type: SyncOperationType,
    val entityId: String,
    val baseRevision: Int = 0,
    val log: WorkoutLogDto? = null,
    val exercise: WorkoutExerciseDto? = null,
    val set: SetLogDto? = null,
)

/** `POST /v1/client/workout-sync`. Each operation is applied on its own; a replayed batch returns the stored result. */
@Serializable
data class WorkoutSyncRequest(val operationId: String, val operations: List<SyncOperationDto>)

@Serializable
enum class SyncOutcome {
    APPLIED,

    /** The server already had exactly this change; nothing was written. */
    DUPLICATE,

    /** The server row moved on since [SyncOperationDto.baseRevision]; the current row is returned. */
    CONFLICT,

    /** Not allowed or invalid (not the caller's log, unknown exercise, plan day that does not exist). */
    REJECTED,
}

@Serializable
data class SyncResultDto(
    val operationId: String,
    val outcome: SyncOutcome,
    val revision: Int? = null,
    val reason: String? = null,
    val serverLog: WorkoutLogDto? = null,
    val serverSet: SetLogDto? = null,
)

@Serializable
data class WorkoutSyncResponse(val results: List<SyncResultDto>)

/**
 * `GET /v1/client/workouts?since=` — rows changed after the cursor, tombstones included. Pass [cursor] back as `since`;
 * the server applies a small overlap window, so the app ignores rows whose revision it already has.
 */
@Serializable
data class WorkoutChangesDto(
    val logs: List<WorkoutLogDto>,
    val exercises: List<WorkoutExerciseDto>,
    val sets: List<SetLogDto>,
    val cursor: IsoInstant,
)

@Serializable
enum class MeasurementKindDto { BODY_WEIGHT, WAIST, HIPS }

/** Values in thousandths of the kind's unit (kg for body weight, cm otherwise). */
@Serializable
data class MeasurementDto(
    val id: String,
    val kind: MeasurementKindDto,
    val valueThousandths: Long,
    val measuredAt: IsoInstant,
    val updatedAt: IsoInstant? = null,
    val deletedAt: IsoInstant? = null,
)

@Serializable
data class MeasurementUpsertDto(val kind: MeasurementKindDto, val valueThousandths: Long, val measuredAt: IsoInstant)

@Serializable
data class MeasurementChangesDto(val measurements: List<MeasurementDto>, val cursor: IsoInstant)
