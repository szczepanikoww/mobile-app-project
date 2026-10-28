package com.trainingapp.contract.plan

import com.trainingapp.contract.exercise.SetType
import kotlinx.serialization.Serializable

/** The editable content of a plan. A published [PlanVersionDto] wraps one immutable body. */
@Serializable
data class PlanBodyDto(
    val name: String,
    val days: List<PlanDayDto>,
)

@Serializable
data class PlanDayDto(
    val id: String,
    val name: String,
    val exercises: List<PlannedExerciseDto>,
)

@Serializable
data class PlannedExerciseDto(
    val id: String,
    val exerciseId: String,
    /** Consecutive exercises with the same group form one superset. */
    val supersetGroup: String? = null,
    val restSeconds: Int? = null,
    val rpeEnabled: Boolean = false,
    val tempo: String? = null,
    val note: String? = null,
    val sets: List<PlannedSetDto>,
)

/** The fields that apply depend on the exercise's tracking type. A fixed rep count has `repsMin == repsMax`. */
@Serializable
data class PlannedSetDto(
    val id: String,
    val type: SetType = SetType.WORKING,
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val weightGrams: Int? = null,
    val durationSeconds: Int? = null,
    val distanceMeters: Int? = null,
    val rpe: Double? = null,
    val restSecondsOverride: Int? = null,
)

@Serializable
data class PlanTemplateDto(
    val id: String,
    val ownerTrainerId: String,
    val name: String,
    val latestVersion: Int,
    val archivedAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long,
)

/** Immutable. `(templateId, number)` is unique. */
@Serializable
data class PlanVersionDto(
    val id: String,
    val templateId: String,
    val number: Int,
    val createdAtEpochMs: Long,
    val basedOnVersion: Int? = null,
    val body: PlanBodyDto,
)

/**
 * `POST /plans` (no [templateId]) or `POST /plans/{id}/versions`.
 * A retry with the same [operationId] returns the version it already created.
 */
@Serializable
data class PublishPlanRequest(
    val operationId: String,
    val templateId: String? = null,
    val baseVersion: Int? = null,
    val body: PlanBodyDto,
)

@Serializable
data class PublishPlanResponse(
    val template: PlanTemplateDto,
    val version: PlanVersionDto,
)
