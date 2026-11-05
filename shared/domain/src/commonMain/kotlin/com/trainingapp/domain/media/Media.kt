package com.trainingapp.domain.media

import com.trainingapp.contract.media.MediaKind
import com.trainingapp.contract.media.MediaLimits
import kotlinx.coroutines.flow.Flow

/**
 * Where a video is on its way to being playable. A video is shown as playable only when it is [READY];
 * [QUEUED] means it waits on this device for a connection.
 */
enum class MediaState { QUEUED, UPLOADING, PROCESSING, READY, FAILED }

data class MediaAsset(
    val id: String,
    /** The trainer (exercise demo) or client (technique video) who recorded it. */
    val ownerUserId: String,
    val kind: MediaKind,
    /** The copy kept on this device until the upload is confirmed. */
    val localPath: String?,
    val thumbnailPath: String?,
    val durationMs: Long?,
    val sizeBytes: Long,
    val state: MediaState,
    /** 0..1 while [MediaState.UPLOADING]. */
    val progress: Float,
    /** Present only when [MediaState.READY]. */
    val playbackUrl: String?,
    val updatedAtEpochMs: Long,
)

/** A video the trainer recorded or picked, already copied into app storage by the platform picker. */
data class PickedVideo(
    val localPath: String,
    val sizeBytes: Long,
    /** `null` when the platform cannot read it; the server checks the duration again. */
    val durationMs: Long?,
    val thumbnailPath: String?,
    val contentType: String = "video/mp4",
)

enum class VideoProblem { TOO_LONG, TOO_LARGE, EMPTY }

object VideoValidator {
    fun validate(video: PickedVideo, kind: MediaKind = MediaKind.EXERCISE_DEMO): VideoProblem? = when {
        video.sizeBytes <= 0 -> VideoProblem.EMPTY
        video.sizeBytes > MediaLimits.maxSizeBytes(kind) -> VideoProblem.TOO_LARGE
        (video.durationMs ?: 0) > MediaLimits.maxDurationMs(kind) -> VideoProblem.TOO_LONG
        else -> null
    }
}

/**
 * Trainer and client videos on their way to private storage. Importing persists the asset and its upload job, so an upload
 * survives process death and resumes when the device is online.
 */
interface MediaRepository {
    fun observe(id: String): Flow<MediaAsset?>

    fun observeAll(): Flow<Map<String, MediaAsset>>

    /**
     * Persists the asset as [MediaState.QUEUED] and starts the upload when possible. [ownerUserId] is the user who
     * recorded it; `null` means the signed-in trainer (exercise demos).
     */
    suspend fun import(video: PickedVideo, kind: MediaKind, ownerUserId: String? = null): MediaAsset

    /** Retries a [MediaState.FAILED] upload. */
    suspend fun retry(id: String)

    /** Sends every queued or interrupted upload. */
    suspend fun uploadPending()
}

/** Files on this device that the shared code cannot touch directly. */
fun interface LocalFiles {
    fun delete(path: String)
}
