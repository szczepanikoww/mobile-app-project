package com.trainingapp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trainingapp.app.resources.client_chats_find_body
import com.trainingapp.app.resources.client_chats_find_error
import com.trainingapp.app.resources.client_chats_find_none
import com.trainingapp.app.resources.client_chats_find_title
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.guest.component.TrainerRow
import com.trainingapp.feature.guest.model.GuestCatalogRepository
import com.trainingapp.feature.guest.model.TrainerQuery
import com.trainingapp.feature.guest.model.TrainerSummary
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import pl.trainer.ui.PlatformBackHandler
import com.trainingapp.designsystem.theme.AppTheme as DsTheme
import pl.trainerapp.core.designsystem.AppTheme
import com.trainingapp.domain.workout.CurrentClient
import org.jetbrains.compose.resources.stringResource
import com.trainingapp.app.resources.Res
import com.trainingapp.app.resources.client_chats_empty_body
import pl.trainerapp.feature.chat.ChatListRepository
import pl.trainerapp.feature.chat.ChatMediaSource
import pl.trainerapp.feature.chat.TrainerChatListRoute
import pl.trainerapp.feature.chat.data.DemoClientConversations
import pl.trainerapp.feature.chat.data.remote.ChatServerApi
import pl.trainerapp.feature.chat.data.remote.RemoteClientChatRepository
import pl.trainerapp.feature.chat.data.remote.RemoteClientContext
import pl.trainerapp.feature.chat.data.remote.RemoteClientConversations
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
internal fun ClientChat(relationshipId: String, onBack: () -> Unit, pinned: Boolean = false) {
    val store: DemoChatStore = koinInject()
    val media: ChatMediaSource = koinInject()
    // With a server the conversation is the client's own; without one it is the shared demo store. A conversation
    // chosen in the list ([pinned]) stays on that relationship instead of following the active one.
    val active: ClientChatRepository? = getKoin().getOrNull()
    val koin = getKoin()
    val remote: ClientChatRepository? = if (active != null && pinned) {
        remember(relationshipId) {
            RemoteClientChatRepository(koin.get<ChatServerApi>(), koin.get<RemoteClientContext>(), pinnedRelationshipId = relationshipId)
        }
    } else {
        active
    }
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

/**
 * A6 "Wiadomości": the client's conversations (in practice the one with their trainer); tapping one opens it. With
 * a server the list comes from the client's relationships, otherwise from the shared demo store.
 */
@Composable
internal fun ClientMessages(onBack: () -> Unit, onOpenChat: (String) -> Unit, onOpenTrainer: (String) -> Unit = {}) {
    val koin = getKoin()
    val store: DemoChatStore = koinInject()
    val client: CurrentClient = koinInject()
    val remote: ClientChatRepository? = koin.getOrNull()
    val repository: ChatListRepository = remember(remote, store, client.trainerRelationshipId) {
        if (remote != null) RemoteClientConversations(koin.get<ChatServerApi>()) else DemoClientConversations(store, setOf(client.trainerRelationshipId))
    }
    AppTheme {
        TrainerChatListRoute(
            repository = repository,
            onBack = onBack,
            onOpenChat = onOpenChat,
            emptyBody = stringResource(Res.string.client_chats_empty_body),
            // A client without a trainer picks one from the public list instead of seeing an empty screen.
            emptyContent = { AvailableTrainers(onOpenTrainer) },
        )
    }
}

private sealed interface TrainersLoad {
    data object Loading : TrainersLoad
    data object Failed : TrainersLoad
    data class Ready(val trainers: List<TrainerSummary>) : TrainersLoad
}

/** The public trainers a client without a trainer can choose from; a row opens that trainer's profile. */
@Composable
private fun AvailableTrainers(onOpenTrainer: (String) -> Unit) {
    val catalog: GuestCatalogRepository = koinInject()
    var attempt by remember { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<TrainersLoad>(TrainersLoad.Loading) }
    LaunchedEffect(attempt) {
        load = TrainersLoad.Loading
        load = try {
            TrainersLoad.Ready(catalog.trainers(TrainerQuery()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TrainersLoad.Failed
        }
    }
    Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SectionHeader(stringResource(Res.string.client_chats_find_title))
        Text(stringResource(Res.string.client_chats_find_body), style = DsTheme.type.compactBody, color = DsTheme.colors.textSecondary)
        when (val state = load) {
            TrainersLoad.Loading -> LoadingState(Modifier.height(160.dp))
            TrainersLoad.Failed -> ErrorState(stringResource(Res.string.client_chats_find_error), onRetry = { attempt++ })
            is TrainersLoad.Ready ->
                if (state.trainers.isEmpty()) {
                    Text(stringResource(Res.string.client_chats_find_none), style = DsTheme.type.compactBody, color = DsTheme.colors.textSecondary)
                } else {
                    state.trainers.forEach { trainer -> TrainerRow(trainer, onClick = { onOpenTrainer(trainer.id) }, compact = true) }
                }
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
