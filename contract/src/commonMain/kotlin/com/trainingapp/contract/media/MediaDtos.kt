package com.trainingapp.contract.media

import kotlinx.serialization.Serializable

/**
 * Server-side state of an uploaded file (architecture section 9.3). Only [READY] media may be played;
 * the server checks the file during [PROCESSING].
 */
@Serializable
enum class RemoteMediaState { PROCESSING, READY, FAILED }

@Serializable
enum class MediaKind { EXERCISE_DEMO }

/** Limits for the trainer's demo video. The architecture proposes 60 s and 100 MB; the team still has to agree on them. */
object MediaLimits {
    const val DEMO_VIDEO_MAX_DURATION_MS = 60_000L
    const val DEMO_VIDEO_MAX_SIZE_BYTES = 100L * 1024 * 1024
}

/** `POST /media/upload-sessions`: asks for a resumable upload to private storage. Idempotent by [mediaId]. */
@Serializable
data class CreateUploadSessionRequest(
    val mediaId: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    val contentType: String,
)

@Serializable
data class MediaStatusDto(
    val mediaId: String,
    val state: RemoteMediaState,
    /** A short-lived signed playback URL, present only when [state] is [RemoteMediaState.READY]. */
    val playbackUrl: String? = null,
)
