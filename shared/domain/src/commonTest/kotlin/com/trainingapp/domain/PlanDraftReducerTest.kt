package com.trainingapp.domain

import com.trainingapp.contract.exercise.SetType
import com.trainingapp.domain.plan.DraftSetField
import com.trainingapp.domain.plan.PlanDraft
import com.trainingapp.domain.plan.PlanDraftAction
import com.trainingapp.domain.plan.PlanDraftReducer
import com.trainingapp.domain.plan.alphabetLabel
import com.trainingapp.domain.plan.supersetLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlanDraftReducerTest {

    private val ids = SequentialIds()
    private fun PlanDraft.apply(vararg actions: PlanDraftAction) = actions.fold(this) { d, a -> PlanDraftReducer.reduce(d, a, ids) }
    private val PlanDraft.firstDay get() = days.first()
    private fun PlanDraft.exerciseIds() = firstDay.exercises.map { it.exerciseId }

    private fun draftWith(vararg exerciseIds: String): PlanDraft {
        val draft = PlanDraftReducer.newDraft(ids)
        return draft.apply(PlanDraftAction.AddExercises(draft.firstDay.id, exerciseIds.toList()))
    }

    @Test
    fun newDraftHasOneEmptyDayAndIsPristine() {
        val draft = PlanDraftReducer.newDraft(ids)
        assertEquals(1, draft.days.size)
        assertTrue(draft.isPristineNewPlan)
    }

    @Test
    fun addedExercisesKeepSelectionOrderAndStartWithOneWorkingSet() {
        val draft = draftWith("bench", "row", "squat")
        assertEquals(listOf("bench", "row", "squat"), draft.exerciseIds())
        assertTrue(draft.firstDay.exercises.all { it.sets.size == 1 && it.sets.single().type == SetType.WORKING })
    }

    @Test
    fun moveExerciseReordersAndClampsTheTarget() {
        val draft = draftWith("a", "b", "c")
        val dayId = draft.firstDay.id
        val c = draft.firstDay.exercises[2].id
        assertEquals(listOf("c", "a", "b"), draft.apply(PlanDraftAction.MoveExercise(dayId, c, 0)).exerciseIds())
        assertEquals(listOf("b", "c", "a"), draft.apply(PlanDraftAction.MoveExercise(dayId, draft.firstDay.exercises[0].id, 99)).exerciseIds())
    }

    @Test
    fun removeAndRestoreReturnsTheExerciseToItsPlace() {
        val draft = draftWith("a", "b", "c")
        val dayId = draft.firstDay.id
        val removed = draft.firstDay.exercises[1]
        val withoutB = draft.apply(PlanDraftAction.RemoveExercise(dayId, removed.id))
        assertEquals(listOf("a", "c"), withoutB.exerciseIds())
        assertEquals(draft.firstDay, withoutB.apply(PlanDraftAction.RestoreExercise(dayId, 1, removed)).firstDay)
    }

    @Test
    fun linkingBuildsSupersetsAndLabelsThemInOrder() {
        val draft = draftWith("a", "b", "c", "d", "e")
        val dayId = draft.firstDay.id
        val (a, b, c, d) = draft.firstDay.exercises.map { it.id }
        val linked = draft.apply(
            PlanDraftAction.LinkWithNext(dayId, a),
            PlanDraftAction.LinkWithNext(dayId, b), // a, b, c form one superset
            PlanDraftAction.LinkWithNext(dayId, d), // d, e form another
        )
        val groups = linked.firstDay.exercises.map { it.supersetGroup }
        assertEquals(groups[0], groups[1]); assertEquals(groups[1], groups[2])
        assertEquals(groups[3], groups[4]); assertTrue(groups[0] != groups[3])
        val labels = linked.firstDay.supersetLabels()
        assertEquals(listOf("A", "A", "A", "B", "B"), linked.firstDay.exercises.map { labels[it.id] })

        // Unlinking the middle of a three-exercise superset leaves two singles, which are ungrouped.
        val split = linked.apply(PlanDraftAction.Unlink(dayId, b))
        assertEquals(listOf(null, null, null), split.firstDay.exercises.take(3).map { it.supersetGroup })
        assertEquals(c, split.firstDay.exercises[2].id)
    }

    @Test
    fun movingAnExerciseOutOfASupersetUngroupsWhatIsLeftAlone() {
        val draft = draftWith("a", "b", "c")
        val dayId = draft.firstDay.id
        val (a, b) = draft.firstDay.exercises.map { it.id }
        val linked = draft.apply(PlanDraftAction.LinkWithNext(dayId, a))
        val moved = linked.apply(PlanDraftAction.MoveExercise(dayId, b, 2))
        assertEquals(listOf("a", "c", "b"), moved.exerciseIds())
        assertTrue(moved.firstDay.exercises.all { it.supersetGroup == null })
    }

    @Test
    fun removingASupersetMemberKeepsTheRestGrouped() {
        val draft = draftWith("a", "b", "c")
        val dayId = draft.firstDay.id
        val (a, b, c) = draft.firstDay.exercises.map { it.id }
        val linked = draft.apply(PlanDraftAction.LinkWithNext(dayId, a), PlanDraftAction.LinkWithNext(dayId, b))
        val removed = linked.apply(PlanDraftAction.RemoveExercise(dayId, c))
        val groups = removed.firstDay.exercises.map { it.supersetGroup }
        assertEquals(2, groups.size); assertEquals(groups[0], groups[1]); assertTrue(groups[0] != null)
    }

    @Test
    fun addSetCopiesTheLastSet() {
        val draft = draftWith("bench")
        val dayId = draft.firstDay.id
        val exercise = draft.firstDay.exercises.single()
        val setId = exercise.sets.single().id
        val edited = draft.apply(
            PlanDraftAction.UpdateSetValue(dayId, exercise.id, setId, DraftSetField.WEIGHT, "82,5"),
            PlanDraftAction.UpdateSetValue(dayId, exercise.id, setId, DraftSetField.REPS, "8–10"),
            PlanDraftAction.AddSet(dayId, exercise.id),
        )
        val sets = edited.firstDay.exercises.single().sets
        assertEquals(2, sets.size)
        assertEquals(sets[0].copy(id = sets[1].id), sets[1])
        assertTrue(sets[0].id != sets[1].id)
    }

    @Test
    fun setTypeCyclesWorkingWarmUpDrop() {
        val draft = draftWith("bench")
        val dayId = draft.firstDay.id
        val exercise = draft.firstDay.exercises.single()
        val cycle = PlanDraftAction.CycleSetType(dayId, exercise.id, exercise.sets.single().id)
        fun PlanDraft.type() = firstDay.exercises.single().sets.single().type
        assertEquals(SetType.WARM_UP, draft.apply(cycle).type())
        assertEquals(SetType.DROP, draft.apply(cycle, cycle).type())
        assertEquals(SetType.WORKING, draft.apply(cycle, cycle, cycle).type())
    }

    @Test
    fun daysCanBeAddedDuplicatedAndDeletedButNeverTheLastOne() {
        val draft = draftWith("a", "b")
        val dayId = draft.firstDay.id
        val (a) = draft.firstDay.exercises.map { it.id }
        val linked = draft.apply(PlanDraftAction.LinkWithNext(dayId, a))
        val duplicated = linked.apply(PlanDraftAction.DuplicateDay(dayId))
        assertEquals(2, duplicated.days.size)
        val copy = duplicated.days[1]
        assertEquals(listOf("a", "b"), copy.exercises.map { it.exerciseId })
        assertTrue(copy.exercises.none { it.id in linked.firstDay.exercises.map { e -> e.id } })
        assertEquals(copy.exercises[0].supersetGroup, copy.exercises[1].supersetGroup)
        assertTrue(copy.exercises[0].supersetGroup != linked.firstDay.exercises[0].supersetGroup)

        val single = PlanDraftReducer.newDraft(ids)
        assertSame(single, single.apply(PlanDraftAction.DeleteDay(single.firstDay.id)))
        val two = single.apply(PlanDraftAction.AddDay)
        assertEquals(listOf(two.days[1].id), two.apply(PlanDraftAction.DeleteDay(two.days[0].id)).days.map { it.id })
    }

    @Test
    fun anyEditClearsThePendingOperationButNoOpsDoNot() {
        val draft = draftWith("bench").copy(pendingOperationId = "op1")
        assertNull(draft.apply(PlanDraftAction.Rename("Góra A")).pendingOperationId)
        assertEquals("op1", draft.apply(PlanDraftAction.DeleteDay(draft.firstDay.id)).pendingOperationId)
    }

    @Test
    fun tagsAreTrimmedAndDeduplicatedAndSetRestIsEditable() {
        val draft = draftWith("bench")
        val dayId = draft.firstDay.id
        val exercise = draft.firstDay.exercises.single()
        val edited = draft.apply(
            PlanDraftAction.UpdateTags(listOf(" Hipertrofia ", "Hipertrofia", "", "Góra")),
            PlanDraftAction.UpdateSetValue(dayId, exercise.id, exercise.sets.single().id, DraftSetField.REST, "120"),
        )
        assertEquals(listOf("Hipertrofia", "Góra"), edited.tags)
        assertEquals("120", edited.firstDay.exercises.single().sets.single().rest)
    }

    @Test
    fun alphabetLabelsContinuePastZ() {
        assertEquals(listOf("A", "B", "Z", "AA", "AB"), listOf(0, 1, 25, 26, 27).map(::alphabetLabel))
    }
}
