package com.trainingapp.domain.workout

import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlannedSetDto
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.plan.SetInput

enum class SetField { WEIGHT, REPS, RPE, DURATION, DISTANCE }

/** The value fields a set of [this] tracking type shows, in order. RPE is added by the caller when the plan enables it. */
val TrackingType.setFields: List<SetField>
    get() = when (this) {
        TrackingType.WEIGHT_REPS -> listOf(SetField.WEIGHT, SetField.REPS)
        TrackingType.REPS -> listOf(SetField.REPS)
        TrackingType.TIME -> listOf(SetField.DURATION)
        TrackingType.DISTANCE_TIME -> listOf(SetField.DISTANCE, SetField.DURATION)
    }

/**
 * Pure changes to a [WorkoutLog]. Every function returns a new log; the caller saves it at once.
 * Time is passed in, so tests and the restored app use the same rules.
 */
object WorkoutLogReducer {

    /**
     * Starts a workout from a plan day. Planned values are copied into the set fields, so ticking a set as planned
     * needs one tap. [trackingTypes] maps exercise ids to their tracking type; unknown ids default to weight × reps.
     */
    fun startFromPlan(
        id: String,
        clientId: String,
        name: String,
        planVersionId: String?,
        day: PlanDayDto,
        trackingTypes: Map<String, TrackingType>,
        nowEpochMs: Long,
        ids: IdGenerator,
        sessionId: String? = null,
        place: String? = null,
    ): WorkoutLog = WorkoutLog(
        id = id,
        clientId = clientId,
        name = name,
        sessionId = sessionId,
        planVersionId = planVersionId,
        planDayId = day.id,
        startedAtEpochMs = nowEpochMs,
        place = place,
        exercises = day.exercises.map { planned ->
            LoggedExercise(
                id = ids.newId(),
                exerciseId = planned.exerciseId,
                trackingType = trackingTypes[planned.exerciseId] ?: TrackingType.WEIGHT_REPS,
                plannedExerciseId = planned.id,
                restSeconds = planned.restSeconds,
                rpeEnabled = planned.rpeEnabled,
                sets = planned.sets.map { it.toSetLog(ids.newId()) },
            )
        },
    )

    /** An independent workout without a plan (D6). Exercises are added while logging. */
    fun startEmpty(id: String, clientId: String, name: String, nowEpochMs: Long): WorkoutLog =
        WorkoutLog(id = id, clientId = clientId, name = name, startedAtEpochMs = nowEpochMs, exercises = emptyList())

    fun addExercise(log: WorkoutLog, exerciseId: String, trackingType: TrackingType, ids: IdGenerator): WorkoutLog {
        val entry = LoggedExercise(ids.newId(), exerciseId, trackingType, sets = listOf(SetLog(ids.newId())))
        return log.copy(exercises = log.exercises + entry, currentExerciseIndex = log.exercises.size)
    }

    sealed interface EditResult {
        data class Changed(val log: WorkoutLog) : EditResult

        /** The text cannot be read; the stored value is kept and the field shows an error. */
        data object Invalid : EditResult
    }

    /** Applies typed text to one field. Blank text clears the value. */
    fun edit(log: WorkoutLog, setId: String, field: SetField, text: String): EditResult {
        val set = log.set(setId) ?: return EditResult.Invalid
        val updated = when (field) {
            SetField.WEIGHT -> when (val p = SetInput.parseWeightGrams(text)) {
                is SetInput.Parsed.Value -> set.copy(weightGrams = p.value)
                SetInput.Parsed.Blank -> set.copy(weightGrams = null)
                SetInput.Parsed.Invalid -> return EditResult.Invalid
            }
            SetField.REPS -> when (val p = SetInput.parseReps(text)) {
                // A logged set has one rep count; a range typed by mistake is not a value.
                is SetInput.Parsed.Value -> if (p.value.min == p.value.max && p.value.min <= MAX_REPS) set.copy(reps = p.value.min) else return EditResult.Invalid
                SetInput.Parsed.Blank -> set.copy(reps = null)
                SetInput.Parsed.Invalid -> return EditResult.Invalid
            }
            SetField.RPE -> when (val p = SetInput.parseRpe(text)) {
                is SetInput.Parsed.Value -> if (p.value in 1.0..10.0) set.copy(rpe = p.value) else return EditResult.Invalid
                SetInput.Parsed.Blank -> set.copy(rpe = null)
                SetInput.Parsed.Invalid -> return EditResult.Invalid
            }
            SetField.DURATION -> when (val p = SetInput.parseDurationSeconds(text)) {
                is SetInput.Parsed.Value -> set.copy(durationSeconds = p.value)
                SetInput.Parsed.Blank -> set.copy(durationSeconds = null)
                SetInput.Parsed.Invalid -> return EditResult.Invalid
            }
            SetField.DISTANCE -> when (val p = SetInput.parseDistanceMeters(text)) {
                is SetInput.Parsed.Value -> set.copy(distanceMeters = p.value)
                SetInput.Parsed.Blank -> set.copy(distanceMeters = null)
                SetInput.Parsed.Invalid -> return EditResult.Invalid
            }
        }
        return EditResult.Changed(replaceSet(log, updated))
    }

