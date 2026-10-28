package com.trainingapp.data.plan

import com.trainingapp.contract.plan.PlanTemplateDto
import com.trainingapp.contract.plan.PlanVersionDto
import com.trainingapp.contract.plan.PublishPlanRequest
import com.trainingapp.contract.plan.PublishPlanResponse
import com.trainingapp.data.db.DataJson
import com.trainingapp.data.db.TrainingDatabase
import com.trainingapp.domain.common.CurrentUser
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.domain.plan.PublishResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** `POST /plans` and `POST /plans/{id}/versions`. */
interface PlanRemoteDataSource {
    suspend fun publish(request: PublishPlanRequest): PublishResult
}

/**
 * Stands in for the Training module until the backend exists (delivery slice 4). It follows the server contract:
 * idempotent by operation id, a conflict on a stale base version, ownership checked, and nothing while offline.
 * Its state lives in the same database as the local cache, so it survives restarts.
 */
class FakePlanRemoteDataSource(
    private val db: TrainingDatabase,
    private val network: NetworkMonitor,
    private val currentUser: CurrentUser,
    private val ids: IdGenerator,
    private val time: TimeSource,
    private val io: CoroutineDispatcher,
    private val latencyMs: Long = 0,
) : PlanRemoteDataSource {

    private val queries get() = db.planQueries

    override suspend fun publish(request: PublishPlanRequest): PublishResult {
        if (latencyMs > 0) delay(latencyMs)
        if (!network.isOnline.value) return PublishResult.NetworkUnavailable
        return withContext(io) {
            db.transactionWithResult {
                queries.selectFakeOperation(request.operationId).executeAsOneOrNull()?.let { op ->
                    return@transactionWithResult PublishResult.Published(load(op.template_id, op.version_number.toInt()))
                }
                val existing = request.templateId?.let { queries.selectTemplate(it).executeAsOneOrNull() }
                if (request.templateId != null) {
                    if (existing == null || existing.owner_trainer_id != currentUser.trainerId) {
                        return@transactionWithResult PublishResult.Failed
                    }
                    if (request.baseVersion != existing.latest_version.toInt()) {
                        return@transactionWithResult PublishResult.Conflict(existing.latest_version.toInt())
                    }
                }
                val templateId = request.templateId ?: ids.newId()
                val number = (existing?.latest_version?.toInt() ?: 0) + 1
                val now = time.nowEpochMs()
                val version = PlanVersionDto(
                    id = ids.newId(),
                    templateId = templateId,
                    number = number,
                    createdAtEpochMs = now,
                    basedOnVersion = request.baseVersion,
                    body = request.body,
                )
                queries.insertVersion(version.id, templateId, number.toLong(), DataJson.encodeToString(PlanVersionDto.serializer(), version))
                queries.upsertTemplate(templateId, currentUser.trainerId, request.body.name, number.toLong(), null, now)
                queries.insertFakeOperation(request.operationId, templateId, number.toLong())
                PublishResult.Published(load(templateId, number))
            }
        }
    }

    private fun load(templateId: String, number: Int): PublishPlanResponse {
        val template = queries.selectTemplate(templateId).executeAsOne()
        val version = queries.selectVersion(templateId, number.toLong()).executeAsOne()
        return PublishPlanResponse(
            template = PlanTemplateDto(
                id = template.id,
                ownerTrainerId = template.owner_trainer_id,
                name = template.name,
                latestVersion = template.latest_version.toInt(),
                archivedAtEpochMs = template.archived_at,
                updatedAtEpochMs = template.updated_at,
            ),
            version = DataJson.decodeFromString(PlanVersionDto.serializer(), version.json),
        )
    }
}
