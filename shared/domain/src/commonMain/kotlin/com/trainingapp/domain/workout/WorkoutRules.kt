package com.trainingapp.domain.workout

import com.trainingapp.contract.exercise.TrackingType

/** "Poprzednio: 80 kg × 6": the last working set of the same exercise in the most recent earlier finished workout. */
object PreviousPerformance {
    fun lastWorkingSet(exerciseId: String, history: List<WorkoutLog>, excludeWorkoutId: String? = null): SetLog? =
        history
            .filter { it.isFinished && it.id != excludeWorkoutId }
            .sortedByDescending { it.finishedAtEpochMs ?: it.startedAtEpochMs }
            .firstNotNullOfOrNull { log ->
                log.exercises.filter { it.exerciseId == exerciseId }
                    .flatMap { it.sets }
                    .lastOrNull { it.isWorking && it.isCompleted }
            }
}

/**
 * A comparable performance of one set: weight first, then reps (weight × reps); reps alone; time; or distance.
 * Values of different tracking types are never compared.
 */
data class SetPerformance(val trackingType: TrackingType, val primary: Int, val secondary: Int) : Comparable<SetPerformance> {
    override fun compareTo(other: SetPerformance): Int =
        compareValuesBy(this, other, { it.primary }, { it.secondary })

    companion object {
        fun of(trackingType: TrackingType, set: SetLog): SetPerformance? = when (trackingType) {
            TrackingType.WEIGHT_REPS -> if (set.weightGrams != null && set.reps != null && set.reps > 0) SetPerformance(trackingType, set.weightGrams, set.reps) else null
            TrackingType.REPS -> set.reps?.takeIf { it > 0 }?.let { SetPerformance(trackingType, it, 0) }
            TrackingType.TIME -> set.durationSeconds?.takeIf { it > 0 }?.let { SetPerformance(trackingType, it, 0) }
            TrackingType.DISTANCE_TIME -> set.distanceMeters?.takeIf { it > 0 }?.let {
                // Longer distance first; for the same distance, a shorter time is better.
                SetPerformance(trackingType, it, -(set.durationSeconds ?: Int.MAX_VALUE))
            }
        }
    }
}

/** The best completed working set of [exerciseId] in one workout, or `null`. Warm-ups are ignored. */
fun WorkoutLog.bestSet(exerciseId: String): Pair<SetLog, SetPerformance>? =
    exercises.filter { it.exerciseId == exerciseId }
        .flatMap { e -> e.sets.filter { it.isWorking && it.isCompleted }.mapNotNull { s -> SetPerformance.of(e.trackingType, s)?.let { s to it } } }
        .maxByOrNull { it.second }

data class PersonalRecord(val exerciseId: String, val set: SetLog, val previousBest: SetPerformance)

/**
 * Personal records (architecture 9.1): only working sets of the same exercise, compared in the same unit. A record
 * needs an earlier result to beat, so the first workout with an exercise is not a record.
 */
object PersonalRecords {
    fun find(workout: WorkoutLog, history: List<WorkoutLog>): List<PersonalRecord> {
        val earlier = history.filter {
            it.isFinished && it.id != workout.id && (it.finishedAtEpochMs ?: 0) <= (workout.finishedAtEpochMs ?: Long.MAX_VALUE)
        }
        return workout.exercises.map { it.exerciseId }.distinct().mapNotNull { exerciseId ->
            val (set, best) = workout.bestSet(exerciseId) ?: return@mapNotNull null
            val previous = earlier.mapNotNull { it.bestSet(exerciseId)?.second }
                .filter { it.trackingType == best.trackingType }
                .maxOrNull() ?: return@mapNotNull null
            if (best > previous) PersonalRecord(exerciseId, set, previous) else null
        }
    }
}

data class ExerciseSummary(
    val entryId: String,
    val exerciseId: String,
    val trackingType: TrackingType,
    val completedSets: Int,
    val plannedSets: Int,
    /** Completed planned sets out of planned sets, 0..100; `null` for exercises without a plan. */
    val planPercent: Int?,
    /** The heaviest completed working set, for weight × reps exercises. */
    val maxWeightGrams: Int?,
    /** The highest RPE entered, if any. */
    val maxRpe: Double?,
)

