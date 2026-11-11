package com.trainingapp.app.remote.media

import com.trainingapp.contract.chat.ConversationDto
import com.trainingapp.contract.chat.MessageDto
import com.trainingapp.contract.chat.MessagesDto
import com.trainingapp.contract.chat.ReadRequest
import com.trainingapp.contract.chat.SendMessageRequest
import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.data.remote.ApiClient
import io.ktor.http.HttpMethod
import kotlinx.serialization.builtins.ListSerializer
import pl.trainerapp.feature.chat.data.remote.ChatServerApi

/**
 * [ChatServerApi] over the app's [ApiClient] (the `/v1` API). The token comes from the ApiClient, so the client's
 * chat uses an ApiClient with the client's token and the trainer's media status one with the trainer's token
 * (`dev.<subject>`). Errors propagate as `ApiOfflineException` / `ApiHttpException`.
 */
class ApiClientChatServer(private val api: ApiClient) : ChatServerApi {
    override suspend fun conversations(): List<ConversationDto> =
        api.get("v1/client/conversations", ListSerializer(ConversationDto.serializer()))

    override suspend fun messages(relationshipId: String, afterSeq: Long, limit: Int): MessagesDto =
        api.get(
            "v1/conversations/$relationshipId/messages",
            MessagesDto.serializer(),
            listOf("afterSeq" to afterSeq.toString(), "limit" to limit.toString()),
        )

    override suspend fun send(relationshipId: String, request: SendMessageRequest): MessageDto =
        api.send(
            HttpMethod.Post,
            "v1/conversations/$relationshipId/messages",
            request,
            SendMessageRequest.serializer(),
            MessageDto.serializer(),
        ) ?: error("empty answer")

    override suspend fun markRead(relationshipId: String, upToSeq: Long) {
        api.send<ReadRequest, Unit>(HttpMethod.Post, "v1/conversations/$relationshipId/read", ReadRequest(upToSeq), ReadRequest.serializer())
    }

    override suspend fun mediaStatus(mediaId: String): MediaStatusDto? =
        api.getOrNull("v1/media/$mediaId", MediaStatusDto.serializer())
}
