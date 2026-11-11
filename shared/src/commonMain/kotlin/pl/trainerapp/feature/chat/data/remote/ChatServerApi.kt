package pl.trainerapp.feature.chat.data.remote

import com.trainingapp.contract.chat.ConversationDto
import com.trainingapp.contract.chat.MessageDto
import com.trainingapp.contract.chat.MessagesDto
import com.trainingapp.contract.chat.SendMessageRequest
import com.trainingapp.contract.media.MediaStatusDto

/**
 * The shared `/v1` chat and media-status endpoints, as the client sees them. `:shared` has no HTTP client of its own
 * for `/v1`, so the app adapts its `ApiClient` to this interface (`ApiClientChatServer` in `:shared:app`).
 * Calls throw when the server could not be reached or refused; nothing here logs message text.
 */
interface ChatServerApi {
    /** `GET /v1/client/conversations`. */
    suspend fun conversations(): List<ConversationDto>

    /** `GET /v1/conversations/{relationshipId}/messages?afterSeq=&limit=`. */
    suspend fun messages(relationshipId: String, afterSeq: Long, limit: Int): MessagesDto

    /** `POST /v1/conversations/{relationshipId}/messages`; resending the same request id returns the stored message. */
    suspend fun send(relationshipId: String, request: SendMessageRequest): MessageDto

    /** `POST /v1/conversations/{relationshipId}/read`. */
    suspend fun markRead(relationshipId: String, upToSeq: Long)

    /** `GET /v1/media/{id}`; `null` when the media does not exist or is not visible to the caller. */
    suspend fun mediaStatus(mediaId: String): MediaStatusDto?
}
