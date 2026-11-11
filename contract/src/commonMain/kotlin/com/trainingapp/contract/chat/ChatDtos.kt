package com.trainingapp.contract.chat

import com.trainingapp.contract.api.IsoInstant
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.contract.schedule.TrainerRefDto
import kotlinx.serialization.Serializable

/*
 * One-to-one chat per trainer–client relationship (plan §8.5). Messages carry a client-generated id and a server
 * sequence number; stored messages are the truth, the WebSocket only says "fetch after seq N".
 */

@Serializable
data class ConversationDto(
    val relationshipId: String,
    /** The other party. */
    val counterpart: TrainerRefDto,
    val lastSeq: Long,
    val unread: Int,
    /** False when the relationship has ended: history stays readable, new messages are refused. */
    val canSend: Boolean,
)

@Serializable
data class AttachmentDto(val mediaId: String, val state: RemoteMediaState)

@Serializable
data class MessageDto(
    val id: String,
    val seq: Long,
    val senderId: String,
    val text: String? = null,
    val createdAt: IsoInstant,
    val workoutLogId: String? = null,
    val setLogId: String? = null,
    val attachments: List<AttachmentDto> = emptyList(),
)

@Serializable
data class MessagesDto(val messages: List<MessageDto>, val lastSeq: Long, val lastReadSeq: Long)

/**
 * `POST /v1/conversations/{relationshipId}/messages`. Resending the same [id] returns the stored message. A technique
 * video sets [mediaId], [workoutLogId] and [setLogId]; the set and the media must already be on the server, otherwise
 * `409 DEPENDENCY_MISSING` and the message stays queued.
 */
@Serializable
data class SendMessageRequest(
    val id: String,
    val text: String? = null,
    val mediaId: String? = null,
    val workoutLogId: String? = null,
    val setLogId: String? = null,
)

@Serializable
data class ReadRequest(val upToSeq: Long)

/** WebSocket `/v1/ws` frames. `kind` of a refresh is `schedule`, `wallet` or `chat`. */
@Serializable
data class LiveEventDto(
    val type: String,
    val relationshipId: String? = null,
    val seq: Long? = null,
    val kind: String? = null,
)
