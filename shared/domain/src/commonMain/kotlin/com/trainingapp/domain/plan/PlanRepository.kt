package com.trainingapp.domain.plan

import com.trainingapp.contract.plan.PlanVersionDto
import com.trainingapp.contract.text.SearchText
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
    /** The first exercise of the plan; its photo represents the plan in the list. */
    val firstExerciseId: String? = null,
    val tags: List<String> = emptyList(),
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

/** The filter chips of the plan list: "Wszystkie", "Szkice" (never published) and "Zapisane". */
enum class PlanListFilter { ALL, DRAFTS, PUBLISHED }

object PlanLibrarySearch {
    fun matches(item: PlanLibraryItem, filter: PlanListFilter): Boolean = when (filter) {
        PlanListFilter.ALL -> true
        PlanListFilter.DRAFTS -> item.isNewDraft
        PlanListFilter.PUBLISHED -> !item.isNewDraft
    }

    /** Accent-insensitive match on the plan name and its tags. */
    fun search(items: List<PlanLibraryItem>, query: String, filter: PlanListFilter): List<PlanLibraryItem> {
        val tokens = SearchText.tokens(query)
        return items.filter { item ->
            matches(item, filter) && tokens.all { token ->
                (listOf(item.name) + item.tags).any { SearchText.normalize(it).split(' ').any { word -> word.startsWith(token) } }
            }
        }
    }
}
