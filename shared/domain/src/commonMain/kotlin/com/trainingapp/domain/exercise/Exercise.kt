package com.trainingapp.domain.exercise

import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.ExerciseDto
import com.trainingapp.contract.exercise.ExerciseStatus
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.TrackingType

enum class SyncState { SYNCED, PENDING, FAILED }

data class Exercise(
    val id: String,
    val ownerTrainerId: String?,
    val source: String,
    val sourceId: String?,
    val names: Map<String, String>,
    val aliases: List<String>,
    val primaryMuscles: List<MuscleGroup>,
    val secondaryMuscles: List<MuscleGroup>,
    val equipment: Equipment,
    val trackingType: TrackingType,
    val instructions: Map<String, List<String>>,
    val images: List<String>,
    val demoMediaId: String?,
    val featured: Boolean,
    val status: ExerciseStatus,
    val syncState: SyncState,
    val updatedAtEpochMs: Long,
) {
    val isCustom: Boolean get() = ownerTrainerId != null
    val isHidden: Boolean get() = status == ExerciseStatus.HIDDEN
    val primaryMuscle: MuscleGroup? get() = primaryMuscles.firstOrNull()

    /** The name in [languageTag], falling back to English and then to any stored name. */
    fun name(languageTag: String): String =
        names[languageTag] ?: names[FALLBACK_LANGUAGE] ?: names.values.firstOrNull().orEmpty()

    fun instructions(languageTag: String): List<String> =
        instructions[languageTag] ?: instructions[FALLBACK_LANGUAGE] ?: instructions.values.firstOrNull().orEmpty()

    fun toDto() = ExerciseDto(
        id = id,
        ownerTrainerId = ownerTrainerId,
        source = source,
        sourceId = sourceId,
        names = names,
        aliases = aliases,
        primaryMuscles = primaryMuscles,
        secondaryMuscles = secondaryMuscles,
        equipment = equipment,
        trackingType = trackingType,
        instructions = instructions,
        images = images,
        demoMediaId = demoMediaId,
        featured = featured,
        status = status,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    companion object {
        const val FALLBACK_LANGUAGE = "en"

        fun fromDto(dto: ExerciseDto, syncState: SyncState = SyncState.SYNCED) = Exercise(
            id = dto.id,
            ownerTrainerId = dto.ownerTrainerId,
            source = dto.source,
            sourceId = dto.sourceId,
            names = dto.names,
            aliases = dto.aliases,
            primaryMuscles = dto.primaryMuscles,
            secondaryMuscles = dto.secondaryMuscles,
            equipment = dto.equipment,
            trackingType = dto.trackingType,
            instructions = dto.instructions,
            images = dto.images,
            demoMediaId = dto.demoMediaId,
            featured = dto.featured,
            status = dto.status,
            syncState = syncState,
            updatedAtEpochMs = dto.updatedAtEpochMs,
        )
    }
}
