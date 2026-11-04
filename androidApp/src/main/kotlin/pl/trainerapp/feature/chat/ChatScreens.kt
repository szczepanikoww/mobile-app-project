package pl.trainerapp.feature.chat

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
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import com.trainingapp.domain.media.MediaAsset
import com.trainingapp.domain.media.MediaState
import pl.trainerapp.feature.chat.model.Delivery
import pl.trainerapp.feature.chat.model.MediaRef
import kotlin.math.roundToInt
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
import pl.trainerapp.feature.chat.model.ChatMessage
import pl.trainerapp.feature.chat.model.Conversation

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
                is ChatListEvent.OpenChat -> onOpenChat(event.relationshipId)
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
                            items(snapshot.conversations, key = { it.relationshipId }) { conversation ->
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
        Avatar(conversation.name)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                conversation.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val text = if (conversation.lastHasVideo) stringResource(R.string.chat_video_preview, conversation.lastMessage) else conversation.lastMessage
            val preview = if (conversation.lastFromMe) stringResource(R.string.chats_you_prefix, text) else text
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

/** The trainer's conversation with one client. Tapping a technique video opens the review (A5). */
@Composable
fun TrainerChatRoute(
    clientId: String,
    repository: TrainerChatsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    mediaSource: ChatMediaSource = ChatMediaSource.None,
    onOpenVideo: (String) -> Unit = {},
) {
    val viewModel = viewModel(key = "chat-$clientId") { TrainerChatViewModel(clientId, repository, mediaSource) }
    ChatRouteContent(viewModel, onBack, onOpenVideo, modifier)
}

/** A6: the client's conversation with their trainer. Same screen as the trainer's; only the header and entry differ. */
@Composable
fun ClientChatRoute(
    repository: ClientChatRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    mediaSource: ChatMediaSource = ChatMediaSource.None,
    onOpenVideo: (String) -> Unit = {},
) {
    val viewModel = viewModel(key = "client-chat-${repository.relationshipId}") { ClientChatViewModel(repository, mediaSource) }
    ChatRouteContent(viewModel, onBack, onOpenVideo, modifier)
}

@Composable
private fun ChatRouteContent(viewModel: ChatViewModel, onBack: () -> Unit, onOpenVideo: (String) -> Unit, modifier: Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnOpenVideo by rememberUpdatedState(onOpenVideo)
    LaunchedEffect(viewModel) { viewModel.onAction(ChatAction.Reload) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatEvent.OpenVideo -> currentOnOpenVideo(event.mediaId)
            }
        }
    }
    ChatScreen(state, viewModel::onAction, onBack, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onAction: (ChatAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DetailHeader(
            title = state.name.ifEmpty { stringResource(R.string.chats_title) },
            onBack = onBack,
            leading = if (state.name.isNotEmpty()) {
                { Avatar(state.name, size = 36.dp) }
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
                ) { MessageList(state, onAction) }
                Composer(state, onAction)
            }
        }
    }
}

