package pl.trainerapp.feature.chat.data

import com.trainingapp.contract.media.MediaKind
import com.trainingapp.domain.media.MediaAsset
import com.trainingapp.domain.media.MediaRepository
import com.trainingapp.domain.media.MediaState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.trainerapp.feature.chat.ChatMediaSource

/**
 * Chat media from the shared media repository (the videos a client records in the live workout), plus the synthetic
 * fixture video behind the trainer home's "Wymaga uwagi" item. The fixture has no file, so it is READY without a
 * playback URL and is labelled as unavailable in the demo.
 */
class RepositoryChatMediaSource(private val media: MediaRepository) : ChatMediaSource {
    override fun observe(mediaId: String): Flow<MediaAsset?> = media.observe(mediaId).map { it ?: DemoChatMedia.fixtures[mediaId] }

    override suspend fun retry(mediaId: String) = media.retry(mediaId)
}

object DemoChatMedia {
    val fixtures: Map<String, MediaAsset> = listOf(TrainerChatsFixtures.aniaDeadliftVideo).associate { ref ->
        ref.mediaId to MediaAsset(
            id = ref.mediaId,
            ownerUserId = "c6",
            kind = MediaKind.TECHNIQUE_VIDEO,
            localPath = null,
            thumbnailPath = null,
            durationMs = ref.durationMs,
            sizeBytes = ref.sizeBytes ?: 0,
            state = MediaState.READY,
            progress = 1f,
            playbackUrl = null,
            updatedAtEpochMs = 0,
        )
    }
}