data class WorkoutSummary(
    val durationMs: Long,
    /** Σ weight × reps of completed working sets, in grams. Warm-ups are excluded. */
    val volumeGrams: Long,
    val completedSets: Int,
    val plannedSets: Int,
    val exercises: List<ExerciseSummary>,
) {
    companion object {
        fun of(log: WorkoutLog, nowEpochMs: Long): WorkoutSummary {
            val end = log.finishedAtEpochMs ?: nowEpochMs
            val exercises = log.exercises.map { e ->
                val done = e.sets.filter { it.isCompleted }
                val planned = e.sets.filter { it.planned != null }
                ExerciseSummary(
                    entryId = e.id,
                    exerciseId = e.exerciseId,
                    trackingType = e.trackingType,
                    completedSets = done.size,
                    plannedSets = planned.size,
                    planPercent = if (planned.isEmpty()) null else planned.count { it.isCompleted } * 100 / planned.size,
                    maxWeightGrams = if (e.trackingType == TrackingType.WEIGHT_REPS) done.filter { it.isWorking }.mapNotNull { it.weightGrams }.maxOrNull() else null,
                    maxRpe = done.mapNotNull { it.rpe }.maxOrNull(),
                )
            }
            val volume = log.exercises.filter { it.trackingType == TrackingType.WEIGHT_REPS }.sumOf { e ->
                e.sets.filter { it.isWorking && it.isCompleted }.sumOf { (it.weightGrams ?: 0).toLong() * (it.reps ?: 0) }
            }
            return WorkoutSummary(
                durationMs = (end - log.startedAtEpochMs).coerceAtLeast(0),
                volumeGrams = volume,
                completedSets = log.allSets.count { it.isCompleted },
                plannedSets = log.allSets.count { it.planned != null },
                exercises = exercises,
            )
        }
    }
}

/** One point of the progress chart: the best working set of an exercise in one finished workout (D1). */
data class ProgressPoint(val exerciseId: String, val workoutId: String, val atEpochMs: Long, val set: SetLog, val performance: SetPerformance)

object ProgressRules {
    /** Oldest first. No estimated 1RM: the chart shows what was actually lifted. */
    fun bestSetPerWorkout(exerciseId: String, history: List<WorkoutLog>): List<ProgressPoint> =
        history.filter { it.isFinished }
            .mapNotNull { log -> log.bestSet(exerciseId)?.let { (set, perf) -> ProgressPoint(exerciseId, log.id, log.finishedAtEpochMs ?: log.startedAtEpochMs, set, perf) } }
            .sortedBy { it.atEpochMs }

    /** Exercises the client has completed working sets of, most recent first. */
    fun loggedExerciseIds(history: List<WorkoutLog>): List<String> =
        history.filter { it.isFinished }
            .sortedByDescending { it.finishedAtEpochMs ?: it.startedAtEpochMs }
            .flatMap { log -> log.exercises.filter { e -> e.sets.any { it.isWorking && it.isCompleted } }.map { it.exerciseId } }
            .distinct()

    /** The best ever working set of each exercise, for the "Rekordy" tab. */
    fun records(history: List<WorkoutLog>): List<ProgressPoint> =
        loggedExerciseIds(history).mapNotNull { id -> bestSetPerWorkout(id, history).maxByOrNull { it.performance } }
}

/** Validation of a new body measurement: a positive number with up to three decimals, within a sane range. */
object MeasurementValidator {
    sealed interface Result {
        data class Valid(val thousandths: Int) : Result
        data object Blank : Result
        data object Invalid : Result
        data object OutOfRange : Result
    }

    fun validate(kind: MeasurementKind, text: String): Result {
        val parsed = com.trainingapp.domain.plan.SetInput.parseWeightGrams(text)
        return when (parsed) {
            com.trainingapp.domain.plan.SetInput.Parsed.Blank -> Result.Blank
            com.trainingapp.domain.plan.SetInput.Parsed.Invalid -> Result.Invalid
            is com.trainingapp.domain.plan.SetInput.Parsed.Value -> {
                val max = when (kind.unit) { MeasurementUnit.KG -> 400_000; MeasurementUnit.CM -> 300_000 }
                if (parsed.value <= 0 || parsed.value > max) Result.OutOfRange else Result.Valid(parsed.value)
            }
        }
    }
}