@Composable
private fun MessageList(state: ChatUiState, onAction: (ChatAction) -> Unit) {
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
            Bubble(message, state, now, onAction)
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage, state: ChatUiState, now: java.time.Instant?, onAction: (ChatAction) -> Unit) {
    val colors = AppTheme.colors
    val mine = message.author == state.viewer
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (mine) 20.dp else 6.dp,
        bottomEnd = if (mine) 6.dp else 20.dp,
    )
    val contentColor = if (mine) Color.White else MaterialTheme.colorScheme.onSurface
    val mutedColor = if (mine) Color.White.copy(alpha = 0.85f) else colors.textMuted
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .then(if (mine) Modifier.background(colors.brand, shape) else Modifier.frosted(shape, alpha = 0.95f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (message.quotedMediaId != null) {
                Text(
                    stringResource(R.string.chat_reply_to_video),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = mutedColor,
                )
            }
            message.attachment?.let { video ->
                VideoAttachment(video, state.media[video.mediaId], mine, onAction)
            }
            if (message.text.isNotBlank()) {
                Text(message.text, style = MaterialTheme.typography.bodyMedium, color = contentColor)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (now != null) formatPaymentWhen(message.at, state.zone, now) else formatTime(message.at, state.zone),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = mutedColor,
                )
                if (mine) {
                    // Sent / not sent only: read receipts need a backend.
                    val sent = message.delivery == Delivery.Sent
                    Icon(
                        if (sent) Icons.Outlined.Done else Icons.Outlined.ErrorOutline,
                        contentDescription = stringResource(if (sent) R.string.chat_delivery_sent else R.string.chat_delivery_not_sent),
                        tint = mutedColor,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * A technique video in a bubble: exercise and set context, duration and size, and its upload state. It opens only
 * when the media is READY; an unfinished upload is never presented as playable.
 */
@Composable
private fun VideoAttachment(video: MediaRef, asset: MediaAsset?, mine: Boolean, onAction: (ChatAction) -> Unit) {
    val colors = AppTheme.colors
    val ready = asset?.state == MediaState.READY
    val openLabel = stringResource(R.string.chat_video_open)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.tile)
            .background(if (mine) Color.White.copy(alpha = 0.16f) else colors.chipSurface)
            .then(if (ready) Modifier.clickable(role = Role.Button, onClickLabel = openLabel) { onAction(ChatAction.OpenVideo(video.mediaId)) } else Modifier)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ready) Icons.Outlined.PlayCircle else Icons.Outlined.Videocam,
                contentDescription = null,
                tint = if (mine) Color.White else colors.brand,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    video.exerciseName.ifBlank { stringResource(R.string.chat_video_title) },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    videoContext(video),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mine) Color.White.copy(alpha = 0.85f) else colors.textMuted,
                )
            }
        }
        val statusColor = if (mine) Color.White else colors.textMuted
        val progress = asset?.progress ?: 0f
        when (asset?.state) {
            // Not on this device: the demo fixture video, or media the server has not described yet.
            null -> Text(
                stringResource(if (mine) R.string.chat_video_queued else R.string.chat_video_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
            MediaState.QUEUED -> Text(stringResource(R.string.chat_video_queued), style = MaterialTheme.typography.labelSmall, color = statusColor)
            MediaState.UPLOADING -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(R.string.chat_video_uploading, (progress * 100).roundToInt()), style = MaterialTheme.typography.labelSmall, color = statusColor)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = if (mine) Color.White else colors.brand)
            }
            MediaState.PROCESSING -> Text(stringResource(R.string.chat_video_processing), style = MaterialTheme.typography.labelSmall, color = statusColor)
            MediaState.READY -> Text(stringResource(R.string.chat_video_ready), style = MaterialTheme.typography.labelSmall, color = statusColor)
            MediaState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_video_failed), style = MaterialTheme.typography.labelSmall, color = statusColor, modifier = Modifier.weight(1f))
                if (mine) {
                    TextButton(onClick = { onAction(ChatAction.RetryVideo(video.mediaId)) }) {
                        Text(stringResource(R.string.chat_video_retry), color = statusColor)
                    }
                }
            }
        }
    }
}

/** "Seria 3/4 · 110 kg × 5 · 0:24 · 18 MB". */
@Composable
fun videoContext(video: MediaRef): String = listOfNotNull(
    stringResource(R.string.chat_video_set, video.setNumber, video.setCount),
    video.weightGrams?.let { grams ->
        val kg = formatKg(grams)
        if (video.reps != null) stringResource(R.string.chat_video_weight_reps, kg, video.reps) else stringResource(R.string.chat_video_weight, kg)
    },
    video.durationMs?.let { ms -> "%d:%02d".format(ms / 60_000, (ms / 1000) % 60) },
    video.sizeBytes?.let { bytes -> stringResource(R.string.chat_video_size, ((bytes + 512 * 1024) / (1024 * 1024)).toInt()) },
).joinToString(" · ")

/** Grams to kilograms with the Polish decimal comma: 82500 → "82,5". */
fun formatKg(grams: Int): String = java.text.DecimalFormat("0.###", java.text.DecimalFormatSymbols(java.util.Locale.forLanguageTag("pl-PL"))).format(grams / 1000.0)

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
