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
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.trainerapp.R
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
        DetailHeader(title = stringResource(R.string.chats_title), onBack = onBack)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onAction(ChatListAction.Refresh) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, top = Spacing.lg, bottom = Spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val snapshot = state.snapshot
                when {
                    state.status == ChatLoadStatus.Loading -> item { ListSkeleton() }
                    state.status == ChatLoadStatus.Error -> item {
                        ErrorState(
                            title = stringResource(R.string.error_load_chats),
                            retryLabel = stringResource(R.string.action_retry),
                            onRetry = { onAction(ChatListAction.Refresh) },
                        )
                    }
                    snapshot != null -> {
                        if (snapshot.isOffline) {
                            item { InlineBanner(stringResource(R.string.offline_banner, formatUpdatedAt(snapshot.lastUpdated))) }
                        }
                        if (snapshot.conversations.isEmpty()) {
                            item {
                                EmptyState(
                                    title = stringResource(R.string.chats_empty_title),
                                    body = stringResource(R.string.chats_empty_body),
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
private fun ListSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(5) { SkeletonBlock(height = 72.dp) }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, now: java.time.Instant, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val unread = conversation.unread > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .frosted(AppShapes.tile, alpha = 0.9f)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
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
                stringResource(R.string.chats_you_prefix, conversation.lastMessage)
            } else {
                conversation.lastMessage
            }
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal),
                color = if (unread) MaterialTheme.colorScheme.onSurface else colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                formatListWhen(conversation.lastAt, conversation.zone, now),
                style = MaterialTheme.typography.labelSmall,
                color = if (unread) colors.brand else colors.textMuted,
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
            title = state.clientName.ifEmpty { stringResource(R.string.chats_title) },
            onBack = onBack,
            leading = if (state.clientName.isNotEmpty()) {
                { Avatar(state.clientName, size = 36.dp) }
            } else {
                null
            },
        )
        when (state.status) {
            ChatLoadStatus.Loading -> Column(modifier = Gutter.padding(top = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SkeletonBlock(height = 48.dp, modifier = Modifier.fillMaxWidth(0.6f))
                SkeletonBlock(height = 48.dp, modifier = Modifier.fillMaxWidth(0.5f))
                SkeletonBlock(height = 48.dp, modifier = Modifier.fillMaxWidth(0.7f))
            }
            ChatLoadStatus.Error -> ErrorState(
                title = stringResource(R.string.error_load_chat),
                retryLabel = stringResource(R.string.action_retry),
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
            title = stringResource(R.string.chat_empty_title),
            body = stringResource(R.string.chat_empty_body),
            modifier = Gutter,
        )
        return
    }
    val now = state.now
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screenGutter, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(state.messages, key = { it.id }) { message ->
            Bubble(message, state, now)
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage, state: ChatUiState, now: java.time.Instant?) {
    val colors = AppTheme.colors
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
                .then(if (mine) Modifier.background(colors.brand, shape) else Modifier.frosted(shape, alpha = 0.95f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (now != null) formatPaymentWhen(message.at, state.zone, now) else formatTime(message.at, state.zone),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = if (mine) Color.White.copy(alpha = 0.8f) else colors.textMuted,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun Composer(state: ChatUiState, onAction: (ChatAction) -> Unit) {
    val colors = AppTheme.colors
    val canSend = state.draft.isNotBlank() && !state.isSending
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.95f))
            .border(1.dp, colors.border)
            .padding(horizontal = Spacing.screenGutter, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (state.sendFailed) {
            Text(
                stringResource(R.string.chat_send_failed),
                style = MaterialTheme.typography.bodySmall,
                color = colors.dangerText,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            BasicTextField(
                value = state.draft,
                onValueChange = { onAction(ChatAction.DraftChanged(it)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(colors.brand),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(AppShapes.pill)
                            .background(colors.chipSurface)
                            .padding(horizontal = Spacing.lg, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (state.draft.isEmpty()) {
                            Text(
                                stringResource(R.string.chat_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textMuted,
                            )
                        }
                        inner()
                    }
                },
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (canSend) colors.brand else colors.border)
                    .clickable(enabled = canSend, role = Role.Button) { onAction(ChatAction.Send) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send),
                    tint = if (canSend) Color.White else colors.textMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
