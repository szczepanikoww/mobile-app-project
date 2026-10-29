package com.trainingapp.data.media

import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.domain.common.NetworkMonitor
import kotlinx.coroutines.delay

sealed interface UploadResult {
    /** The server has the file and is checking it. */
    data object Uploaded : UploadResult

    /** The connection dropped; the upload resumes later from where the server says it stopped. */
    data object Interrupted : UploadResult

    /** The server refused the file. */
    data object Rejected : UploadResult
}

/**
 * Upload sessions to private storage (architecture section 9.3): resumable, idempotent by media id, and the server
 * decides when the media is ready.
 */
interface MediaRemoteDataSource {
    suspend fun upload(request: CreateUploadSessionRequest, localPath: String, onProgress: suspend (Float) -> Unit): UploadResult

    suspend fun status(mediaId: String): MediaStatusDto?
}

/**
 * Stands in for the media service until the backend exists. It reports progress in steps, stops when the device
 * goes offline, and marks a file ready after a processing step. Playback uses the local copy.
 */
class FakeMediaRemoteDataSource(
    private val network: NetworkMonitor,
    private val stepDelayMs: Long = 0,
    private val steps: Int = 10,
) : MediaRemoteDataSource {
    private val uploaded = mutableMapOf<String, String>()
    var rejectNext = false

    override suspend fun upload(request: CreateUploadSessionRequest, localPath: String, onProgress: suspend (Float) -> Unit): UploadResult {
        if (rejectNext) {
            rejectNext = false
            return UploadResult.Rejected
        }
        for (step in 1..steps) {
            if (!network.isOnline.value) return UploadResult.Interrupted
            if (stepDelayMs > 0) delay(stepDelayMs)
            onProgress(step.toFloat() / steps)
        }
        uploaded[request.mediaId] = localPath
        return UploadResult.Uploaded
    }

    override suspend fun status(mediaId: String): MediaStatusDto? {
        if (!network.isOnline.value) return null
        val path = uploaded[mediaId] ?: return MediaStatusDto(mediaId, RemoteMediaState.FAILED)
        if (stepDelayMs > 0) delay(stepDelayMs * 2)
        return MediaStatusDto(mediaId, RemoteMediaState.READY, playbackUrl = "file://$path")
    }
}
