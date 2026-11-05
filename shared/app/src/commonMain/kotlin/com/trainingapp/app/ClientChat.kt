package com.trainingapp.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.koin.compose.koinInject
import pl.trainer.ui.PlatformBackHandler
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.chat.ChatMediaSource
import pl.trainerapp.feature.chat.ClientChatRoute
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
    var openVideo by rememberSaveable { mutableStateOf<String?>(null) }
    val videoId = openVideo
    AppTheme {
        if (videoId == null) {
            ClientChatRoute(
                repository = remember(store, relationshipId) { DemoClientChatRepository(store, relationshipId) },
                onBack = onBack,
                mediaSource = media,
                onOpenVideo = { openVideo = it },
            )
        } else {
            PlatformBackHandler(enabled = true) { openVideo = null }
            TechniqueVideoRoute(
                mediaId = videoId,
                viewer = ChatRole.Client,
                store = store,
                chats = remember(store) { DemoTrainerChatsRepository(store) },
                mediaSource = media,
                onBack = { openVideo = null },
                videoCard = AppTechniqueVideoCard,
            )
        }
    }
}
