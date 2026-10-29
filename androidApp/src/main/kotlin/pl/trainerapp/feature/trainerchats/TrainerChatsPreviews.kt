package pl.trainerapp.feature.trainerchats

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.trainerchats.data.TrainerChatsFixtures as Fx
import pl.trainerapp.feature.trainerchats.model.Conversation
import pl.trainerapp.feature.trainerchats.model.ConversationsSnapshot

private val conversations = Fx.clients.mapNotNull { (id, name) ->
    val last = Fx.threads[id]?.lastOrNull() ?: return@mapNotNull null
    Conversation(id, name, last.text, last.at, Fx.zone, last.fromTrainer, Fx.unread[id] ?: 0)
}.sortedByDescending { it.lastAt }

private val listState = ChatListUiState(
    status = ChatLoadStatus.Content,
    snapshot = ConversationsSnapshot(conversations, Fx.now, isOffline = false, lastUpdated = Fx.now),
)

private val chatState = ChatUiState(
    status = ChatLoadStatus.Content,
    clientName = "Piotr Szymański",
    messages = Fx.threads.getValue("c7") + Fx.threads.getValue("c1").takeLast(1),
    now = Fx.now,
    zone = Fx.zone,
    draft = "Białko możesz zjeść do 2 godzin po treningu.",
)

@Composable
private fun ListFrame(state: ChatListUiState) {
    AppTheme { TrainerChatListScreen(state, onAction = {}, onBack = {}) }
}

@Composable
private fun ChatFrame(state: ChatUiState) {
    AppTheme { TrainerChatScreen(state, onAction = {}, onBack = {}) }
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

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Rozmowa: błąd wysyłki")
@Composable
private fun ChatSendFailedPreview() = ChatFrame(chatState.copy(sendFailed = true))

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Rozmowa: pusta")
@Composable
private fun ChatEmptyPreview() = ChatFrame(chatState.copy(messages = emptyList(), draft = ""))
