package com.trainingapp.data.media

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.data.db.Media_asset
import com.trainingapp.data.db.TrainingDatabase
import com.trainingapp.domain.common.CurrentUser
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.domain.media.MediaAsset
import com.trainingapp.domain.media.MediaRepository
import com.trainingapp.domain.media.MediaState
import com.trainingapp.domain.media.PickedVideo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persists trainer videos and uploads them one at a time. Uploads start right after import when online, resume
 * whenever the device comes back online (also after process death), and a video becomes [MediaState.READY] only
 * when the server says so.
 */
class LocalMediaRepository(
    private val db: TrainingDatabase,
    private val remote: MediaRemoteDataSource,
    private val network: NetworkMonitor,
    private val currentUser: CurrentUser,
    private val ids: IdGenerator,
    private val time: TimeSource,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : MediaRepository {

    private val queries get() = db.mediaQueries
    private val uploadMutex = Mutex()

    init {
        scope.launch { network.isOnline.filter { it }.collect { uploadPending() } }
    }

    override fun observe(id: String): Flow<MediaAsset?> =
        queries.selectMedia(id).asFlow().mapToList(io).map { rows -> rows.firstOrNull()?.toAsset() }

    override fun observeAll(): Flow<Map<String, MediaAsset>> =
        queries.selectAllMedia().asFlow().mapToList(io).map { rows -> rows.map { it.toAsset() }.associateBy { it.id } }

    override suspend fun import(video: PickedVideo, kind: MediaKind): MediaAsset {
        val asset = MediaAsset(
            id = ids.newId(),
            ownerTrainerId = currentUser.trainerId,
            kind = kind,
            localPath = video.localPath,
            thumbnailPath = video.thumbnailPath,
            durationMs = video.durationMs,
            sizeBytes = video.sizeBytes,
            state = MediaState.QUEUED,
            progress = 0f,
            playbackUrl = null,
            updatedAtEpochMs = time.nowEpochMs(),
        )
        withContext(io) {
            queries.insertMedia(
                id = asset.id,
                owner_trainer_id = asset.ownerTrainerId,
                kind = kind.name,
                local_path = video.localPath,
                thumbnail_path = video.thumbnailPath,
                duration_ms = video.durationMs,
                size_bytes = video.sizeBytes,
                content_type = video.contentType,
                state = asset.state.name,
                progress = 0.0,
                playback_url = null,
                updated_at = asset.updatedAtEpochMs,
            )
        }
        if (network.isOnline.value) scope.launch { uploadPending() }
        return asset
    }

    override suspend fun retry(id: String) {
        val row = withContext(io) { queries.selectMedia(id).executeAsOneOrNull() } ?: return
        if (row.state != MediaState.FAILED.name) return
        setState(id, MediaState.QUEUED, 0f, null)
        if (network.isOnline.value) uploadPending()
    }

    override suspend fun uploadPending() = uploadMutex.withLock {
        val pending = withContext(io) { queries.selectMediaToUpload().executeAsList() }
        for (row in pending) {
            if (!network.isOnline.value) return@withLock
            upload(row)
        }
    }

    private suspend fun upload(row: Media_asset) {
        val path = row.local_path
        if (path == null) {
            setState(row.id, MediaState.FAILED, 0f, null)
            return
        }
        // A file the server already has (e.g. the app died while it was processing) only needs its status.
        if (row.state != MediaState.PROCESSING.name) {
            setState(row.id, MediaState.UPLOADING, row.progress.toFloat(), null)
            val request = CreateUploadSessionRequest(row.id, MediaKind.valueOf(row.kind), row.size_bytes, row.content_type)
            val result = runCatching {
                remote.upload(request, path) { progress -> setState(row.id, MediaState.UPLOADING, progress, null) }
            }.getOrDefault(UploadResult.Interrupted)
            when (result) {
                UploadResult.Interrupted -> {
                    setState(row.id, MediaState.QUEUED, currentProgress(row.id), null)
                    return
                }
                UploadResult.Rejected -> {
                    setState(row.id, MediaState.FAILED, 0f, null)
                    return
                }
                UploadResult.Uploaded -> setState(row.id, MediaState.PROCESSING, 1f, null)
            }
        }
        val status = runCatching { remote.status(row.id) }.getOrNull() ?: return // stays PROCESSING; checked again later
        when (status.state) {
            RemoteMediaState.READY -> setState(row.id, MediaState.READY, 1f, status.playbackUrl)
            RemoteMediaState.FAILED -> setState(row.id, MediaState.FAILED, 0f, null)
            RemoteMediaState.PROCESSING -> Unit
        }
    }

    private suspend fun currentProgress(id: String): Float =
        withContext(io) { queries.selectMedia(id).executeAsOneOrNull()?.progress?.toFloat() ?: 0f }

    private suspend fun setState(id: String, state: MediaState, progress: Float, playbackUrl: String?) = withContext(io) {
        queries.updateMediaState(state.name, progress.toDouble(), playbackUrl, time.nowEpochMs(), id)
    }

    private fun Media_asset.toAsset() = MediaAsset(
        id = id,
        ownerTrainerId = owner_trainer_id,
        kind = MediaKind.valueOf(kind),
        localPath = local_path,
        thumbnailPath = thumbnail_path,
        durationMs = duration_ms,
        sizeBytes = size_bytes,
        state = MediaState.valueOf(state),
        progress = progress.toFloat(),
        playbackUrl = playback_url,
        updatedAtEpochMs = updated_at,
    )
}
