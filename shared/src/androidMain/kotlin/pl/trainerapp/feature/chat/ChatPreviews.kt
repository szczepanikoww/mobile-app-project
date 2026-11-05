package pl.trainerapp.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.domain.media.MediaAsset
import com.trainingapp.domain.media.MediaState
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.chat.data.DemoChatMedia
import pl.trainerapp.feature.chat.data.TrainerChatsFixtures as Fx
import pl.trainerapp.feature.chat.model.ChatMessage
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.model.Conversation
import pl.trainerapp.feature.chat.model.ConversationsSnapshot
import pl.trainerapp.feature.chat.model.MediaRef

private val conversations = Fx.clients.mapNotNull { (id, name) ->
    val last = Fx.threads[id]?.lastOrNull() ?: return@mapNotNull null
    Conversation(id, name, last.text, last.at, Fx.zone, last.author == ChatRole.Trainer, last.attachment != null, Fx.unread[id] ?: 0)
}.sortedByDescending { it.lastAt }

private val listState = ChatListUiState(
    status = ChatLoadStatus.Content,
    snapshot = ConversationsSnapshot(conversations, Fx.now, isOffline = false, lastUpdated = Fx.now),
)

private val chatState = ChatUiState(
    status = ChatLoadStatus.Content,
    name = "Piotr Szymański",
    messages = Fx.threads.getValue("c7") + Fx.threads.getValue("c1").takeLast(1),
    now = Fx.now,
    zone = Fx.zone,
    draft = "Białko możesz zjeść do 2 godzin po treningu.",
)

/** The client's view (A6) with a technique video that is still uploading. */
private val uploadingVideo = MediaRef("m-up", "Wyciskanie sztangi leżąc", setNumber = 3, setCount = 4, weightGrams = 82_500, reps = 8)
private val clientChatState = ChatUiState(
    viewer = ChatRole.Client,
    status = ChatLoadStatus.Content,
    name = Fx.trainerName,
    messages = Fx.threads.getValue("c1") + ChatMessage("v1", ChatRole.Client, "", Fx.now, uploadingVideo),
    media = mapOf(
        "m-up" to MediaAsset("m-up", "c1", MediaKind.TECHNIQUE_VIDEO, null, null, 21_000, 15_000_000, MediaState.UPLOADING, 0.72f, null, 0),
    ),
    now = Fx.now,
    zone = Fx.zone,
)

private val trainerVideoState = chatState.copy(
    name = "Ania Kruk",
    messages = Fx.threads.getValue("c6"),
    media = DemoChatMedia.fixtures,
    draft = "",
)

@Composable
private fun ListFrame(state: ChatListUiState) {
    AppTheme { TrainerChatListScreen(state, onAction = {}, onBack = {}) }
}

@Composable
private fun ChatFrame(state: ChatUiState) {
    AppTheme { ChatScreen(state, onAction = {}, onBack = {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Lista rozmów")
@Composable
private fun ListPreview() = ListFrame(listState)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, fontScale = 1.5f, name = "Lista 1.5x")
@Composable
private fun ListLargeFontPreview() = ListFrame(listState)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Lista: ładowanie")
@Composable
private fun ListLoadingPreview() = ListFrame(ChatListUiState())

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Lista: błąd")
@Composable
private fun ListErrorPreview() = ListFrame(ChatListUiState(status = ChatLoadStatus.Error))

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Lista: pusta")
@Composable
private fun ListEmptyPreview() = ListFrame(
    listState.copy(snapshot = listState.snapshot?.copy(conversations = emptyList())),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Rozmowa")
@Composable
private fun ChatPreview() = ChatFrame(chatState)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Rozmowa: wideo techniki (trener)")
@Composable
private fun TrainerVideoPreview() = ChatFrame(trainerVideoState)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "A6 Czat klienta: wysyłanie wideo")
@Composable
private fun ClientChatPreview() = ChatFrame(clientChatState)

@Preview(showBackground = true, widthDp = 320, heightDp = 720, fontScale = 2f, name = "A6 Czat klienta 2x")
@Composable
private fun ClientChatLargeFontPreview() = ChatFrame(clientChatState)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Rozmowa: błąd wysyłki")
@Composable
private fun ChatSendFailedPreview() = ChatFrame(chatState.copy(sendFailed = true))

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Rozmowa: pusta")
@Composable
private fun ChatEmptyPreview() = ChatFrame(chatState.copy(messages = emptyList(), draft = ""))

private val reviewState = TechniqueVideoUiState(
    viewer = ChatRole.Trainer,
    status = ReviewStatus.Content,
    relationshipId = "c6",
    clientName = "Ania Kruk",
    message = Fx.threads.getValue("c6").first(),
    asset = DemoChatMedia.fixtures["m1"],
    now = Fx.now,
)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "A5 Przegląd wideo")
@Composable
private fun ReviewPreview() = AppTheme { TechniqueVideoScreen(reviewState, {}, {}, {}, {}, {}, {}) }

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "A5 Przegląd: przetwarzanie")
@Composable
private fun ReviewProcessingPreview() = AppTheme {
    TechniqueVideoScreen(reviewState.copy(asset = reviewState.asset?.copy(state = MediaState.PROCESSING)), {}, {}, {}, {}, {}, {})
}
