package com.trainingapp.domain.plan

import com.trainingapp.contract.exercise.TrackingType

/** The three tiles of the plan creator: "Łącznie serii", "Szac. czas" and "Śr. RPE". */
data class PlanStats(
    val totalSets: Int,
    val estimatedMinutes: Int,
    /** `null` when no set has a readable RPE. */
    val averageRpe: Double?,
)

/**
 * An estimate, not a policy: each set takes [SET_WORK_SECONDS] (or its planned duration for timed sets) plus the
 * rest after it. The rest is the set's own "PRZERWA", else the exercise's rest, else [DEFAULT_REST_SECONDS].
 */
object PlanStatsCalculator {
    const val SET_WORK_SECONDS = 45
    const val DEFAULT_REST_SECONDS = 90

    fun of(day: DraftDay, trackingTypeOf: (exerciseId: String) -> TrackingType?): PlanStats {
        var seconds = 0
        var sets = 0
        val rpes = mutableListOf<Double>()
        day.exercises.forEach { exercise ->
            val timed = trackingTypeOf(exercise.exerciseId).let { it == TrackingType.TIME || it == TrackingType.DISTANCE_TIME }
            exercise.sets.forEach { set ->
                sets++
                val work = if (timed) SetInput.parseDurationSeconds(set.duration).valueOrNull() ?: SET_WORK_SECONDS else SET_WORK_SECONDS
                val rest = SetInput.parseDurationSeconds(set.rest).valueOrNull() ?: exercise.restSeconds ?: DEFAULT_REST_SECONDS
                seconds += work + rest
                SetInput.parseRpe(set.rpe).valueOrNull()?.let { rpes += it }
            }
        }
        return PlanStats(
            totalSets = sets,
            estimatedMinutes = (seconds + 59) / 60,
            averageRpe = if (rpes.isEmpty()) null else rpes.sum() / rpes.size,
        )
    }

    private fun <T> SetInput.Parsed<T>.valueOrNull(): T? = (this as? SetInput.Parsed.Value<T>)?.value
}
