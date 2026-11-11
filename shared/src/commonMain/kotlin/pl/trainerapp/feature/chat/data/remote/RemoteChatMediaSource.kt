package pl.trainerapp.feature.chat.data.remote

import com.trainingapp.contract.media.MediaKind
import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.domain.media.MediaAsset
import com.trainingapp.domain.media.MediaState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import pl.trainerapp.feature.chat.ChatMediaSource

/**
 * Upload state of chat media as the server knows it (`GET /v1/media/{id}`), for videos that are not in this
 * device's media repository: the trainer's view of a client's technique video, or the client's own video after a
 * reinstall. Polls every [pollMs] while the server is still receiving or checking the file (or cannot be reached)
 * and stops once it is READY or FAILED. A video is READY with a playback URL only when the server says so.
 *
 * [rebase] rewrites the signed playback URL (the server signs with its public address, which a phone or emulator
 * may not reach); see [rebaseUrl].
 */
class RemoteChatMediaSource(
    private val status: suspend (mediaId: String) -> MediaStatusDto?,
    private val pollMs: Long = 2_000,
    private val rebase: (String) -> String = { it },
) : ChatMediaSource {

    override fun observe(mediaId: String): Flow<MediaAsset?> = flow {
        var emitted: MediaAsset? = null
        while (true) {
            val dto = try {
                status(mediaId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (dto != null) {
                val asset = dto.toAsset(mediaId)
                if (asset != emitted) emit(asset)
                emitted = asset
                if (dto.state == RemoteMediaState.READY || dto.state == RemoteMediaState.FAILED) return@flow
            }
            delay(pollMs)
        }
    }

    /** Only the owner's device can retry an upload. */
    override suspend fun retry(mediaId: String) = Unit

    private fun MediaStatusDto.toAsset(id: String) = MediaAsset(
        id = id,
        ownerUserId = "",
        kind = MediaKind.TECHNIQUE_VIDEO,
        localPath = null,
        thumbnailPath = null,
        durationMs = null,
        sizeBytes = 0,
        state = when (state) {
            RemoteMediaState.UPLOADING -> MediaState.UPLOADING
            RemoteMediaState.PROCESSING -> MediaState.PROCESSING
            RemoteMediaState.READY -> MediaState.READY
            RemoteMediaState.FAILED -> MediaState.FAILED
        },
        progress = if (state == RemoteMediaState.UPLOADING) 0f else 1f,
        playbackUrl = if (state == RemoteMediaState.READY) playbackUrl?.let(rebase) else null,
        updatedAtEpochMs = 0,
    )
}

/**
 * Prefers this device's own copy ([local], with its real upload progress) and asks the server ([remote]) for
 * videos the device does not have.
 */
class CompositeChatMediaSource(
    private val local: ChatMediaSource,
    private val remote: ChatMediaSource,
) : ChatMediaSource {
    override fun observe(mediaId: String): Flow<MediaAsset?> = flow {
        val source = if (local.observe(mediaId).first() != null) local else remote
        source.observe(mediaId).collect { emit(it) }
    }

    override suspend fun retry(mediaId: String) = local.retry(mediaId)
}

/** Replaces the scheme, host and port of an absolute [url] with those of [baseUrl]; relative values are kept. */
fun rebaseUrl(url: String, baseUrl: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url
    val pathStart = url.indexOf('/', schemeEnd + 3)
    return if (pathStart < 0) baseUrl.trimEnd('/') else baseUrl.trimEnd('/') + url.substring(pathStart)
}
