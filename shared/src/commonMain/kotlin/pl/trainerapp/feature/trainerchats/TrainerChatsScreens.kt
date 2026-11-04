package pl.trainerapp.feature.trainerchats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.trainer.resources.*
import pl.trainerapp.core.designsystem.AppShapes
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.core.designsystem.Avatar
import pl.trainerapp.core.designsystem.DetailHeader
import pl.trainerapp.core.designsystem.EmptyState
import pl.trainerapp.core.designsystem.ErrorState
import pl.trainerapp.core.designsystem.InlineBanner
import pl.trainerapp.core.designsystem.SkeletonBlock
import pl.trainerapp.core.designsystem.Spacing
import pl.trainerapp.core.designsystem.UnreadBadge
import pl.trainerapp.core.designsystem.frosted
import pl.trainerapp.core.designsystem.ScreenLoading
import pl.trainer.ui.Layout
import androidx.compose.material3.OutlinedTextField
import pl.trainerapp.core.format.formatListWhen
import pl.trainerapp.core.format.formatPaymentWhen
import pl.trainerapp.core.format.formatTime
import pl.trainerapp.core.format.formatUpdatedAt
import pl.trainerapp.feature.trainerchats.model.ChatMessage
import pl.trainerapp.feature.trainerchats.model.Conversation

private val Gutter = Modifier.padding(horizontal = Spacing.screenGutter)

// ---------------------------------------------------------------- Conversation list

@Composable
fun TrainerChatListRoute(
    repository: TrainerChatsRepository,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel { TrainerChatListViewModel(repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The view model outlives the screen, so pick up changes (read state, new messages) on every entry.
    LaunchedEffect(viewModel) { viewModel.onAction(ChatListAction.Reload) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatListEvent.OpenChat -> onOpenChat(event.clientId)
            }
        }
    }

    TrainerChatListScreen(state, viewModel::onAction, onBack, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainerChatListScreen(
    state: ChatListUiState,
    onAction: (ChatListAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DetailHeader(title = stringResource(Res.string.chats_title), onBack = onBack)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onAction(ChatListAction.Refresh) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Layout.gutter),
                verticalArrangement = Arrangement.spacedBy(Layout.small),
            ) {
                val snapshot = state.snapshot
                when {
                    state.status == ChatLoadStatus.Loading -> item { ScreenLoading(stringResource(Res.string.screen_loading)) }
                    state.status == ChatLoadStatus.Error -> item {
                        ErrorState(
                            title = stringResource(Res.string.error_load_chats),
                            retryLabel = stringResource(Res.string.action_retry),
                            onRetry = { onAction(ChatListAction.Refresh) },
                        )
                    }
                    snapshot != null -> {
                        if (snapshot.isOffline) {
                            item { InlineBanner(stringResource(Res.string.offline_banner, formatUpdatedAt(snapshot.lastUpdated))) }
                        }
                        if (snapshot.conversations.isEmpty()) {
                            item {
                                EmptyState(
                                    title = stringResource(Res.string.chats_empty_title),
                                    body = stringResource(Res.string.chats_empty_body),
                                    icon = Icons.Outlined.ChatBubbleOutline,
                                )
                            }
                        } else {
                            items(snapshot.conversations, key = { it.clientId }) { conversation ->
                                ConversationRow(conversation, snapshot.generatedAt) { onAction(ChatListAction.Open(conversation)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, now: kotlin.time.Instant, onClick: () -> Unit) {
    val unread = conversation.unread > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(Layout.tile)
            .frosted(Layout.tile)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Layout.padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Layout.medium),
    ) {
        Avatar(conversation.clientName)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                conversation.clientName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val preview = if (conversation.lastFromTrainer) {
                stringResource(Res.string.chats_you_prefix, conversation.lastMessage)
            } else {
                conversation.lastMessage
            }
            Text(
                preview,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal),
                color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
            Text(
                formatListWhen(conversation.lastAt, conversation.zone, now),
                style = MaterialTheme.typography.bodySmall,
                color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            if (unread) UnreadBadge(conversation.unread)
        }
    }
}

// ---------------------------------------------------------------- Conversation

@Composable
fun TrainerChatRoute(
    clientId: String,
    repository: TrainerChatsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel(key = "chat-$clientId") { TrainerChatViewModel(clientId, repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.onAction(ChatAction.Reload) }
    TrainerChatScreen(state, viewModel::onAction, onBack, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainerChatScreen(
    state: ChatUiState,
    onAction: (ChatAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DetailHeader(
            title = state.clientName.ifEmpty { stringResource(Res.string.chats_title) },
            onBack = onBack,
            leading = if (state.clientName.isNotEmpty()) {
                { Avatar(state.clientName, size = 40.dp) }
            } else {
                null
            },
        )
        when (state.status) {
            ChatLoadStatus.Loading -> ScreenLoading(stringResource(Res.string.screen_loading))
            ChatLoadStatus.Error -> ErrorState(
                title = stringResource(Res.string.error_load_chat),
                retryLabel = stringResource(Res.string.action_retry),
                onRetry = { onAction(ChatAction.Refresh) },
                modifier = Gutter,
            )
            ChatLoadStatus.Content -> {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { onAction(ChatAction.Refresh) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { MessageList(state) }
                Composer(state, onAction)
            }
        }
    }
}

@Composable
private fun MessageList(state: ChatUiState) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    if (state.messages.isEmpty()) {
        EmptyState(
            title = stringResource(Res.string.chat_empty_title),
            body = stringResource(Res.string.chat_empty_body),
            icon = Icons.Outlined.ChatBubbleOutline,
            modifier = Gutter,
        )
        return
    }
    val now = state.now
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Layout.gutter, vertical = Layout.padding),
        verticalArrangement = Arrangement.spacedBy(Layout.small),
    ) {
        items(state.messages, key = { it.id }) { message ->
            Bubble(message, state, now)
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage, state: ChatUiState, now: kotlin.time.Instant?) {
    val mine = message.fromTrainer
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (mine) 20.dp else 6.dp,
        bottomEnd = if (mine) 6.dp else 20.dp,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .then(if (mine) Modifier.background(MaterialTheme.colorScheme.primary, shape) else Modifier.frosted(shape))
                .padding(horizontal = Layout.padding, vertical = Layout.medium),
        ) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (now != null) formatPaymentWhen(message.at, state.zone, now) else formatTime(message.at, state.zone),
                style = MaterialTheme.typography.bodySmall,
                color = if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Layout.tiny),
            )
        }
    }
}

@Composable
private fun Composer(state: ChatUiState, onAction: (ChatAction) -> Unit) {
    val canSend = state.draft.isNotBlank() && !state.isSending
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Layout.gutter, vertical = Layout.small),
            verticalArrangement = Arrangement.spacedBy(Layout.small),
        ) {
            if (state.sendFailed) {
                Text(
                    stringResource(Res.string.chat_send_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = { onAction(ChatAction.DraftChanged(it)) },
                    placeholder = { Text(stringResource(Res.string.chat_placeholder)) },
                    maxLines = 4,
                    shape = Layout.card,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = { onAction(ChatAction.Send) },
                    enabled = canSend,
                    modifier = Modifier.size(Layout.target),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(Res.string.chat_send))
                }
            }
        }
    }
}
