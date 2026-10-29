package com.trainingapp.domain

import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.ExerciseSources
import com.trainingapp.contract.exercise.ExerciseStatus
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.SyncState

/** Ids "id1", "id2", … in order. */
class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private var next = 0
    override fun newId(): String = "$prefix${++next}"
}

fun exercise(
    id: String,
    en: String,
    pl: String? = null,
    aliases: List<String> = emptyList(),
    muscle: MuscleGroup = MuscleGroup.CHEST,
    equipment: Equipment = Equipment.BARBELL,
    trackingType: TrackingType = TrackingType.WEIGHT_REPS,
    featured: Boolean = false,
    owner: String? = null,
    status: ExerciseStatus = ExerciseStatus.ACTIVE,
    syncState: SyncState = SyncState.SYNCED,
) = Exercise(
    id = id,
    ownerTrainerId = owner,
    source = if (owner == null) ExerciseSources.FREE_EXERCISE_DB else ExerciseSources.TRAINER,
    sourceId = null,
    names = buildMap { put("en", en); if (pl != null) put("pl", pl) },
    aliases = aliases,
    primaryMuscles = listOf(muscle),
    secondaryMuscles = emptyList(),
    equipment = equipment,
    trackingType = trackingType,
    instructions = emptyMap(),
    images = emptyList(),
    demoMediaId = null,
    featured = featured,
    status = status,
    syncState = syncState,
    updatedAtEpochMs = 0,
)
