package com.trainingapp.domain.plan

import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.plan.PlanIssue
import com.trainingapp.contract.plan.PublishPlanRequest
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.domain.exercise.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class GetPlanLibrary(private val repository: PlanRepository) {
    operator fun invoke(): Flow<List<PlanLibraryItem>> = repository.observeLibrary()
}

/** Opens the working copy for the editor. Nothing is written until the trainer changes something. */
class OpenPlanDraft(
    private val repository: PlanRepository,
    private val ids: IdGenerator,
) {
    fun newPlan(): PlanDraft = PlanDraftReducer.newDraft(ids)

    suspend fun existingDraft(draftId: String): PlanDraft? = repository.getDraft(draftId)

    /** The unpublished draft of [templateId] if there is one, otherwise a fresh draft of its latest version. */
    suspend fun forTemplate(templateId: String, decimalSeparator: Char, defaultDayName: (Int) -> String): PlanDraft? {
        repository.findDraftForTemplate(templateId)?.let { return it }
        val latest = repository.getLatestVersion(templateId) ?: return null
        return PlanDraftMapper.toDraft(latest.body, templateId, latest.number, decimalSeparator, ids, defaultDayName)
    }

    /** A new, unpublished copy with a new name. */
    fun duplicate(draft: PlanDraft, name: String): PlanDraft = draft.copy(
        draftId = ids.newId(),
        templateId = null,
        baseVersion = null,
        name = name,
        pendingOperationId = null,
    )
}

/** Writes the draft on every change, or drops a new plan nobody typed into. */
class SavePlanDraft(
    private val repository: PlanRepository,
    private val time: TimeSource,
) {
    suspend operator fun invoke(draft: PlanDraft): PlanDraft {
        if (draft.isPristineNewPlan) {
            repository.deleteDraft(draft.draftId)
            return draft
        }
        val stamped = draft.copy(updatedAtEpochMs = time.nowEpochMs())
        repository.saveDraft(stamped)
        return stamped
    }
}

sealed interface PublishOutcome {
    data class Published(val templateId: String, val versionNumber: Int) : PublishOutcome

    /** Nothing was sent. [issues] point at the fields to fix. */
    data class Invalid(val issues: List<PlanIssue>) : PublishOutcome

    /** Nothing was sent; the draft stays on the device. */
    data object Offline : PublishOutcome

    /** The plan uses own exercises that could not be synced, so the plan was not published. */
    data class ExerciseSyncFailed(val exerciseIds: List<String>) : PublishOutcome
    data class Conflict(val latestVersion: Int) : PublishOutcome
    data object Failed : PublishOutcome
}

/**
 * "Zapisz plan": validates locally, then publishes an immutable version. Publishing needs the server and never
 * reports success while offline. The operation id is stored in the draft before sending, so a retry after a lost
 * response or process death returns the same version. Unsynced own exercises are synced first.
 */
class PublishPlan(
    private val plans: PlanRepository,
    private val exercises: ExerciseRepository,
    private val network: NetworkMonitor,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(draft: PlanDraft, defaultDayName: (Int) -> String): PublishOutcome {
        val library = exercises.observeExercises().first().associateBy { it.id }
        val trackingTypeOf: (String) -> TrackingType? = { library[it]?.trackingType }
        val validation = PlanDraftMapper.validate(draft, trackingTypeOf, defaultDayName)
        if (!validation.isValid) return PublishOutcome.Invalid(validation.issues)
        if (!network.isOnline.value) return PublishOutcome.Offline

        val unsynced = validation.body.days
            .flatMap { day -> day.exercises.map { it.exerciseId } }
            .distinct()
            .filter { library[it]?.syncState?.let { state -> state != SyncState.SYNCED } == true }
        val failed = unsynced.filterNot { exercises.syncExercise(it) }
        if (failed.isNotEmpty()) return PublishOutcome.ExerciseSyncFailed(failed)

        val operationId = draft.pendingOperationId ?: ids.newId()
        if (operationId != draft.pendingOperationId) plans.saveDraft(draft.copy(pendingOperationId = operationId))

        val request = PublishPlanRequest(
            operationId = operationId,
            templateId = draft.templateId,
            baseVersion = draft.baseVersion,
            body = validation.body,
        )
        return when (val result = plans.publish(request)) {
            is PublishResult.Published -> {
                plans.deleteDraft(draft.draftId)
                PublishOutcome.Published(result.response.template.id, result.response.version.number)
            }
            is PublishResult.Conflict -> PublishOutcome.Conflict(result.latestVersion)
            PublishResult.NetworkUnavailable -> PublishOutcome.Offline
            PublishResult.Failed -> PublishOutcome.Failed
        }
    }
}
