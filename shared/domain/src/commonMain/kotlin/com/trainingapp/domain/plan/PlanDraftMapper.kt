package com.trainingapp.domain.plan

import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.plan.PlanBodyDto
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlanIssue
import com.trainingapp.contract.plan.PlanValidator
import com.trainingapp.contract.plan.PlannedExerciseDto
import com.trainingapp.contract.plan.PlannedSetDto
import com.trainingapp.contract.plan.SetField
import com.trainingapp.contract.plan.SetProblem
import com.trainingapp.domain.common.IdGenerator

data class DraftValidation(val body: PlanBodyDto, val issues: List<PlanIssue>) {
    val isValid: Boolean get() = issues.isEmpty()
}

/** Converts between the editable draft and the published plan body. */
object PlanDraftMapper {

    /**
     * Parses the draft and runs [PlanValidator]. Text that cannot be parsed is reported as [SetProblem.INVALID]
     * instead of the validator's "required" for the same field. Only the fields that the exercise's tracking type
     * uses are sent; a blank day name becomes [defaultDayName] for its position.
     */
    fun validate(
        draft: PlanDraft,
        trackingTypeOf: (exerciseId: String) -> TrackingType?,
        defaultDayName: (index: Int) -> String,
    ): DraftValidation {
        val parseIssues = mutableListOf<PlanIssue.SetValueInvalid>()
        val body = PlanBodyDto(
            name = draft.name.trim(),
            tags = draft.tags,
            days = draft.days.mapIndexed { index, day ->
                PlanDayDto(
                    id = day.id,
                    name = day.name.trim().ifEmpty { defaultDayName(index) },
                    exercises = day.exercises.map { exercise ->
                        val tracking = trackingTypeOf(exercise.exerciseId)
                        PlannedExerciseDto(
                            id = exercise.id,
                            exerciseId = exercise.exerciseId,
                            supersetGroup = exercise.supersetGroup,
                            restSeconds = exercise.restSeconds,
                            rpeEnabled = exercise.rpeEnabled,
                            accessory = exercise.accessory,
                            tempo = exercise.tempo.trim().ifEmpty { null },
                            note = exercise.note.trim().ifEmpty { null },
                            sets = exercise.sets.map { set ->
                                toSetDto(set, tracking, exercise.rpeEnabled) { field ->
                                    parseIssues += PlanIssue.SetValueInvalid(day.id, exercise.id, set.id, field, SetProblem.INVALID)
                                }
                            },
                        )
                    },
                )
            },
        )
        val unparsable = parseIssues.map { Triple(it.setId, it.field, it.plannedExerciseId) }.toSet()
        val validatorIssues = PlanValidator.validate(body, trackingTypeOf).filterNot {
            it is PlanIssue.SetValueInvalid && Triple(it.setId, it.field, it.plannedExerciseId) in unparsable
        }
        return DraftValidation(body, parseIssues + validatorIssues)
    }

    private fun toSetDto(
        set: DraftSet,
        tracking: TrackingType?,
        rpeEnabled: Boolean,
        onInvalid: (SetField) -> Unit,
    ): PlannedSetDto {
        fun <T> SetInput.Parsed<T>.valueOrReport(field: SetField): T? = when (this) {
            is SetInput.Parsed.Value -> value
            SetInput.Parsed.Blank -> null
            SetInput.Parsed.Invalid -> null.also { onInvalid(field) }
        }

        val usesReps = tracking == TrackingType.WEIGHT_REPS || tracking == TrackingType.REPS
        val usesDuration = tracking == TrackingType.TIME || tracking == TrackingType.DISTANCE_TIME
        val reps = if (usesReps) SetInput.parseReps(set.reps).valueOrReport(SetField.REPS) else null
        return PlannedSetDto(
            id = set.id,
            type = set.type,
            repsMin = reps?.min,
            repsMax = reps?.max,
            weightGrams = if (tracking == TrackingType.WEIGHT_REPS) SetInput.parseWeightGrams(set.weight).valueOrReport(SetField.WEIGHT) else null,
            durationSeconds = if (usesDuration) SetInput.parseDurationSeconds(set.duration).valueOrReport(SetField.DURATION) else null,
            distanceMeters = if (tracking == TrackingType.DISTANCE_TIME) SetInput.parseDistanceMeters(set.distance).valueOrReport(SetField.DISTANCE) else null,
            rpe = if (rpeEnabled) SetInput.parseRpe(set.rpe).valueOrReport(SetField.RPE) else null,
            restSecondsOverride = SetInput.parseDurationSeconds(set.rest).valueOrReport(SetField.REST),
        )
    }

    /** Opens a published body for editing. Ids are kept, so the next version diffs cleanly against this one. */
    fun toDraft(
        body: PlanBodyDto,
        templateId: String,
        baseVersion: Int,
        decimalSeparator: Char,
        ids: IdGenerator,
        defaultDayName: (index: Int) -> String,
    ): PlanDraft = PlanDraft(
        draftId = ids.newId(),
        templateId = templateId,
        baseVersion = baseVersion,
        name = body.name,
        tags = body.tags,
        days = body.days.mapIndexed { index, day ->
            DraftDay(
                id = day.id,
                // A stored default name is shown as the default again, so it follows the language.
                name = if (day.name == defaultDayName(index)) "" else day.name,
                exercises = day.exercises.map { exercise ->
                    DraftExercise(
                        id = exercise.id,
                        exerciseId = exercise.exerciseId,
                        supersetGroup = exercise.supersetGroup,
                        restSeconds = exercise.restSeconds,
                        rpeEnabled = exercise.rpeEnabled,
                        accessory = exercise.accessory,
                        tempo = exercise.tempo.orEmpty(),
                        note = exercise.note.orEmpty(),
                        sets = exercise.sets.map { set ->
                            DraftSet(
                                id = set.id,
                                type = set.type,
                                reps = SetInput.formatReps(set.repsMin, set.repsMax),
                                weight = SetInput.formatThousandths(set.weightGrams, decimalSeparator),
                                duration = SetInput.formatDuration(set.durationSeconds),
                                distance = SetInput.formatThousandths(set.distanceMeters, decimalSeparator),
                                rpe = SetInput.formatRpe(set.rpe, decimalSeparator),
                                rest = set.restSecondsOverride?.toString().orEmpty(),
                            )
                        },
                    )
                },
            )
        },
    )
}
