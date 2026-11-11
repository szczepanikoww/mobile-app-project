package com.trainingapp.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import pl.trainer.ui.PlatformBackHandler
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.chat.ChatMediaSource
import pl.trainerapp.feature.chat.ClientChatRepository
import pl.trainerapp.feature.chat.ClientChatRoute
import pl.trainerapp.feature.chat.TrainerChatsRepository
import pl.trainerapp.feature.chat.data.remote.RemoteClientVideoStore
import pl.trainerapp.feature.chat.model.ChatMessage
import pl.trainerapp.feature.chat.model.ConversationsSnapshot
import pl.trainerapp.feature.chat.TechniqueVideoRoute
import pl.trainerapp.feature.chat.data.DemoChatStore
import pl.trainerapp.feature.chat.data.DemoClientChatRepository
import pl.trainerapp.feature.chat.data.DemoTrainerChatsRepository
import pl.trainerapp.feature.chat.model.ChatRole

/**
 * A6: the client's conversation with their trainer, the same thread the trainer sees in the demo chat store. The
 * client's own technique video opens as a read-only player.
 */
@Composable
internal fun ClientChat(relationshipId: String, onBack: () -> Unit) {
    val store: DemoChatStore = koinInject()
    val media: ChatMediaSource = koinInject()
    // With a server the conversation is the client's own; without one it is the shared demo store.
    val remote: ClientChatRepository? = getKoin().getOrNull()
    var openVideo by rememberSaveable { mutableStateOf<String?>(null) }
    val videoId = openVideo
    AppTheme {
        if (videoId == null) {
            ClientChatRoute(
                repository = remote ?: remember(store, relationshipId) { DemoClientChatRepository(store, relationshipId) },
                onBack = onBack,
                mediaSource = media,
                onOpenVideo = { openVideo = it },
            )
        } else {
            PlatformBackHandler(enabled = true) { openVideo = null }
            TechniqueVideoRoute(
                mediaId = videoId,
                viewer = ChatRole.Client,
                store = if (remote != null) remember(remote) { RemoteClientVideoStore(remote) } else store,
                chats = if (remote != null) ClientHasNoReplies else remember(store) { DemoTrainerChatsRepository(store) },
                mediaSource = media,
                onBack = { openVideo = null },
                videoCard = AppTechniqueVideoCard,
            )
        }
    }
}

/** The read-only player never replies; the trainer-side repository is not available to a client. */
private object ClientHasNoReplies : TrainerChatsRepository {
    override suspend fun conversations(): ConversationsSnapshot = unsupported()
    override suspend fun thread(clientId: String) = unsupported()
    override suspend fun send(clientId: String, text: String): ChatMessage = unsupported()
    override suspend fun replyToVideo(clientId: String, mediaId: String, text: String): ChatMessage = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("a client cannot reply from the player")
}
