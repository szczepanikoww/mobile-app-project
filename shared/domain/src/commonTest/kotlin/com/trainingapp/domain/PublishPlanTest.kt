package com.trainingapp.domain

import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.plan.PlanIssue
import com.trainingapp.contract.plan.SetField
import com.trainingapp.contract.plan.SetProblem
import com.trainingapp.domain.exercise.SyncState
import com.trainingapp.domain.plan.DraftSetField
import com.trainingapp.domain.plan.PlanDraft
import com.trainingapp.domain.plan.PlanDraftAction
import com.trainingapp.domain.plan.PlanDraftMapper
import com.trainingapp.domain.plan.PlanDraftReducer
import com.trainingapp.domain.plan.PublishOutcome
import com.trainingapp.domain.plan.PublishPlan
import com.trainingapp.domain.plan.PublishResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublishPlanTest {

    private val ids = SequentialIds()
    private val dayName = { i: Int -> "Dzień ${'A' + i}" }
    private val exercises = FakeExerciseRepository(
        listOf(
            exercise("bench", "Bench press"),
            exercise("plank", "Plank", trackingType = TrackingType.TIME),
            exercise("own", "Hip thrust na maszynie", owner = "t1", syncState = SyncState.PENDING),
        ),
    )
    private val plans = FakePlanRepository()
    private val network = FakeNetwork()
    private val publish = PublishPlan(plans, exercises, network, ids)

    private fun PlanDraft.apply(vararg actions: PlanDraftAction) = actions.fold(this) { d, a -> PlanDraftReducer.reduce(d, a, ids) }

    private fun validDraft(exerciseId: String = "bench", reps: String = "8"): PlanDraft {
        val draft = PlanDraftReducer.newDraft(ids)
        val dayId = draft.days.single().id
        val withExercise = draft.apply(PlanDraftAction.Rename("Góra A"), PlanDraftAction.AddExercises(dayId, listOf(exerciseId)))
        val exercise = withExercise.days.single().exercises.single()
        return withExercise.apply(
            PlanDraftAction.UpdateSetValue(dayId, exercise.id, exercise.sets.single().id, DraftSetField.REPS, reps),
            PlanDraftAction.UpdateSetValue(dayId, exercise.id, exercise.sets.single().id, DraftSetField.WEIGHT, "82,5"),
        ).also { plans.drafts[it.draftId] = it }
    }

    @Test
    fun invalidDraftIsNotSentAndPointsAtTheField() = runTest {
        val draft = validDraft(reps = "8–")
        val outcome = assertIs<PublishOutcome.Invalid>(publish(draft, dayName))
        val issue = assertIs<PlanIssue.SetValueInvalid>(outcome.issues.single())
        assertEquals(SetField.REPS, issue.field)
        assertEquals(SetProblem.INVALID, issue.problem)
        assertEquals(0, plans.publishCalls)
    }

    @Test
    fun parsedBodyUsesGramsAndDefaultDayNames() {
        val body = PlanDraftMapper.validate(validDraft(), { TrackingType.WEIGHT_REPS }, dayName).body
        assertEquals("Dzień A", body.days.single().name)
        val set = body.days.single().exercises.single().sets.single()
        assertEquals(82_500, set.weightGrams)
        assertEquals(8, set.repsMin)
        assertEquals(8, set.repsMax)
    }

    @Test
    fun fieldsOutsideTheTrackingTypeAreNotSent() {
        val draft = validDraft("plank")
        val dayId = draft.days.single().id
        val exercise = draft.days.single().exercises.single()
        val timed = draft.apply(PlanDraftAction.UpdateSetValue(dayId, exercise.id, exercise.sets.single().id, DraftSetField.DURATION, "1:00"))
        val validation = PlanDraftMapper.validate(timed, { TrackingType.TIME }, dayName)
        assertTrue(validation.isValid)
        val set = validation.body.days.single().exercises.single().sets.single()
        assertEquals(60, set.durationSeconds)
        assertNull(set.weightGrams)
        assertNull(set.repsMin)
    }

    @Test
    fun publishingCreatesVersionOneAndDeletesTheDraft() = runTest {
        val draft = validDraft()
        val outcome = assertIs<PublishOutcome.Published>(publish(draft, dayName))
        assertEquals(1, outcome.versionNumber)
        assertNull(plans.drafts[draft.draftId])
    }

    @Test
    fun offlinePublishNeverReportsSuccessAndKeepsTheDraft() = runTest {
        network.isOnline.value = false
        val draft = validDraft()
        assertEquals(PublishOutcome.Offline, publish(draft, dayName))
        assertEquals(0, plans.publishCalls)
        assertNotNull(plans.drafts[draft.draftId])
    }

    @Test
    fun retryAfterLostResponseReusesTheOperationAndCreatesOneVersion() = runTest {
        val draft = validDraft()
        // The server creates the version, but the response never reaches the app.
        plans.networkResult = null
        val firstAttempt = plans.publish(
            com.trainingapp.contract.plan.PublishPlanRequest("op-lost", null, null, PlanDraftMapper.validate(draft, { TrackingType.WEIGHT_REPS }, dayName).body),
        )
        assertIs<PublishResult.Published>(firstAttempt)
        val stored = draft.copy(pendingOperationId = "op-lost")
        plans.drafts[draft.draftId] = stored

        val outcome = assertIs<PublishOutcome.Published>(publish(stored, dayName))
        assertEquals(1, outcome.versionNumber)
        assertEquals(1, plans.versions.size)
    }

    @Test
    fun operationIdIsStoredBeforeSending() = runTest {
        val draft = validDraft()
        plans.networkResult = PublishResult.NetworkUnavailable
        assertEquals(PublishOutcome.Offline, publish(draft, dayName))
        val operationId = assertNotNull(plans.drafts[draft.draftId]?.pendingOperationId)

        plans.networkResult = null
        publish(plans.drafts.getValue(draft.draftId), dayName)
        assertEquals(setOf(operationId), plans.operations.keys)
    }

    @Test
    fun staleBaseVersionIsAConflictAndKeepsTheDraft() = runTest {
        val first = assertIs<PublishOutcome.Published>(publish(validDraft(), dayName))
        val second = validDraft().copy(templateId = first.templateId, baseVersion = 1)
        assertIs<PublishOutcome.Published>(publish(second, dayName))

        val stale = validDraft().copy(templateId = first.templateId, baseVersion = 1)
        plans.drafts[stale.draftId] = stale
        assertEquals(PublishOutcome.Conflict(latestVersion = 2), publish(stale, dayName))
        assertNotNull(plans.drafts[stale.draftId])
    }

    @Test
    fun unsyncedOwnExerciseIsSyncedFirst() = runTest {
        val outcome = publish(validDraft("own"), dayName)
        assertIs<PublishOutcome.Published>(outcome)
        assertEquals(listOf("own"), exercises.syncCalls)
    }

    @Test
    fun failedExerciseSyncStopsThePublish() = runTest {
        exercises.syncSucceeds = false
        val draft = validDraft("own")
        assertEquals(PublishOutcome.ExerciseSyncFailed(listOf("own")), publish(draft, dayName))
        assertEquals(0, plans.publishCalls)
        assertNotNull(plans.drafts[draft.draftId])
    }
}
