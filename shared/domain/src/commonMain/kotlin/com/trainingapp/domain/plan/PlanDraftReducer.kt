package com.trainingapp.domain.plan

import com.trainingapp.contract.exercise.SetType
import com.trainingapp.domain.common.IdGenerator

/** Every edit the plan editor can make. [PlanDraftReducer] applies them as pure functions. */
sealed interface PlanDraftAction {
    data class Rename(val name: String) : PlanDraftAction
    data class UpdateTags(val tags: List<String>) : PlanDraftAction

    data object AddDay : PlanDraftAction
    data class RenameDay(val dayId: String, val name: String) : PlanDraftAction
    data class DuplicateDay(val dayId: String) : PlanDraftAction

    /** Ignored for the last remaining day: a plan always has at least one. */
    data class DeleteDay(val dayId: String) : PlanDraftAction

    /** Appends in the given order, each with one empty working set. */
    data class AddExercises(val dayId: String, val exerciseIds: List<String>) : PlanDraftAction
    data class MoveExercise(val dayId: String, val plannedExerciseId: String, val toIndex: Int) : PlanDraftAction
    data class RemoveExercise(val dayId: String, val plannedExerciseId: String) : PlanDraftAction

    /** Puts a removed exercise back, for "Cofnij". */
    data class RestoreExercise(val dayId: String, val index: Int, val exercise: DraftExercise) : PlanDraftAction
    data class ReplaceExercise(val dayId: String, val plannedExerciseId: String, val exerciseId: String) : PlanDraftAction
    data class LinkWithNext(val dayId: String, val plannedExerciseId: String) : PlanDraftAction
    data class Unlink(val dayId: String, val plannedExerciseId: String) : PlanDraftAction
    data class UpdateSettings(
        val dayId: String,
        val plannedExerciseId: String,
        val restSeconds: Int?,
        val rpeEnabled: Boolean,
        val tempo: String,
        val note: String,
        val accessory: Boolean = false,
    ) : PlanDraftAction

    /** Copies the values of the last set. */
    data class AddSet(val dayId: String, val plannedExerciseId: String) : PlanDraftAction
    data class RemoveSet(val dayId: String, val plannedExerciseId: String, val setId: String) : PlanDraftAction

    /** Working → warm-up → drop set → working. */
    data class CycleSetType(val dayId: String, val plannedExerciseId: String, val setId: String) : PlanDraftAction
    data class UpdateSetValue(
        val dayId: String,
        val plannedExerciseId: String,
        val setId: String,
        val field: DraftSetField,
        val text: String,
    ) : PlanDraftAction
}

object PlanDraftReducer {

    fun newDraft(ids: IdGenerator): PlanDraft = PlanDraft(draftId = ids.newId(), days = listOf(DraftDay(ids.newId())))