    /** Whether the set has the values its tracking type needs, so it can be ticked. */
    fun canComplete(exercise: LoggedExercise, set: SetLog): Boolean = when (exercise.trackingType) {
        TrackingType.WEIGHT_REPS -> set.reps != null && set.reps > 0 && set.weightGrams != null
        TrackingType.REPS -> set.reps != null && set.reps > 0
        TrackingType.TIME -> set.durationSeconds != null && set.durationSeconds > 0
        TrackingType.DISTANCE_TIME -> set.distanceMeters != null && set.distanceMeters > 0
    }

    /**
     * Ticks a set and starts the rest after it. Ticking a ticked set un-ticks it (and stops its rest).
     * Returns the log unchanged when the set lacks a required value.
     */
    fun toggleComplete(log: WorkoutLog, setId: String, nowEpochMs: Long): WorkoutLog {
        val exercise = log.exerciseOfSet(setId) ?: return log
        val set = exercise.sets.first { it.id == setId }
        if (set.isCompleted) {
            val rest = log.rest?.takeUnless { it.afterSetId == setId }
            return replaceSet(log, set.copy(completedAtEpochMs = null)).copy(rest = rest)
        }
        if (!canComplete(exercise, set)) return log
        val seconds = set.planned?.restSeconds ?: exercise.restSeconds ?: RestTimer.DEFAULT_SECONDS
        val ticked = replaceSet(log, set.copy(completedAtEpochMs = nowEpochMs))
        val isLastSetOfWorkout = ticked.allSets.all { it.isCompleted }
        return ticked.copy(rest = if (seconds > 0 && !isLastSetOfWorkout) RestTimer.start(setId, seconds, nowEpochMs) else null)
    }

    /** Adds a set after the last one of the exercise, pre-filled with the last set's values. */
    fun addSet(log: WorkoutLog, exerciseEntryId: String, newSetId: String): WorkoutLog {
        val exercise = log.exercise(exerciseEntryId) ?: return log
        val last = exercise.sets.lastOrNull()
        val set = SetLog(
            id = newSetId,
            type = if (last?.type == SetType.WARM_UP || last == null) SetType.WORKING else last.type,
            weightGrams = last?.weightGrams,
            reps = last?.reps,
            durationSeconds = last?.durationSeconds,
            distanceMeters = last?.distanceMeters,
        )
        return replaceExercise(log, exercise.copy(sets = exercise.sets + set))
    }

    fun removeSet(log: WorkoutLog, setId: String): WorkoutLog {
        val exercise = log.exerciseOfSet(setId) ?: return log
        val rest = log.rest?.takeUnless { it.afterSetId == setId }
        return replaceExercise(log, exercise.copy(sets = exercise.sets.filterNot { it.id == setId })).copy(rest = rest)
    }

    fun attachTechniqueVideo(log: WorkoutLog, setId: String, mediaId: String): WorkoutLog {
        val set = log.set(setId) ?: return log
        return replaceSet(log, set.copy(techniqueMediaId = mediaId))
    }

    fun selectExercise(log: WorkoutLog, index: Int): WorkoutLog =
        if (index in log.exercises.indices) log.copy(currentExerciseIndex = index) else log

    fun adjustRest(log: WorkoutLog, deltaSeconds: Int, nowEpochMs: Long): WorkoutLog =
        log.copy(rest = log.rest?.adjusted(deltaSeconds, nowEpochMs))

    fun skipRest(log: WorkoutLog): WorkoutLog = log.copy(rest = null)

    fun setNote(log: WorkoutLog, note: String): WorkoutLog = log.copy(note = note.take(NOTE_MAX_LENGTH))

    /**
     * "Zakończ" in the live workout: stops the clock and the rest, but keeps the workout unfinished until the client
     * saves it in the summary. Going back from the summary calls [resume].
     */
    fun end(log: WorkoutLog, nowEpochMs: Long): WorkoutLog =
        if (log.isFinished) log else log.copy(finishedAtEpochMs = log.finishedAtEpochMs ?: nowEpochMs, rest = null)

    fun resume(log: WorkoutLog): WorkoutLog = if (log.isFinished) log else log.copy(finishedAtEpochMs = null)

    /** "Zapisz trening". Keeps the end time set by [end], if any. */
    fun finish(log: WorkoutLog, nowEpochMs: Long): WorkoutLog =
        if (log.isFinished) log else log.copy(state = WorkoutState.FINISHED, finishedAtEpochMs = log.finishedAtEpochMs ?: nowEpochMs, rest = null)

    const val NOTE_MAX_LENGTH = 500
    private const val MAX_REPS = 999

    private fun PlannedSetDto.toSetLog(id: String): SetLog {
        val planned = PlannedValues(repsMin, repsMax, weightGrams, durationSeconds, distanceMeters, rpe, restSecondsOverride)
        return SetLog(
            id = id,
            type = type,
            planned = planned,
            weightGrams = weightGrams,
            // A range ("8–10") pre-fills its lower bound; the client corrects it if they did more.
            reps = repsMin,
            rpe = null,
            durationSeconds = durationSeconds,
            distanceMeters = distanceMeters,
        )
    }

    private fun replaceSet(log: WorkoutLog, set: SetLog): WorkoutLog {
        val exercise = log.exerciseOfSet(set.id) ?: return log
        return replaceExercise(log, exercise.copy(sets = exercise.sets.map { if (it.id == set.id) set else it }))
    }

    private fun replaceExercise(log: WorkoutLog, exercise: LoggedExercise): WorkoutLog =
        log.copy(exercises = log.exercises.map { if (it.id == exercise.id) exercise else it })
}
