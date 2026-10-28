package com.trainingapp.domain.plan

import com.trainingapp.contract.exercise.SetType
import kotlinx.serialization.Serializable

/**
 * The local working copy of a plan. It is written to the device on every change and survives process death.
 * Set values are kept as the text the trainer typed, so partial input such as "8–" is never lost;
 * they are parsed only when the plan is validated or published.
 */
@Serializable
data class PlanDraft(
    val draftId: String,
    /** `null` until the plan is published for the first time. */
    val templateId: String? = null,
    /** The version this draft was opened from; sent so the server can detect a conflict. */
    val baseVersion: Int? = null,
    val name: String = "",
    val days: List<DraftDay>,
    /** Reused when a publish is retried, so a retry never publishes twice. Cleared by any edit. */
    val pendingOperationId: String? = null,
    val updatedAtEpochMs: Long = 0,
) {
    val exerciseCount: Int get() = days.sumOf { it.exercises.size }
    val setCount: Int get() = days.sumOf { day -> day.exercises.sumOf { it.sets.size } }

    fun day(dayId: String): DraftDay? = days.firstOrNull { it.id == dayId }

    /** A new plan nobody has typed into yet. It is not worth keeping on the device. */
    val isPristineNewPlan: Boolean
        get() = templateId == null && name.isBlank() && exerciseCount == 0 && days.size <= 1 && days.all { it.name.isBlank() }
}

@Serializable
data class DraftDay(
    val id: String,
    /** Blank means the default label for its position ("Dzień A"). */
    val name: String = "",
    val exercises: List<DraftExercise> = emptyList(),
)

@Serializable
data class DraftExercise(
    val id: String,
    val exerciseId: String,
    val supersetGroup: String? = null,
    val restSeconds: Int? = null,
    val rpeEnabled: Boolean = false,
    val tempo: String = "",
    val note: String = "",
    val sets: List<DraftSet>,
)

@Serializable
data class DraftSet(
    val id: String,
    val type: SetType = SetType.WORKING,
    val reps: String = "",
    val weight: String = "",
    val duration: String = "",
    val distance: String = "",
    val rpe: String = "",
)

enum class DraftSetField { REPS, WEIGHT, DURATION, DISTANCE, RPE }
