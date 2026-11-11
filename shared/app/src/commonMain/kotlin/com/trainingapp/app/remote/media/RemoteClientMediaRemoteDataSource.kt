package com.trainingapp.app.remote.media

import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.contract.media.UploadSessionDto
import com.trainingapp.data.media.MediaRemoteDataSource
import com.trainingapp.data.media.UploadResult
import com.trainingapp.data.remote.ApiClient
import com.trainingapp.data.remote.ApiHttpException
import com.trainingapp.data.remote.ApiOfflineException
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import pl.trainerapp.feature.chat.data.remote.RemoteClientContext
import pl.trainerapp.feature.chat.data.remote.rebaseUrl

/**
 * A client's technique video on the server (`/v1/media`): `POST upload-sessions` (idempotent by media id, bound to
 * the client's trainer relationship), then the bytes in tus-style `PATCH` chunks from the offset the server reports,
 * so an interrupted upload resumes instead of starting over. [status] asks `GET v1/media/{id}` and waits a little
 * while the server is still checking the file, because the media repository looks only once after an upload.
 *
 * - The relationship comes from the request, else from [context] (the media repository does not know it).
 * - The upload URL's host is ignored: the server signs with its public address, so the chunks go to `v1/media/uploads/{id}`
 *   on the API's own base URL. [baseUrl], when given, is also put on the playback URL ([rebaseUrl]).
 * - Outcomes: connection lost, `401`, `408`, `429`, `5xx` => [UploadResult.Interrupted]; any other refusal => [UploadResult.Rejected].
 */
class RemoteClientMediaRemoteDataSource(
    private val api: ApiClient,
    private val context: RemoteClientContext,
    private val baseUrl: String? = null,
    private val readBytes: suspend (String) -> ByteArray = ::readLocalFile,
    private val chunkBytes: Int = 1024 * 1024,
    private val statusAttempts: Int = 8,
    private val statusDelayMs: Long = 750,
) : MediaRemoteDataSource {

    override suspend fun upload(request: CreateUploadSessionRequest, localPath: String, onProgress: suspend (Float) -> Unit): UploadResult {
        onProgress(0f)
        return try {
            val relationshipId = request.relationshipId
                ?: if (request.kind == MediaKind.TECHNIQUE_VIDEO) context.ensure()?.relationshipId else null
            // Without a trainer there is nowhere to send it yet: stays queued until a relationship is known.
            if (request.kind == MediaKind.TECHNIQUE_VIDEO && relationshipId == null) return UploadResult.Interrupted
            val session = api.send(
                HttpMethod.Post,
                "v1/media/upload-sessions",
                request.copy(relationshipId = relationshipId),
                CreateUploadSessionRequest.serializer(),
                UploadSessionDto.serializer(),
            ) ?: return UploadResult.Interrupted
            when (session.state) {
                RemoteMediaState.FAILED -> return UploadResult.Rejected
                RemoteMediaState.PROCESSING, RemoteMediaState.READY -> {
                    onProgress(1f)
                    return UploadResult.Uploaded
                }
                RemoteMediaState.UPLOADING -> Unit
            }
            val bytes = readBytes(localPath)
            val size = session.sizeBytes
            if (bytes.size.toLong() != size) return UploadResult.Rejected // the file changed after it was announced
            val path = "v1/media/uploads/${request.mediaId}"
            var offset = session.offset.coerceIn(0L, size)
            var resyncs = 0
            while (offset < size) {
                try {
                    offset = patch(path, offset, bytes, minOf(size, offset + chunkBytes))
                    onProgress(offset.toFloat() / size)
                } catch (e: ApiHttpException) {
                    // 409: the server's offset differs from ours (a lost answer, or the file is already complete).
                    if (e.status != 409 || resyncs++ >= MAX_RESYNCS) throw e
                    offset = serverOffset(path)
                }
            }
            onProgress(1f)
            UploadResult.Uploaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiHttpException) {
            if (e.status == 401 || e.status == 408 || e.status == 429 || e.status >= 500) UploadResult.Interrupted else UploadResult.Rejected
        } catch (e: ApiOfflineException) {
            UploadResult.Interrupted
        } catch (e: Exception) {
            UploadResult.Interrupted
        }
    }

    override suspend fun status(mediaId: String): MediaStatusDto? {
        repeat(statusAttempts) { attempt ->
            val dto = try {
                api.getOrNull("v1/media/$mediaId", MediaStatusDto.serializer())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            } ?: return null
            if (dto.state != RemoteMediaState.PROCESSING || attempt == statusAttempts - 1) {
                return if (baseUrl != null && dto.playbackUrl != null) dto.copy(playbackUrl = rebaseUrl(dto.playbackUrl!!, baseUrl)) else dto
            }
            delay(statusDelayMs)
        }
        return null
    }

    /** Sends bytes `[offset, end)` and returns the offset the server now has. */
    private suspend fun patch(path: String, offset: Long, bytes: ByteArray, end: Long): Long {
        val response = api.raw(HttpMethod.Patch, path) {
            header("Tus-Resumable", "1.0.0")
            header("Upload-Offset", offset.toString())
            setBody(ByteArrayContent(bytes.copyOfRange(offset.toInt(), end.toInt()), OFFSET_STREAM))
        }
        return response.headers["Upload-Offset"]?.toLongOrNull() ?: end
    }

    private suspend fun serverOffset(path: String): Long =
        api.raw(HttpMethod.Head, path).headers["Upload-Offset"]?.toLongOrNull() ?: 0L

    private companion object {
        const val MAX_RESYNCS = 3
        val OFFSET_STREAM = ContentType("application", "offset+octet-stream")
    }
}

private suspend fun readLocalFile(path: String): ByteArray = withContext(Dispatchers.Default) {
    SystemFileSystem.source(Path(path.removePrefix("file://"))).buffered().use { it.readByteArray() }
}