    /**
     * Applies [action]. Any content change clears [PlanDraft.pendingOperationId], so a publish after an edit
     * is a new operation rather than a retry of the old one.
     */
    fun reduce(draft: PlanDraft, action: PlanDraftAction, ids: IdGenerator): PlanDraft {
        val next = when (action) {
            is PlanDraftAction.Rename -> draft.copy(name = action.name)
            is PlanDraftAction.UpdateTags -> draft.copy(tags = action.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
            PlanDraftAction.AddDay -> draft.copy(days = draft.days + DraftDay(ids.newId()))
            is PlanDraftAction.RenameDay -> draft.updateDay(action.dayId) { it.copy(name = action.name) }
            is PlanDraftAction.DuplicateDay -> duplicateDay(draft, action.dayId, ids)
            is PlanDraftAction.DeleteDay ->
                if (draft.days.size <= 1) draft else draft.copy(days = draft.days.filterNot { it.id == action.dayId })

            is PlanDraftAction.AddExercises -> draft.updateDay(action.dayId) { day ->
                day.copy(
                    exercises = day.exercises + action.exerciseIds.map { exerciseId ->
                        DraftExercise(id = ids.newId(), exerciseId = exerciseId, sets = listOf(DraftSet(ids.newId())))
                    },
                )
            }

            is PlanDraftAction.MoveExercise -> draft.updateExercises(action.dayId, ids) { list ->
                val from = list.indexOfFirst { it.id == action.plannedExerciseId }
                if (from < 0) return@updateExercises list
                val to = action.toIndex.coerceIn(0, list.lastIndex)
                list.toMutableList().apply { add(to, removeAt(from)) }
            }

            is PlanDraftAction.RemoveExercise -> draft.updateExercises(action.dayId, ids) { list ->
                list.filterNot { it.id == action.plannedExerciseId }
            }

            is PlanDraftAction.RestoreExercise -> draft.updateExercises(action.dayId, ids) { list ->
                list.toMutableList().apply { add(action.index.coerceIn(0, size), action.exercise) }
            }

            is PlanDraftAction.ReplaceExercise -> draft.updateExercise(action.dayId, action.plannedExerciseId) {
                it.copy(exerciseId = action.exerciseId)
            }

            is PlanDraftAction.LinkWithNext -> draft.updateExercises(action.dayId, ids) { list -> linkWithNext(list, action.plannedExerciseId, ids) }
            is PlanDraftAction.Unlink -> draft.updateExercises(action.dayId, ids) { list ->
                list.map { if (it.id == action.plannedExerciseId) it.copy(supersetGroup = null) else it }
            }

            is PlanDraftAction.UpdateSettings -> draft.updateExercise(action.dayId, action.plannedExerciseId) {
                it.copy(
                    restSeconds = action.restSeconds,
                    rpeEnabled = action.rpeEnabled,
                    accessory = action.accessory,
                    tempo = action.tempo.trim(),
                    note = action.note.trim(),
                )
            }

            is PlanDraftAction.AddSet -> draft.updateExercise(action.dayId, action.plannedExerciseId) { exercise ->
                val copied = exercise.sets.lastOrNull()?.copy(id = ids.newId()) ?: DraftSet(ids.newId())
                exercise.copy(sets = exercise.sets + copied)
            }

            is PlanDraftAction.RemoveSet -> draft.updateExercise(action.dayId, action.plannedExerciseId) { exercise ->
                exercise.copy(sets = exercise.sets.filterNot { it.id == action.setId })
            }

            is PlanDraftAction.CycleSetType -> draft.updateSet(action.dayId, action.plannedExerciseId, action.setId) {
                it.copy(type = nextType(it.type))
            }

            is PlanDraftAction.UpdateSetValue -> draft.updateSet(action.dayId, action.plannedExerciseId, action.setId) {
                when (action.field) {
                    DraftSetField.REPS -> it.copy(reps = action.text)
                    DraftSetField.WEIGHT -> it.copy(weight = action.text)
                    DraftSetField.DURATION -> it.copy(duration = action.text)
                    DraftSetField.DISTANCE -> it.copy(distance = action.text)
                    DraftSetField.RPE -> it.copy(rpe = action.text)
                    DraftSetField.REST -> it.copy(rest = action.text)
                }
            }
        }
        return if (next == draft) draft else next.copy(pendingOperationId = null)
    }

    fun nextType(type: SetType): SetType = when (type) {
        SetType.WORKING -> SetType.WARM_UP
        SetType.WARM_UP -> SetType.DROP
        SetType.DROP -> SetType.WORKING
    }

    /**
     * Groups the exercise with the next one. If either is already in a superset, the other joins that group,
     * so "link" can grow a superset to three or more exercises.
     */
    private fun linkWithNext(list: List<DraftExercise>, plannedExerciseId: String, ids: IdGenerator): List<DraftExercise> {
        val index = list.indexOfFirst { it.id == plannedExerciseId }
        if (index < 0 || index == list.lastIndex) return list
        val current = list[index]
        val next = list[index + 1]
        val group = current.supersetGroup ?: next.supersetGroup ?: ids.newId()
        val oldNextGroup = next.supersetGroup
        return list.mapIndexed { i, exercise ->
            when {
                i == index || i == index + 1 -> exercise.copy(supersetGroup = group)
                // The rest of the next exercise's old superset follows it into the merged group.
                i > index + 1 && oldNextGroup != null && exercise.supersetGroup == oldNextGroup -> exercise.copy(supersetGroup = group)
                else -> exercise
            }
        }
    }

    /**
     * Keeps supersets meaningful after any structural change: a group must be one contiguous run of two or more
     * exercises. A run of one is ungrouped, and a group split by a move gets a new id for its later run.
     */
    fun normalizeSupersets(list: List<DraftExercise>, ids: IdGenerator): List<DraftExercise> {
        val result = list.toMutableList()
        val seen = mutableSetOf<String>()
        var i = 0
        while (i < result.size) {
            val group = result[i].supersetGroup
            if (group == null) {
                i++
                continue
            }
            var end = i
            while (end + 1 < result.size && result[end + 1].supersetGroup == group) end++
            val runLength = end - i + 1
            val newGroup = when {
                runLength < 2 -> null
                group in seen -> ids.newId()
                else -> group
            }
            if (newGroup != null) seen += newGroup
            if (newGroup != group) for (k in i..end) result[k] = result[k].copy(supersetGroup = newGroup)
            i = end + 1
        }
        return result
    }

    private fun duplicateDay(draft: PlanDraft, dayId: String, ids: IdGenerator): PlanDraft {
        val index = draft.days.indexOfFirst { it.id == dayId }
        if (index < 0) return draft
        val source = draft.days[index]
        val groupIds = mutableMapOf<String, String>()
        val copy = DraftDay(
            id = ids.newId(),
            name = source.name,
            exercises = source.exercises.map { exercise ->
                exercise.copy(
                    id = ids.newId(),
                    supersetGroup = exercise.supersetGroup?.let { groupIds.getOrPut(it) { ids.newId() } },
                    sets = exercise.sets.map { it.copy(id = ids.newId()) },
                )
            },
        )
        return draft.copy(days = draft.days.toMutableList().apply { add(index + 1, copy) })
    }

    private inline fun PlanDraft.updateDay(dayId: String, transform: (DraftDay) -> DraftDay): PlanDraft =
        copy(days = days.map { if (it.id == dayId) transform(it) else it })

    private inline fun PlanDraft.updateExercises(
        dayId: String,
        ids: IdGenerator,
        transform: (List<DraftExercise>) -> List<DraftExercise>,
    ): PlanDraft = updateDay(dayId) { day ->
        val changed = transform(day.exercises)
        if (changed == day.exercises) day else day.copy(exercises = normalizeSupersets(changed, ids))
    }

    private inline fun PlanDraft.updateExercise(
        dayId: String,
        plannedExerciseId: String,
        transform: (DraftExercise) -> DraftExercise,
    ): PlanDraft = updateDay(dayId) { day ->
        day.copy(exercises = day.exercises.map { if (it.id == plannedExerciseId) transform(it) else it })
    }

    private inline fun PlanDraft.updateSet(
        dayId: String,
        plannedExerciseId: String,
        setId: String,
        crossinline transform: (DraftSet) -> DraftSet,
    ): PlanDraft = updateExercise(dayId, plannedExerciseId) { exercise ->
        exercise.copy(sets = exercise.sets.map { if (it.id == setId) transform(it) else it })
    }
}

/** Superset labels for one day: each run of linked exercises gets "A", "B", … in order. */
fun DraftDay.supersetLabels(): Map<String, String> {
    val labels = mutableMapOf<String, String>()
    var letter = 0
    var previousGroup: String? = null
    for (exercise in exercises) {
        val group = exercise.supersetGroup
        if (group != null) {
            if (group != previousGroup) letter++
            labels[exercise.id] = alphabetLabel(letter - 1)
        }
        previousGroup = group
    }
    return labels
}

/** 0 → "A", 25 → "Z", 26 → "AA". Used for default day names and superset labels. */
fun alphabetLabel(index: Int): String {
    var n = index
    val sb = StringBuilder()
    do {
        sb.insert(0, 'A' + n % 26)
        n = n / 26 - 1
    } while (n >= 0)
    return sb.toString()
}
