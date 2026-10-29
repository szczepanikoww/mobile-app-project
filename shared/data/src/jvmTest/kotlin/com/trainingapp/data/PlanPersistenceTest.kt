package com.trainingapp.data

import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.plan.PlanBodyDto
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlannedExerciseDto
import com.trainingapp.contract.plan.PlannedSetDto
import com.trainingapp.contract.plan.PublishPlanRequest
import com.trainingapp.data.db.createTrainingDatabase
import com.trainingapp.data.plan.FakePlanRemoteDataSource
import com.trainingapp.data.plan.LocalPlanRepository
import com.trainingapp.domain.plan.DraftSetField
import com.trainingapp.domain.plan.PlanDraft
import com.trainingapp.domain.plan.PlanDraftAction
import com.trainingapp.domain.plan.PlanDraftReducer
import com.trainingapp.domain.plan.PublishResult
import com.trainingapp.domain.plan.SavePlanDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanPersistenceTest {

    private val ids = TestIds()
    private val clock = TestClock()
    private val network = TestNetwork()

    private fun repository(driver: app.cash.sqldelight.db.SqlDriver): LocalPlanRepository {
        val db = createTrainingDatabase(driver)
        val remote = FakePlanRemoteDataSource(db, network, trainer, TestIds("server"), clock, Dispatchers.IO)
        return LocalPlanRepository(db, remote, clock, Dispatchers.IO)
    }

    private fun editedDraft(): PlanDraft {
        var draft = PlanDraftReducer.newDraft(ids)
        val dayId = draft.days.single().id
        fun apply(action: PlanDraftAction) { draft = PlanDraftReducer.reduce(draft, action, ids) }
        apply(PlanDraftAction.Rename("Hipertrofia Góra A – Faza 2"))
        apply(PlanDraftAction.AddExercises(dayId, listOf("bench", "row")))
        val bench = draft.days.single().exercises.first()
        apply(PlanDraftAction.UpdateSetValue(dayId, bench.id, bench.sets.single().id, DraftSetField.WEIGHT, "82,"))
        apply(PlanDraftAction.UpdateSetValue(dayId, bench.id, bench.sets.single().id, DraftSetField.REPS, "8–"))
        apply(PlanDraftAction.CycleSetType(dayId, bench.id, bench.sets.single().id))
        apply(PlanDraftAction.LinkWithNext(dayId, bench.id))
        apply(PlanDraftAction.AddDay)
        return draft
    }

    @Test
    fun draftSurvivesProcessDeathIncludingPartialInput() = runTest {
        val file = Files.createTempFile("training", ".db").toFile().apply { deleteOnExit() }
        val draft = editedDraft()

        val firstProcess = fileDriver(file)
        val saved = SavePlanDraft(repository(firstProcess), clock)(draft)
        firstProcess.close()

        val secondProcess = fileDriver(file)
        val reopened = repository(secondProcess).getDraft(draft.draftId)
        secondProcess.close()

        assertEquals(saved, reopened)
        val set = reopened!!.days.first().exercises.first().sets.single()
        assertEquals("82,", set.weight)
        assertEquals("8–", set.reps)
        assertEquals(SetType.WARM_UP, set.type)
    }

    @Test
    fun pristineNewPlanIsNotKept() = runTest {
        val repository = repository(memoryDriver())
        val draft = PlanDraftReducer.newDraft(ids)
        SavePlanDraft(repository, clock)(draft)
        assertNull(repository.getDraft(draft.draftId))
        assertTrue(repository.observeLibrary().first().isEmpty())
    }

    private fun body(name: String = "Góra A") = PlanBodyDto(
        name,
        listOf(PlanDayDto("d1", "Dzień A", listOf(PlannedExerciseDto("pe1", "bench", sets = listOf(PlannedSetDto("s1", repsMin = 8, repsMax = 8)))))),
    )

    @Test
    fun retryWithTheSameOperationIdCreatesOneVersion() = runTest {
        val repository = repository(memoryDriver())
        val first = assertIs<PublishResult.Published>(repository.publish(PublishPlanRequest("op-1", body = body())))
        val retry = assertIs<PublishResult.Published>(repository.publish(PublishPlanRequest("op-1", body = body())))
        assertEquals(first.response, retry.response)
        assertEquals(1, repository.getLatestVersion(first.response.template.id)?.number)
        assertEquals(1, repository.observeLibrary().first().size)
    }

    @Test
    fun versionsAreImmutableAndStaleBaseVersionConflicts() = runTest {
        val repository = repository(memoryDriver())
        val v1 = assertIs<PublishResult.Published>(repository.publish(PublishPlanRequest("op-1", body = body("Góra A"))))
        val templateId = v1.response.template.id
        val v2 = assertIs<PublishResult.Published>(repository.publish(PublishPlanRequest("op-2", templateId, 1, body("Góra A v2"))))
        assertEquals(2, v2.response.version.number)
        assertEquals(PublishResult.Conflict(2), repository.publish(PublishPlanRequest("op-3", templateId, 1, body("stale"))))
        // Version 1 is untouched by version 2.
        assertEquals("Góra A", v1.response.version.body.name)
        assertEquals("Góra A v2", repository.getLatestVersion(templateId)?.body?.name)
    }

    @Test
    fun offlinePublishSendsNothing() = runTest {
        val repository = repository(memoryDriver())
        network.isOnline.value = false
        assertEquals(PublishResult.NetworkUnavailable, repository.publish(PublishPlanRequest("op-1", body = body())))
        assertTrue(repository.observeLibrary().first().isEmpty())
    }

    @Test
    fun libraryShowsNewDraftsAndUnpublishedChanges() = runTest {
        val repository = repository(memoryDriver())
        val save = SavePlanDraft(repository, clock)
        val published = assertIs<PublishResult.Published>(repository.publish(PublishPlanRequest("op-1", body = body("Góra A"))))
        val newDraft = save(editedDraft())

        var library = repository.observeLibrary().first()
        assertEquals(listOf(newDraft.draftId, null), library.map { it.draftId })
        assertTrue(library[0].isNewDraft)
        assertEquals(2, library[0].dayCount)
        assertEquals(1, library[1].exerciseCount)
        assertEquals("bench", library[0].firstExerciseId)
        assertEquals("bench", library[1].firstExerciseId)

        val edit = save(editedDraft().copy(templateId = published.response.template.id, baseVersion = 1, name = "Góra A (zmiany)"))
        library = repository.observeLibrary().first()
        val template = library.single { it.templateId == published.response.template.id }
        assertTrue(template.hasUnpublishedChanges)
        assertEquals(edit.draftId, template.draftId)
        assertEquals("Góra A (zmiany)", template.name)

        repository.archive(published.response.template.id)
        assertEquals(listOf(newDraft.draftId), repository.observeLibrary().first().map { it.draftId })
    }
}
