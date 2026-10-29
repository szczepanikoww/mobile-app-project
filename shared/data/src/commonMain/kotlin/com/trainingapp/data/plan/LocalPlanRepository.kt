package com.trainingapp.data.plan

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.trainingapp.contract.plan.PlanVersionDto
import com.trainingapp.contract.plan.PublishPlanRequest
import com.trainingapp.data.db.DataJson
import com.trainingapp.data.db.Plan_draft
import com.trainingapp.data.db.TrainingDatabase
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.domain.exercise.ExerciseUsage
import com.trainingapp.domain.plan.PlanDraft
import com.trainingapp.domain.plan.PlanLibraryItem
import com.trainingapp.domain.plan.PlanRepository
import com.trainingapp.domain.plan.PublishResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** Drafts and the cache of published plans live in SQLDelight; publishing goes through [PlanRemoteDataSource]. */
class LocalPlanRepository(
    private val db: TrainingDatabase,
    private val remote: PlanRemoteDataSource,
    private val time: TimeSource,
    private val io: CoroutineDispatcher,
) : PlanRepository {

    private val queries get() = db.planQueries

    override fun observeLibrary(): Flow<List<PlanLibraryItem>> = combine(
        queries.selectActiveTemplates().asFlow().mapToList(io),
        queries.selectAllDrafts().asFlow().mapToList(io),
    ) { templates, draftRows ->
        val drafts = draftRows.map { it.toDraft() }
        val draftsByTemplate = drafts.filter { it.templateId != null }.groupBy { it.templateId }
        val published = templates.map { template ->
            val draft = draftsByTemplate[template.id]?.maxByOrNull { it.updatedAtEpochMs }
            val body = if (draft == null) withContext(io) { version(template.id, template.latest_version.toInt())?.body } else null
            PlanLibraryItem(
                templateId = template.id,
                draftId = draft?.draftId,
                name = draft?.name?.ifBlank { null } ?: template.name,
                dayCount = draft?.days?.size ?: body?.days?.size ?: 0,
                exerciseCount = draft?.exerciseCount ?: body?.days?.sumOf { it.exercises.size } ?: 0,
                latestVersion = template.latest_version.toInt(),
                updatedAtEpochMs = maxOf(template.updated_at, draft?.updatedAtEpochMs ?: 0),
                firstExerciseId = draft?.days?.flatMap { it.exercises }?.firstOrNull()?.exerciseId
                    ?: body?.days?.flatMap { it.exercises }?.firstOrNull()?.exerciseId,
                tags = draft?.tags ?: body?.tags.orEmpty(),
            )
        }
        val newDrafts = drafts.filter { it.templateId == null }.map { draft ->
            PlanLibraryItem(
                templateId = null,
                draftId = draft.draftId,
                name = draft.name,
                dayCount = draft.days.size,
                exerciseCount = draft.exerciseCount,
                latestVersion = null,
                updatedAtEpochMs = draft.updatedAtEpochMs,
                firstExerciseId = draft.days.flatMap { it.exercises }.firstOrNull()?.exerciseId,
                tags = draft.tags,
            )
        }
        (published + newDrafts).sortedByDescending { it.updatedAtEpochMs }
    }

    override suspend fun getDraft(draftId: String): PlanDraft? = withContext(io) {
        queries.selectDraft(draftId).executeAsOneOrNull()?.toDraft()
    }

    override suspend fun findDraftForTemplate(templateId: String): PlanDraft? = withContext(io) {
        queries.selectDraftForTemplate(templateId).executeAsOneOrNull()?.toDraft()
    }

    override suspend fun saveDraft(draft: PlanDraft): Unit = withContext(io) {
        queries.upsertDraft(
            draft_id = draft.draftId,
            template_id = draft.templateId,
            json = DataJson.encodeToString(PlanDraft.serializer(), draft),
            updated_at = draft.updatedAtEpochMs,
        )
    }

    override suspend fun deleteDraft(draftId: String): Unit = withContext(io) { queries.deleteDraft(draftId) }

    override suspend fun getLatestVersion(templateId: String): PlanVersionDto? = withContext(io) {
        val template = queries.selectTemplate(templateId).executeAsOneOrNull() ?: return@withContext null
        version(templateId, template.latest_version.toInt())
    }

    override suspend fun publish(request: PublishPlanRequest): PublishResult {
        val result = runCatching { remote.publish(request) }.getOrElse { PublishResult.NetworkUnavailable }
        if (result is PublishResult.Published) {
            val (template, version) = result.response
            withContext(io) {
                db.transaction {
                    queries.insertVersion(version.id, template.id, version.number.toLong(), DataJson.encodeToString(PlanVersionDto.serializer(), version))
                    queries.upsertTemplate(
                        template.id, template.ownerTrainerId, template.name, template.latestVersion.toLong(),
                        template.archivedAtEpochMs, template.updatedAtEpochMs,
                    )
                }
            }
        }
        return result
    }

    // TODO(slice 4): send the archive to the server; until then it only hides the template on this device.
    override suspend fun archive(templateId: String): Unit = withContext(io) {
        queries.archiveTemplate(time.nowEpochMs(), templateId)
    }

    override suspend fun exerciseIdsInPublishedVersions(): Set<String> = withContext(io) {
        queries.selectAllVersionJson().executeAsList()
            .flatMap { json -> DataJson.decodeFromString(PlanVersionDto.serializer(), json).body.days.flatMap { day -> day.exercises.map { it.exerciseId } } }
            .toSet()
    }

    private fun version(templateId: String, number: Int): PlanVersionDto? =
        queries.selectVersion(templateId, number.toLong()).executeAsOneOrNull()
            ?.let { DataJson.decodeFromString(PlanVersionDto.serializer(), it.json) }

    private fun Plan_draft.toDraft(): PlanDraft = DataJson.decodeFromString(PlanDraft.serializer(), json)
}

/** An exercise is in use once a published plan version references it. Workout logs will be added with the logger. */
class PublishedPlanExerciseUsage(private val plans: PlanRepository) : ExerciseUsage {
    override suspend fun isInUse(exerciseId: String): Boolean = exerciseId in plans.exerciseIdsInPublishedVersions()
}
