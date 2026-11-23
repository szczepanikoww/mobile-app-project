package pl.trainerapp.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import kotlinx.datetime.toLocalDateTime
import pl.trainerapp.core.format.formatMessageWhen
import pl.trainerapp.feature.chat.model.isActive
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Surface
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import com.trainingapp.domain.format.LocaleFormats
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
import pl.trainerapp.core.designsystem.ScreenLoading
import pl.trainer.ui.Layout
import pl.trainerapp.core.format.formatListWhen
import pl.trainerapp.core.format.formatTime
import pl.trainerapp.core.format.formatUpdatedAt
import pl.trainerapp.feature.chat.model.ChatMessage
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.model.Conversation

private val Gutter = Modifier.padding(horizontal = Spacing.screenGutter)

// ---------------------------------------------------------------- Conversation list

/**
 * The conversation list. The trainer sees one row per client; the client (A6) sees their trainer(s), with
 * [emptyBody] explaining where conversations come from in their role.
 */
@Composable
fun TrainerChatListRoute(
    repository: ChatListRepository,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier,
    emptyBody: String? = null,
    emptyContent: (@Composable () -> Unit)? = null,
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

    TrainerChatListScreen(state, viewModel::onAction, onBack, modifier, emptyBody, emptyContent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainerChatListScreen(
    state: ChatListUiState,
    onAction: (ChatListAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    emptyBody: String? = null,
    /** Replaces the plain empty state, e.g. the trainers a client without one can choose from. */
    emptyContent: (@Composable () -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        DetailHeader(title = stringResource(Res.string.chats_title), onBack = onBack)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onAction(ChatListAction.Refresh) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Layout.padding)) {
                val snapshot = state.snapshot
                when {
                    state.status == ChatLoadStatus.Loading -> item { ScreenLoading(stringResource(Res.string.screen_loading)) }
                    state.status == ChatLoadStatus.Error -> item {
                        ErrorState(
                            title = stringResource(Res.string.error_load_chats),
                            retryLabel = stringResource(Res.string.action_retry),
                            onRetry = { onAction(ChatListAction.Refresh) },
                            modifier = Gutter,
                        )
                    }
                    snapshot != null -> {
                        if (snapshot.isOffline) {
                            item { InlineBanner(stringResource(Res.string.offline_banner, formatUpdatedAt(snapshot.lastUpdated)), modifier = Gutter) }
                        }
                        if (snapshot.conversations.isEmpty()) {
                            item {
                                if (emptyContent != null) {
                                    emptyContent()
                                } else {
                                    EmptyState(
                                        title = stringResource(Res.string.chats_empty_title),
                                        body = emptyBody ?: stringResource(Res.string.chats_empty_body),
                                        icon = Icons.Outlined.ChatBubbleOutline,
                                        modifier = Gutter,
                                    )
                                }
                            }
                        } else {
                            val shown = snapshot.conversations.filter { it.name.contains(query.trim(), ignoreCase = true) }
                            item { SearchField(query) { query = it } }
                            // Messenger's top strip: the people who are active right now.
                            val active = shown.filter { isActive(it.peerActiveAt, snapshot.generatedAt) }
                            if (active.isNotEmpty()) {
                                item { ActiveStrip(active) { onAction(ChatListAction.Open(it)) } }
                            }
                            if (shown.isEmpty()) {
                                item {
                                    Text(
                                        stringResource(Res.string.chats_search_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(Layout.gutter),
                                    )
                                }
                            }
                            items(shown, key = { it.relationshipId }) { conversation ->
                                ConversationRow(conversation, snapshot.generatedAt) { onAction(ChatListAction.Open(conversation)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The rounded grey search field under the title; it filters the list by name on the device. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(Res.string.chats_search_placeholder)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        shape = Layout.pill,
        colors = pillFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Layout.gutter, vertical = Layout.small),
    )
}

@Composable
private fun pillFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

/** Round avatar with Messenger's green "active now" dot at the bottom right. */
@Composable
private fun PresenceAvatar(name: String, active: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val activeLabel = stringResource(Res.string.chat_active_now)
    Box(modifier) {
        Avatar(name, size = size)
        if (active) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.3f)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(2.dp)
                    .background(AppTheme.colors.success, CircleShape)
                    .semantics { contentDescription = activeLabel },
            )
        }
    }
}

@Composable
private fun ActiveStrip(active: List<Conversation>, onOpen: (Conversation) -> Unit) {
    Column(Modifier.padding(top = Layout.small)) {
        Text(
            stringResource(Res.string.chats_active_now_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Layout.gutter),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Layout.gutter, vertical = Layout.small),
            horizontalArrangement = Arrangement.spacedBy(Layout.padding),
        ) {
            items(active, key = { it.relationshipId }) { person ->
                Column(
                    modifier = Modifier
                        .width(68.dp)
                        .clip(Layout.card)
                        .clickable(role = Role.Button) { onOpen(person) }
                        .padding(vertical = Layout.tiny),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Layout.tiny),
                ) {
                    PresenceAvatar(person.name, active = true, size = 60.dp)
                    Text(
                        person.name.substringBefore(' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A flat Messenger row: avatar, name, "preview · time", and a dot when there is something unread. */
@Composable
private fun ConversationRow(conversation: Conversation, now: kotlin.time.Instant, onClick: () -> Unit) {
    val unread = conversation.unread > 0
    val unreadLabel = stringResource(Res.string.chats_unread_a11y, conversation.unread)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Layout.gutter, vertical = Layout.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Layout.medium),
    ) {
        PresenceAvatar(conversation.name, isActive(conversation.peerActiveAt, now), size = 56.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                conversation.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val text = if (conversation.lastHasVideo) stringResource(Res.string.chat_video_preview, conversation.lastMessage) else conversation.lastMessage
            val preview = if (conversation.lastFromMe) stringResource(Res.string.chats_you_prefix, text) else text
            Row {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    " · " + formatListWhen(conversation.lastAt, conversation.zone, now),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (unread) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .semantics { contentDescription = unreadLabel },
            )
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
    val now = state.now
    val peerActiveAt = state.peerActiveAt
    val active = now != null && isActive(peerActiveAt, now)
    val status = when {
        peerActiveAt == null || now == null -> null
        active -> stringResource(Res.string.chat_active_now)
        else -> stringResource(Res.string.chat_active_at, formatMessageWhen(peerActiveAt, state.zone, now))
    }
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        DetailHeader(
            title = state.name.ifEmpty { stringResource(Res.string.chats_title) },
            subtitle = status,
            onBack = onBack,
            leading = if (state.name.isNotEmpty()) {
                { PresenceAvatar(state.name, active, size = 40.dp) }
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
                ) { MessageList(state, onAction) }
                Composer(state, onAction)
            }
        }
    }
}

/** Messages further apart than this get a time separator, as in Messenger. */
private val SeparatorGap = kotlin.time.Duration.parse("30m")

@Composable
private fun MessageList(state: ChatUiState, onAction: (ChatAction) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    if (state.messages.isEmpty()) {
        EmptyState(
            title = stringResource(Res.string.chat_empty_title),
            body = stringResource(if (state.viewer == ChatRole.Client) Res.string.chat_empty_body_client else Res.string.chat_empty_body),
            icon = Icons.Outlined.ChatBubbleOutline,
            modifier = Gutter,
        )
        return
    }
    val messages = state.messages
    val now = state.now ?: messages.last().at
    // Tapping a bubble reveals its exact send time, like Messenger.
    var tapped by rememberSaveable { mutableStateOf<String?>(null) }
    val lastMineId = messages.lastOrNull { it.author == state.viewer }?.id
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Layout.medium, vertical = Layout.padding),
    ) {
        itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
            val previous = messages.getOrNull(index - 1)
            val next = messages.getOrNull(index + 1)
            val startsAfterGap = previous == null || message.at - previous.at > SeparatorGap ||
                previous.at.toLocalDateTime(state.zone).date != message.at.toLocalDateTime(state.zone).date
            val endsGroup = next == null || next.author != message.author || next.at - message.at > SeparatorGap
            val startsGroup = previous == null || previous.author != message.author || startsAfterGap
            Column(Modifier.fillMaxWidth().padding(top = if (startsGroup) Layout.small else 2.dp)) {
                if (startsAfterGap || tapped == message.id) {
                    TimeSeparator(formatMessageWhen(message.at, state.zone, now))
                }
                Bubble(
                    message = message,
                    state = state,
                    startsGroup = startsGroup,
                    endsGroup = endsGroup,
                    onTap = { tapped = if (tapped == message.id) null else message.id },
                    onAction = onAction,
                )
                Receipt(message, state, now, isNewestMine = message.id == lastMineId)
            }
        }
    }
}

@Composable
private fun TimeSeparator(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = Layout.small),
    )
}

/**
 * Under my messages, as in Messenger: "Wyświetlono · Dziś, 14:32" below the newest message the other person has read,
 * and "Wysłano" under my newest message when it is not read yet. A message that failed shows "Nie wysłano".
 */
@Composable
private fun Receipt(message: ChatMessage, state: ChatUiState, now: kotlin.time.Instant, isNewestMine: Boolean) {
    if (message.author != state.viewer) return
    val failed = message.delivery != Delivery.Sent
    val seen = !failed && message.id == state.seenMessageId
    val seenAt = state.seenAt
    val text = when {
        failed -> stringResource(Res.string.chat_delivery_not_sent)
        seen && seenAt != null -> stringResource(Res.string.chat_seen_at, formatMessageWhen(seenAt, state.zone, now))
        seen -> stringResource(Res.string.chat_seen_at, formatTime(message.at, state.zone))
        isNewestMine -> stringResource(Res.string.chat_sent_status)
        else -> return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, end = Layout.tiny),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (seen) {
            Avatar(state.name, size = 14.dp)
            Spacer(Modifier.width(Layout.tiny))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Bubble(
    message: ChatMessage,
    state: ChatUiState,
    startsGroup: Boolean,
    endsGroup: Boolean,
    onTap: () -> Unit,
    onAction: (ChatAction) -> Unit,
) {
    val mine = message.author == state.viewer
    val big = 20.dp
    val small = 6.dp
    // Corners next to a neighbouring bubble of the same sender are tight, so a run reads as one block.
    val shape = if (mine) {
        RoundedCornerShape(topStart = big, bottomStart = big, topEnd = if (startsGroup) big else small, bottomEnd = if (endsGroup) big else small)
    } else {
        RoundedCornerShape(topEnd = big, bottomEnd = big, topStart = if (startsGroup) big else small, bottomStart = if (endsGroup) big else small)
    }
    val contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val mutedColor = if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!mine) {
            // The other person's avatar sits beside the last bubble of their run; the rest keep the same indent.
            Box(Modifier.width(28.dp + Layout.small)) {
                if (endsGroup && state.name.isNotEmpty()) Avatar(state.name, size = 28.dp)
            }
        }
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(shape)
                .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                .clickable(onClick = onTap)
                .padding(horizontal = Layout.padding, vertical = Layout.medium),
            verticalArrangement = Arrangement.spacedBy(Layout.tiny),
        ) {
            if (message.quotedMediaId != null) {
                Text(
                    stringResource(Res.string.chat_reply_to_video),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = mutedColor,
                )
            }
            message.attachment?.let { video ->
                VideoAttachment(video, state.media[video.mediaId], mine, onAction)
            }
            if (message.text.isNotBlank()) {
                Text(message.text, style = MaterialTheme.typography.bodyLarge, color = contentColor)
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
    val ready = asset?.state == MediaState.READY
    val openLabel = stringResource(Res.string.chat_video_open)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Layout.tile)
            .background(if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f) else AppTheme.colors.chipSurface)
            .then(if (ready) Modifier.clickable(role = Role.Button, onClickLabel = openLabel) { onAction(ChatAction.OpenVideo(video.mediaId)) } else Modifier)
            .padding(Layout.medium),
        verticalArrangement = Arrangement.spacedBy(Layout.tiny),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.small), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ready) Icons.Outlined.PlayCircle else Icons.Outlined.Videocam,
                contentDescription = null,
                tint = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    video.exerciseName.ifBlank { stringResource(Res.string.chat_video_title) },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    videoContext(video),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val statusColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        val progress = asset?.progress ?: 0f
        when (asset?.state) {
            // Not on this device: the demo fixture video, or media the server has not described yet.
            null -> Text(
                stringResource(if (mine) Res.string.chat_video_queued else Res.string.chat_video_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
            MediaState.QUEUED -> Text(stringResource(Res.string.chat_video_queued), style = MaterialTheme.typography.labelSmall, color = statusColor)
            MediaState.UPLOADING -> Column(verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                Text(stringResource(Res.string.chat_video_uploading, (progress * 100).roundToInt()), style = MaterialTheme.typography.labelSmall, color = statusColor)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
            }
            MediaState.PROCESSING -> Text(stringResource(Res.string.chat_video_processing), style = MaterialTheme.typography.labelSmall, color = statusColor)
            MediaState.READY -> Text(stringResource(Res.string.chat_video_ready), style = MaterialTheme.typography.labelSmall, color = statusColor)
            MediaState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.chat_video_failed), style = MaterialTheme.typography.labelSmall, color = statusColor, modifier = Modifier.weight(1f))
                if (mine) {
                    TextButton(onClick = { onAction(ChatAction.RetryVideo(video.mediaId)) }) {
                        Text(stringResource(Res.string.chat_video_retry), color = statusColor)
                    }
                }
            }
        }
    }
}

