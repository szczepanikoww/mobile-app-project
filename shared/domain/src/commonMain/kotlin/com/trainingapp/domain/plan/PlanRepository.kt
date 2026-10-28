package com.trainingapp.domain.plan

import com.trainingapp.contract.plan.PlanVersionDto
import com.trainingapp.contract.plan.PublishPlanRequest
import com.trainingapp.contract.plan.PublishPlanResponse
import kotlinx.coroutines.flow.Flow

/** One row in the plan library: a published template, a template with unpublished changes, or a new draft. */
data class PlanLibraryItem(
    val templateId: String?,
    val draftId: String?,
    val name: String,
    val dayCount: Int,
    val exerciseCount: Int,
    val latestVersion: Int?,
    val updatedAtEpochMs: Long,
) {
    /** Stable key for lists. */
    val key: String get() = templateId ?: "draft:$draftId"

    /** Never published: only a local draft exists. */
    val isNewDraft: Boolean get() = templateId == null

    /** Published, with local changes that are not published yet. */
    val hasUnpublishedChanges: Boolean get() = templateId != null && draftId != null
}

sealed interface PublishResult {
    data class Published(val response: PublishPlanResponse) : PublishResult

    /** The template has a newer version than the draft's base version. */
    data class Conflict(val latestVersion: Int) : PublishResult
    data object NetworkUnavailable : PublishResult
    data object Failed : PublishResult
}

interface PlanRepository {
    fun observeLibrary(): Flow<List<PlanLibraryItem>>

    suspend fun getDraft(draftId: String): PlanDraft?

    suspend fun findDraftForTemplate(templateId: String): PlanDraft?

    /** Written on every change. */
    suspend fun saveDraft(draft: PlanDraft)

    suspend fun deleteDraft(draftId: String)

    suspend fun getLatestVersion(templateId: String): PlanVersionDto?

    /** Needs the server. A retry with the same operation id returns the version that was already created. */
    suspend fun publish(request: PublishPlanRequest): PublishResult

    suspend fun archive(templateId: String)

    /** Exercise ids referenced by any published version. */
    suspend fun exerciseIdsInPublishedVersions(): Set<String>
}
