package com.trainingapp.app.remote.media

import com.trainingapp.contract.api.ApiErrorCodes
import com.trainingapp.contract.chat.SendMessageRequest
import com.trainingapp.data.remote.ApiHttpException
import com.trainingapp.data.remote.ApiOfflineException
import com.trainingapp.domain.workout.TechniqueVideoContext
import com.trainingapp.domain.workout.TechniqueVideoOutbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.trainerapp.feature.chat.data.remote.ChatServerApi
import pl.trainerapp.feature.chat.data.remote.RemoteClientContext
import pl.trainerapp.feature.chat.data.remote.TechniqueCaption

/**
 * Posts a recorded technique video into the client's conversation with the trainer
 * (`POST /v1/conversations/{relationshipId}/messages` with `mediaId`). The message id is the media id, so every
 * retry is the same message. Exercise and set travel as the first line of the text ([TechniqueCaption]) until the
 * workout sync links the set.
 *
 * The server accepts the message only after it knows the media (the upload session exists), which starts moments
 * after the import: a `409 DEPENDENCY_MISSING` is retried for a few seconds. When it still cannot be sent (offline,
 * server down) [send] returns `false` ("kept on the set") and keeps trying in [scope] with growing pauses. That
 * background retry lives in memory only: it is lost if the process dies (a persistent outbox is the proper fix).
 */
class RemoteTechniqueVideoOutbox(
    private val api: ChatServerApi,
    private val clientContext: RemoteClientContext,
    private val scope: CoroutineScope,
    private val dependencyRetryDelaysMs: List<Long> = listOf(500, 1_000, 2_000, 3_000),
    private val backgroundRetryDelaysMs: List<Long> = listOf(5_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000),
) : TechniqueVideoOutbox {

    private enum class Outcome { Sent, Retry, WaitForMedia, Refused }

    override suspend fun send(relationshipId: String, mediaId: String, context: TechniqueVideoContext): Boolean {
        val caption = TechniqueCaption.format(context.exerciseName, context.setNumber, context.setCount, context.weightGrams, context.reps)
        var outcome = attempt(relationshipId, mediaId, caption)
        for (pause in dependencyRetryDelaysMs) {
            if (outcome != Outcome.WaitForMedia) break
            delay(pause)
            outcome = attempt(relationshipId, mediaId, caption)
        }
        when (outcome) {
            Outcome.Sent -> return true
            Outcome.Refused -> return false
            Outcome.Retry, Outcome.WaitForMedia -> scope.launch { retryInBackground(relationshipId, mediaId, caption) }
        }
        return false
    }

    private suspend fun retryInBackground(relationshipId: String, mediaId: String, caption: String) {
        for (pause in backgroundRetryDelaysMs) {
            delay(pause)
            if (attempt(relationshipId, mediaId, caption) !in setOf(Outcome.Retry, Outcome.WaitForMedia)) return
        }
    }

    private suspend fun attempt(relationshipId: String, mediaId: String, caption: String): Outcome {
        val relationship = clientContext.ensure()?.relationshipId ?: relationshipId.takeIf { it.isNotBlank() } ?: return Outcome.Retry
        return try {
            api.send(relationship, SendMessageRequest(id = mediaId, text = caption, mediaId = mediaId))
            Outcome.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiHttpException) {
            when {
                e.status == 409 && e.code == ApiErrorCodes.DEPENDENCY_MISSING -> Outcome.WaitForMedia
                e.status == 401 || e.status == 408 || e.status == 429 || e.status >= 500 -> Outcome.Retry
                else -> Outcome.Refused
            }
        } catch (e: ApiOfflineException) {
            Outcome.Retry
        } catch (e: Exception) {
            Outcome.Retry
        }
    }
}
