package com.trainingapp.domain

import com.trainingapp.contract.exercise.ExerciseStatus
import com.trainingapp.contract.plan.PlanTemplateDto
import com.trainingapp.contract.plan.PlanVersionDto
import com.trainingapp.contract.plan.PublishPlanRequest
import com.trainingapp.contract.plan.PublishPlanResponse
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.domain.exercise.SyncState
import com.trainingapp.domain.plan.PlanDraft
import com.trainingapp.domain.plan.PlanLibraryItem
import com.trainingapp.domain.plan.PlanRepository
import com.trainingapp.domain.plan.PublishResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeNetwork(online: Boolean = true) : NetworkMonitor {
    override val isOnline = MutableStateFlow(online)
}

class FakeExerciseRepository(initial: List<Exercise>) : ExerciseRepository {
    val exercises = MutableStateFlow(initial.associateBy { it.id })
    var syncSucceeds = true
    val syncCalls = mutableListOf<String>()

    override fun observeExercises(): Flow<List<Exercise>> = exercises.map { it.values.toList() }
    override fun observeExercise(id: String): Flow<Exercise?> = exercises.map { it[id] }
    override suspend fun getExercise(id: String) = exercises.value[id]
    override suspend fun saveCustomExercise(exercise: Exercise) {
        exercises.value = exercises.value + (exercise.id to exercise)
    }

    override suspend fun setHidden(id: String, hidden: Boolean) {
        val e = exercises.value[id] ?: return
        exercises.value = exercises.value + (id to e.copy(status = if (hidden) ExerciseStatus.HIDDEN else ExerciseStatus.ACTIVE))
    }

    override suspend fun syncExercise(id: String): Boolean {
        syncCalls += id
        if (!syncSucceeds) return false
        val e = exercises.value[id] ?: return false
        exercises.value = exercises.value + (id to e.copy(syncState = SyncState.SYNCED))
        return true
    }

    override suspend fun syncPending(): List<String> =
        exercises.value.values.filter { it.syncState != SyncState.SYNCED }.map { it.id }.filterNot { syncExercise(it) }

    override fun observeRecentlyUsedIds(): Flow<List<String>> = MutableStateFlow(emptyList())
    override suspend fun markUsed(ids: List<String>) = Unit
}

/** Behaves like the server: idempotent by operation id, conflict on a stale base version. */
class FakePlanRepository : PlanRepository {
    val drafts = mutableMapOf<String, PlanDraft>()
    val versions = mutableListOf<PlanVersionDto>()
    val operations = mutableMapOf<String, PublishPlanResponse>()
    var networkResult: PublishResult? = null
    var publishCalls = 0

    override fun observeLibrary(): Flow<List<PlanLibraryItem>> = MutableStateFlow(emptyList())
    override suspend fun getDraft(draftId: String) = drafts[draftId]
    override suspend fun findDraftForTemplate(templateId: String) = drafts.values.firstOrNull { it.templateId == templateId }
    override suspend fun saveDraft(draft: PlanDraft) { drafts[draft.draftId] = draft }
    override suspend fun deleteDraft(draftId: String) { drafts.remove(draftId) }
    override suspend fun getLatestVersion(templateId: String) = versions.filter { it.templateId == templateId }.maxByOrNull { it.number }
    override suspend fun archive(templateId: String) = Unit
    override suspend fun exerciseIdsInPublishedVersions() =
        versions.flatMap { v -> v.body.days.flatMap { d -> d.exercises.map { it.exerciseId } } }.toSet()

    override suspend fun publish(request: PublishPlanRequest): PublishResult {
        publishCalls++
        networkResult?.let { return it }
        operations[request.operationId]?.let { return PublishResult.Published(it) }
        val templateId = request.templateId ?: "template${operations.size + 1}"
        val latest = getLatestVersion(templateId)?.number ?: 0
        if (request.templateId != null && request.baseVersion != latest) return PublishResult.Conflict(latest)
        val version = PlanVersionDto("v-$templateId-${latest + 1}", templateId, latest + 1, 0, request.baseVersion, request.body)
        versions += version
        val response = PublishPlanResponse(PlanTemplateDto(templateId, "t1", request.body.name, version.number, null, 0), version)
        operations[request.operationId] = response
        return PublishResult.Published(response)
    }
}
