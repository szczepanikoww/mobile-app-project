package com.trainingapp.app.remote

import com.trainingapp.contract.api.UploadSessionDto
import com.trainingapp.contract.exercise.ExerciseDto
import com.trainingapp.contract.exercise.UpsertExerciseRequest
import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.contract.plan.PlanVersionDto
import com.trainingapp.contract.plan.PublishPlanRequest
import com.trainingapp.contract.plan.PublishPlanResponse
import com.trainingapp.data.exercise.ExerciseRemoteDataSource
import com.trainingapp.data.media.MediaRemoteDataSource
import com.trainingapp.data.media.UploadResult
import com.trainingapp.data.plan.PlanRemoteDataSource
import com.trainingapp.domain.plan.PublishResult
import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import io.ktor.http.HttpMethod
import pl.trainer.data.remote.TrainerApiClient
import pl.trainer.domain.Failure
import pl.trainer.domain.TrainerException

private fun Throwable.failure(): Failure? = (this as? TrainerException)?.failure

/** `POST trainer/plans` (new template) or `POST trainer/plans/{id}/versions` (new version). */
class RemotePlanRemoteDataSource(private val api: TrainerApiClient) : PlanRemoteDataSource {
    override suspend fun publish(request: PublishPlanRequest): PublishResult {
        val templateId = request.templateId
        val path = if (templateId == null) "trainer/plans" else "trainer/plans/$templateId/versions"
        return try {
            PublishResult.Published(api.send<PublishPlanRequest, PublishPlanResponse>(HttpMethod.Post, path, request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrainerException) {
            when (e.failure) {
                Failure.Offline -> PublishResult.NetworkUnavailable
                Failure.Conflict -> conflict(templateId)
                else -> PublishResult.Failed
            }
        } catch (e: Exception) {
            PublishResult.Failed
        }
    }

    /** The server answered 409; the draft needs the template's newest version number to rebase. */
    private suspend fun conflict(templateId: String?): PublishResult {
        if (templateId == null) return PublishResult.Failed
        return try {
            PublishResult.Conflict(api.get<PlanVersionDto>("trainer/plans/$templateId/versions/latest").number)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.failure() == Failure.Offline) PublishResult.NetworkUnavailable else PublishResult.Failed
        }
    }
}

/** `PUT trainer/exercises/{id}`, `POST trainer/exercises/{id}/hide|unhide`. True only when the server confirmed. */
class RemoteExerciseRemoteDataSource(private val api: TrainerApiClient) : ExerciseRemoteDataSource {
    override suspend fun upsert(request: UpsertExerciseRequest): Boolean =
        confirmed { api.send<UpsertExerciseRequest, ExerciseDto>(HttpMethod.Put, "trainer/exercises/${request.id}", request) }

    override suspend fun setHidden(id: String, hidden: Boolean): Boolean =
        confirmed { api.command(HttpMethod.Post, "trainer/exercises/$id/${if (hidden) "hide" else "unhide"}") }

    private suspend fun confirmed(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}

/**
 * Upload session (`POST trainer/media/upload-sessions`), then the raw bytes `PUT` to the signed [UploadSessionDto.uploadUrl]
 * (no bearer token), and `GET trainer/media/{id}` for the status. [readBytes] defaults to reading the local file
 * with kotlinx-io, which works on Android and desktop (JVM file paths).
 */
class RemoteMediaRemoteDataSource(
    private val api: TrainerApiClient,
    private val http: HttpClient = HttpClient(),
    private val readBytes: suspend (String) -> ByteArray = ::readLocalFile,
) : MediaRemoteDataSource {

    override suspend fun upload(request: CreateUploadSessionRequest, localPath: String, onProgress: suspend (Float) -> Unit): UploadResult {
        onProgress(0f)
        return try {
            val session = api.send<CreateUploadSessionRequest, UploadSessionDto>(HttpMethod.Post, "trainer/media/upload-sessions", request)
            val bytes = readBytes(localPath)
            val response = http.put(session.uploadUrl) {
                setBody(ByteArrayContent(bytes, ContentType.parse(request.contentType)))
            }
            when {
                response.status.isSuccess() -> {
                    onProgress(1f)
                    UploadResult.Uploaded
                }
                // The server already has the bytes (a retry after a lost answer).
                response.status == HttpStatusCode.Conflict -> {
                    onProgress(1f)
                    UploadResult.Uploaded
                }
                response.status.value in 400..499 -> UploadResult.Rejected
                else -> UploadResult.Interrupted
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrainerException) {
            when (e.failure) {
                Failure.Offline, Failure.Save, Failure.Load -> UploadResult.Interrupted
                else -> UploadResult.Rejected
            }
        } catch (e: Exception) {
            UploadResult.Interrupted
        }
    }

    override suspend fun status(mediaId: String): MediaStatusDto? = try {
        api.get<MediaStatusDto>("trainer/media/$mediaId")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

private suspend fun readLocalFile(path: String): ByteArray = withContext(Dispatchers.Default) {
    SystemFileSystem.source(Path(path.removePrefix("file://"))).buffered().use { it.readByteArray() }
}