/** "Seria 3/4 · 110 kg × 5 · 0:24 · 18 MB". */
@Composable
fun videoContext(video: MediaRef): String = listOfNotNull(
    stringResource(Res.string.chat_video_set, video.setNumber, video.setCount),
    video.weightGrams?.let { grams ->
        val kg = formatKg(grams)
        if (video.reps != null) stringResource(Res.string.chat_video_weight_reps, kg, video.reps) else stringResource(Res.string.chat_video_weight, kg)
    },
    video.durationMs?.let { ms -> "${ms / 60_000}:${((ms / 1000) % 60).toString().padStart(2, '0')}" },
    video.sizeBytes?.let { bytes -> stringResource(Res.string.chat_video_size, ((bytes + 512 * 1024) / (1024 * 1024)).toInt()) },
).joinToString(" · ")

/** Grams to kilograms with the Polish decimal comma: 82500 → "82,5". */
fun formatKg(grams: Int): String = LocaleFormats.formatDecimal(grams / 1000.0, "pl-PL", maxFractionDigits = 3)

@Composable
private fun Composer(state: ChatUiState, onAction: (ChatAction) -> Unit) {
    val canSend = state.draft.isNotBlank() && !state.isSending
    // The app draws edge to edge, so the composer moves above the keyboard itself.
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Layout.medium, vertical = Layout.small),
            verticalArrangement = Arrangement.spacedBy(Layout.small),
        ) {
            if (state.sendFailed) {
                Text(
                    stringResource(Res.string.chat_send_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Layout.small),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
                TextField(
                    value = state.draft,
                    onValueChange = { onAction(ChatAction.DraftChanged(it)) },
                    placeholder = { Text(stringResource(Res.string.chat_placeholder)) },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = pillFieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat-input")
                        // Enter on a hardware keyboard sends; Shift+Enter inserts a new line.
                        .onPreviewKeyEvent { event ->
                            val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (enter && !event.isShiftPressed) {
                                if (event.type == KeyEventType.KeyDown && canSend) onAction(ChatAction.Send)
                                true
                            } else {
                                false
                            }
                        },
                )
                // Messenger's send: a plain brand-colored icon that only lights up when there is something to send.
                IconButton(onClick = { onAction(ChatAction.Send) }, enabled = canSend, modifier = Modifier.size(Layout.target).testTag("chat-send")) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = stringResource(Res.string.chat_send),
                        tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}
