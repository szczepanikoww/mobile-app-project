package pl.trainerapp.feature.chat.data.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.TimeZone
import pl.trainerapp.feature.chat.ChatListRepository
import pl.trainerapp.feature.chat.model.Conversation
import pl.trainerapp.feature.chat.model.ConversationsSnapshot
import kotlin.time.Clock

/**
 * The client's conversation list (A6, "Wiadomości") from `GET /v1/client/conversations`. The list rows carry no
 * message text, so the preview is the newest message of each conversation (one message per row). Conversations
 * without messages are listed with an empty preview, so the client can still start writing.
 */
class RemoteClientConversations(
    private val api: ChatServerApi,
    private val zone: TimeZone = TimeZone.of("Europe/Warsaw"),
    private val clock: Clock = Clock.System,
) : ChatListRepository {

    override suspend fun conversations(): ConversationsSnapshot = coroutineScope {
        val now = clock.now()
        val rows = api.conversations().map { row ->
            async {
                val last = if (row.lastSeq > 0) api.messages(row.relationshipId, row.lastSeq - 1, 1).messages.lastOrNull() else null
                val fromTrainer = last?.senderId == row.counterpart.id
                Conversation(
                    relationshipId = row.relationshipId,
                    name = row.counterpart.name,
                    lastMessage = last?.let { message ->
                        val text = message.text.orEmpty()
                        // A client's technique video carries a machine caption; show only the note.
                        if (message.attachments.isNotEmpty() && !fromTrainer) TechniqueCaption.parse(text)?.note ?: text else text
                    }.orEmpty(),
                    lastAt = last?.createdAt ?: now,
                    zone = zone,
                    lastFromMe = last != null && !fromTrainer,
                    lastHasVideo = last?.attachments?.isNotEmpty() == true,
                    unread = row.unread,
                )
            }
        }.awaitAll()
        ConversationsSnapshot(rows.sortedByDescending { it.lastAt }, generatedAt = now, isOffline = false, lastUpdated = now)
    }
}
