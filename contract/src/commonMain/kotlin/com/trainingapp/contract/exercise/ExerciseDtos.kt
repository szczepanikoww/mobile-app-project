package com.trainingapp.contract.exercise

import kotlinx.serialization.Serializable

/** Locale tag (`pl`, `en`) to text. */
typealias LocalizedText = Map<String, String>

@Serializable
data class ExerciseDto(
    val id: String,
    /** `null` for the global library; otherwise the owning trainer. */
    val ownerTrainerId: String? = null,
    val source: String,
    val sourceId: String? = null,
    val names: LocalizedText,
    val aliases: List<String> = emptyList(),
    val primaryMuscles: List<MuscleGroup>,
    val secondaryMuscles: List<MuscleGroup> = emptyList(),
    val equipment: Equipment,
    val trackingType: TrackingType,
    /** Locale tag to ordered steps. */
    val instructions: Map<String, List<String>> = emptyMap(),
    /** Storage keys of the start/end position images. */
    val images: List<String> = emptyList(),
    /** Reserved for the trainer demo video from the media pipeline. */
    val demoMediaId: String? = null,
    val featured: Boolean = false,
    val status: ExerciseStatus = ExerciseStatus.ACTIVE,
    val updatedAtEpochMs: Long = 0,
)

/** `PUT /exercises/{id}`: creates or updates the caller's own exercise. Idempotent by the client-generated id. */
@Serializable
data class UpsertExerciseRequest(
    val id: String,
    val names: LocalizedText,
    val primaryMuscle: MuscleGroup,
    val secondaryMuscles: List<MuscleGroup> = emptyList(),
    val equipment: Equipment,
    val trackingType: TrackingType,
    val instructions: Map<String, List<String>> = emptyMap(),
    /** The trainer's demo video, once its upload has started. */
    val demoMediaId: String? = null,
)

object ExerciseSources {
    const val FREE_EXERCISE_DB = "free-exercise-db"
    const val TRAINER = "trainer"
}

/**
 * The trainer's own note and video on an exercise ("Od trenera"), for the global library too. The video is shown
 * before the library images. `GET`/`PUT trainer/exercises/{id}/note`; an empty note and no video remove it.
 */
@Serializable
data class TrainerExerciseNoteDto(val exerciseId: String, val note: String = "", val demoMediaId: String? = null)
