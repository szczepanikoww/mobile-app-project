package pl.trainerapp.feature.trainerhome

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.trainerapp.R
import pl.trainerapp.core.designsystem.AppColors
import pl.trainerapp.core.designsystem.AppShapes
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.core.designsystem.Corner
import pl.trainerapp.core.designsystem.CornerArc
import pl.trainerapp.core.designsystem.EmptyState
import pl.trainerapp.core.designsystem.ErrorState
import pl.trainerapp.core.designsystem.GlassPanel
import pl.trainerapp.core.designsystem.InlineBanner
import pl.trainerapp.core.designsystem.SectionHeader
import pl.trainerapp.core.designsystem.SkeletonBlock
import pl.trainerapp.core.designsystem.Spacing
import pl.trainerapp.core.designsystem.frosted
import pl.trainerapp.core.format.formatSessionRange
import pl.trainerapp.core.format.formatStartsIn
import pl.trainerapp.core.format.formatTime
import pl.trainerapp.core.format.formatUpdatedAt
import pl.trainerapp.feature.trainerhome.model.AttentionItem
import pl.trainerapp.feature.trainerhome.model.SessionStatus
import pl.trainerapp.feature.trainerhome.model.SessionSummary
import pl.trainerapp.feature.trainerhome.model.TrainerHomeSummary
import pl.trainerapp.feature.trainerhome.model.WeekStats
import java.time.Instant

private val SessionCardWidth = 290.dp
private val AttentionCardWidth = 190.dp
private val AttentionCardHeight = 250.dp
private val CarouselGap = Spacing.md
private val SectionInnerGap = Spacing.sm

private val Gutter = Modifier.padding(horizontal = Spacing.screenGutter)
private val CarouselPadding = PaddingValues(horizontal = Spacing.screenGutter)

// Demo imagery: the design's stock photos, assigned by position until clients have real avatars.
private val SessionPhotos = intArrayOf(
    R.drawable.photo_next_session,
    R.drawable.photo_today_1,
    R.drawable.photo_today_2,
    R.drawable.photo_today_3,
)

/** Decorative corner arc per card position: corner and diameter. */
private val SessionArcs = listOf(Corner.TopEnd to 80.dp, Corner.BottomStart to 64.dp, Corner.TopEnd to 64.dp, Corner.TopStart to 64.dp)

/** "Magdalena Kowalczyk-Wiśniewska" → "Magdalena K." */
private fun shortName(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotBlank() }
    return if (parts.size < 2) name else "${parts.first()} ${parts.last().first().uppercaseChar()}."
}

/** Navigation- and DI-aware entry point. Everything below it only renders state and emits actions. */
@Composable
fun TrainerHomeRoute(
    repository: TrainerHomeRepository,
    onNavigate: (TrainerHomeDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel { TrainerHomeViewModel(repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TrainerHomeEvent.Navigate -> onNavigate(event.destination)
            }
        }
    }

    TrainerHomeScreen(state = state, onAction = viewModel::onAction, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainerHomeScreen(
    state: TrainerHomeUiState,
    onAction: (TrainerHomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { onAction(TrainerHomeAction.Refresh) },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = Spacing.xs, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
        ) {
            when {
                state.status == LoadStatus.Loading -> item { LoadingContent() }
                state.status == LoadStatus.Error -> item {
                    ErrorState(
                        title = stringResource(R.string.error_load_home),
                        retryLabel = stringResource(R.string.action_retry),
                        onRetry = { onAction(TrainerHomeAction.Refresh) },
                        modifier = Gutter,
                    )
                }
                state.summary != null -> content(state, state.summary, onAction)
            }
        }
    }
}

private fun LazyListScope.content(
    state: TrainerHomeUiState,
    summary: TrainerHomeSummary,
    onAction: (TrainerHomeAction) -> Unit,
) {
    val updated = state.lastUpdated

    item { HomeHeader(summary, onAction) }

    if (state.isOffline && updated != null) {
        item { InlineBanner(stringResource(R.string.offline_banner, formatUpdatedAt(updated)), modifier = Gutter) }
    }

    if (summary.isNewTrainer) {
        item {
            EmptyState(
                title = stringResource(R.string.empty_new_title),
                body = stringResource(R.string.empty_new_body),
                actionLabel = stringResource(R.string.empty_new_action),
                onAction = { onAction(TrainerHomeAction.EmptyStateAction) },
                modifier = Gutter,
            )
        }
        return
    }

    val sessions = summary.carouselSessions
    if (sessions.isNotEmpty()) {
        item { SessionsSection(sessions, summary.generatedAt, state.isOffline, onAction) }
    } else {
        item { EmptyDay(summary, onAction) }
    }

    if (summary.attention.isNotEmpty()) {
        item { AttentionSection(summary.attention, onAction) }
    }

    summary.week?.let { week ->
        item { WeekSection(week, onAction) }
    }
}

@Composable
private fun LoadingContent() {
    Column(modifier = Gutter, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SkeletonBlock(height = 28.dp, modifier = Modifier.fillMaxWidth(0.5f))
        SkeletonBlock(height = 180.dp)
        SkeletonBlock(height = 64.dp)
        SkeletonBlock(height = 64.dp)
    }
}

@Composable
private fun EmptyDay(summary: TrainerHomeSummary, onAction: (TrainerHomeAction) -> Unit) {
    val upcoming = summary.upcomingAfterToday
    EmptyState(
        title = stringResource(R.string.empty_day_title),
        body = if (upcoming != null) {
            stringResource(
                R.string.empty_day_next,
                formatSessionRange(upcoming.start, upcoming.end, upcoming.zone, summary.generatedAt),
            )
        } else {
            stringResource(R.string.empty_day_body)
        },
        actionLabel = stringResource(R.string.home_see_calendar),
        onAction = { onAction(TrainerHomeAction.OpenCalendar) },
        modifier = Gutter,
    )
}

@Composable
private fun HomeHeader(summary: TrainerHomeSummary, onAction: (TrainerHomeAction) -> Unit) {
    val colors = AppTheme.colors
    val profileDescription = stringResource(R.string.action_profile)
    Row(
        modifier = Modifier.fillMaxWidth().then(Gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .shadow(2.dp, CircleShape)
                    .background(colors.softBrand, CircleShape)
                    .border(1.dp, colors.brandBorder, CircleShape)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onAction(TrainerHomeAction.OpenProfile) }
                    .semantics { contentDescription = profileDescription },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = colors.brand, modifier = Modifier.size(28.dp))
            }
            Column {
                Text(
                    stringResource(R.string.home_greeting_hey),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = colors.textMuted,
                )
                summary.trainerName?.let {
                    Text(it, style = MaterialTheme.typography.titleLarge.copy(lineHeight = 20.sp), color = colors.brand)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val unread = summary.unreadChats
            HeaderButton(
                icon = Icons.Outlined.ChatBubble,
                description = if (unread > 0) {
                    stringResource(R.string.cd_unread_chats, unread)
                } else {
                    stringResource(R.string.action_chat)
                },
                onClick = { onAction(TrainerHomeAction.OpenChats) },
            ) {
                if (unread > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = Spacing.xs, end = Spacing.xs)
                            .size(20.dp)
                            .shadow(2.dp, CircleShape)
                            .background(colors.brand, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(unread.toString(), style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
            HeaderButton(
                icon = Icons.Outlined.Notifications,
                description = stringResource(R.string.action_notifications),
                onClick = { onAction(TrainerHomeAction.OpenNotifications) },
            ) {
                if (summary.hasNotifications) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = Spacing.sm, end = 10.dp)
                            .size(10.dp)
                            .background(colors.danger, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    badge: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .frosted(CircleShape, alpha = 0.75f)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = AppTheme.colors.textStrong, modifier = Modifier.size(22.dp))
        badge()
    }
}

@Composable
private fun SessionsSection(
    sessions: List<SessionSummary>,
    now: Instant,
    offline: Boolean,
    onAction: (TrainerHomeAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SectionInnerGap)) {
        SectionHeader(
            title = stringResource(R.string.home_today_sessions),
            badge = sessions.size.toString(),
            modifier = Gutter,
            action = {
                Text(
                    stringResource(R.string.home_swipe),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = AppTheme.colors.textMuted,
                )
            },
        )
        LazyRow(contentPadding = CarouselPadding, horizontalArrangement = Arrangement.spacedBy(CarouselGap)) {
            itemsIndexed(sessions, key = { _, session -> session.id }) { index, session ->
                SessionCard(session, index, now, offline, onAction)
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionSummary,
    index: Int,
    now: Instant,
    offline: Boolean,
    onAction: (TrainerHomeAction) -> Unit,
) {
    val colors = AppTheme.colors
    val featured = index == 0
    val range = formatSessionRange(session.start, session.end, session.zone, now)
    val requested = session.status == SessionStatus.Requested
    val chipText = when {
        requested -> stringResource(R.string.status_pending_chip)
        else -> formatStartsIn(session.start, now) ?: formatTime(session.start, session.zone)
    }
    val place = when {
        session.planTitle == null -> session.location
        featured -> stringResource(R.string.session_place_plan, session.location, session.planTitle)
        else -> stringResource(R.string.session_plan_place, session.planTitle, session.location)
    }
    val (arcCorner, arcSize) = SessionArcs[index % SessionArcs.size]
    val canCheckIn = session.checkInOpen && !offline

    // Translucent frame around a white callout, as in the design's wide framed card.
    Column(
        modifier = Modifier
            .width(SessionCardWidth)
            .shadow(12.dp, AppShapes.frame)
            .background(Color.White.copy(alpha = 0.72f), AppShapes.frame)
            .border(1.dp, colors.border, AppShapes.frame)
            .padding(6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppShapes.media)
                .background(Color.White)
                .clickable { onAction(TrainerHomeAction.OpenSession(session)) },
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(144.dp).background(colors.chipSurface)) {
                Image(
                    painterResource(SessionPhotos[index % SessionPhotos.size]),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                CornerArc(arcCorner, arcSize)
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .frosted(AppShapes.pill, alpha = 0.95f)
                        .padding(horizontal = 10.dp, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val chipColor = if (requested) colors.warning else colors.brand
                    Icon(
                        if (requested) Icons.Outlined.Pending else Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = chipColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        chipText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (requested) colors.warning else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        range,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    SessionStatusBadge(session.status, colors)
                }
                Column {
                    Text(
                        if (featured) session.client.name else shortName(session.client.name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        place,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (requested) {
                        CardButton(
                            text = stringResource(R.string.action_confirm),
                            icon = Icons.Outlined.Check,
                            primary = true,
                            onClick = { onAction(TrainerHomeAction.OpenSession(session)) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        CardButton(
                            text = stringResource(R.string.action_check_in),
                            icon = Icons.Outlined.HowToReg,
                            primary = true,
                            enabled = canCheckIn,
                            onClick = { onAction(TrainerHomeAction.CheckIn(session)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    CardButton(
                        text = stringResource(R.string.action_message),
                        icon = Icons.Outlined.ChatBubbleOutline,
                        primary = false,
                        onClick = { onAction(TrainerHomeAction.Message(session)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (offline && session.checkInOpen && !requested) {
                    Text(
                        stringResource(R.string.check_in_needs_connection),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardButton(
    text: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val content = if (primary) Color.White else colors.textStrong
    val base = modifier
        .height(40.dp)
        .alpha(if (enabled) 1f else 0.4f)
        .then(if (primary) Modifier.shadow(4.dp, AppShapes.pill, ambientColor = colors.brand, spotColor = colors.brand) else Modifier)
        .background(if (primary) colors.brand else colors.chipSurface, AppShapes.pill)
        .then(if (primary) Modifier else Modifier.border(1.dp, colors.border, AppShapes.pill))
        .clip(AppShapes.pill)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    Row(
        modifier = base,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (primary) content else colors.textMuted, modifier = Modifier.size(18.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold),
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Spacing.xs),
        )
    }
}

/** Text-only status pill. */
@Composable
private fun SessionStatusBadge(status: SessionStatus, colors: AppColors) {
    val (label, container, content, border) = when (status) {
        SessionStatus.Confirmed, SessionStatus.Completed ->
            BadgeStyle(stringResource(if (status == SessionStatus.Confirmed) R.string.status_confirmed else R.string.status_completed), colors.successContainer, colors.success, colors.successBorder)
        SessionStatus.Requested -> BadgeStyle(stringResource(R.string.status_requested), colors.softBrand, colors.warning, colors.warningBorder)
        SessionStatus.Cancelled -> BadgeStyle(stringResource(R.string.status_cancelled), colors.chipSurface, colors.textMuted, colors.border)
    }
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .clip(AppShapes.pill)
            .background(container)
            .border(1.dp, border, AppShapes.pill)
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
    )
}

private data class BadgeStyle(val label: String, val container: Color, val content: Color, val border: Color)

/** Attention card shell: 190×250, clipped to the media radius, with the photo filling it. */
@Composable
private fun PhotoCard(
    @DrawableRes photo: Int,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .size(AttentionCardWidth, AttentionCardHeight)
            .shadow(6.dp, AppShapes.media)
            .clip(AppShapes.media)
            .border(1.dp, AppTheme.colors.border, AppShapes.media)
            .clickable(onClick = onClick),
    ) {
        Image(painterResource(photo), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        content()
    }
}

@Composable
private fun AttentionSection(items: List<AttentionItem>, onAction: (TrainerHomeAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SectionInnerGap)) {
        SectionHeader(stringResource(R.string.home_needs_attention), modifier = Gutter)
        LazyRow(contentPadding = CarouselPadding, horizontalArrangement = Arrangement.spacedBy(CarouselGap)) {
            itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                AttentionCard(item) { onAction(TrainerHomeAction.OpenAttention(item)) }
            }
        }
    }
}

@Composable
private fun AttentionCard(item: AttentionItem, onClick: () -> Unit) {
    val colors = AppTheme.colors
    when (item) {
        is AttentionItem.TechniqueVideo -> PhotoCard(R.drawable.photo_attention_video, onClick) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier.size(48.dp).frosted(CircleShape, alpha = 0.85f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = colors.brand, modifier = Modifier.size(28.dp))
                }
            }
            CornerArc(Corner.TopEnd, 64.dp)
            GlassPanel {
                PanelLabel(stringResource(R.string.attention_video_label).uppercase(), colors.brand)
                PanelHeading(item.exercise)
                PanelSubtitle(shortName(item.client.name))
            }
        }
        is AttentionItem.UnreadMessages -> PhotoCard(R.drawable.photo_attention_message, onClick) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .frosted(AppShapes.pill, alpha = 0.82f)
                    .padding(horizontal = 10.dp, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = colors.brand, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.attention_new), style = MaterialTheme.typography.labelSmall, color = colors.warning)
            }
            GlassPanel {
                PanelLabel(pluralStringResource(R.plurals.attention_unread_short, item.count, item.count), colors.brand)
                PanelHeading(shortName(item.client.name))
                item.topic?.let { PanelSubtitle(it) }
            }
        }
        is AttentionItem.OverduePayment -> PhotoCard(R.drawable.photo_attention_payment, onClick) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.md)
                    .size(20.dp)
                    .background(colors.danger.copy(alpha = 0.3f), CircleShape)
                    .padding(4.dp)
                    .background(colors.danger, CircleShape)
                    .clearAndSetSemantics { },
            )
            GlassPanel {
                PanelLabel(stringResource(R.string.attention_payment_label), colors.dangerText)
                PanelHeading(stringResource(R.string.attention_overdue))
                PanelSubtitle(shortName(item.client.name))
            }
        }
    }
}

@Composable
private fun PanelLabel(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun PanelHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PanelSubtitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = AppTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun WeekSection(week: WeekStats, onAction: (TrainerHomeAction) -> Unit) {
    val colors = AppTheme.colors
    Column(modifier = Gutter, verticalArrangement = Arrangement.spacedBy(SectionInnerGap)) {
        SectionHeader(
            title = stringResource(R.string.home_this_week),
            action = {
                Text(
                    stringResource(R.string.home_details),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.brand,
                    modifier = Modifier.clickable { onAction(TrainerHomeAction.OpenCalendar) },
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StatTile(
                Icons.Outlined.FitnessCenter,
                "${week.sessionsDone}/${week.sessionsPlanned}",
                stringResource(R.string.week_sessions),
                Modifier.weight(1f),
            )
            StatTile(Icons.Outlined.PersonAdd, week.newClients.toString(), stringResource(R.string.week_new), Modifier.weight(1f))
            StatTile(
                Icons.Outlined.EventBusy,
                week.cancellations.toString(),
                stringResource(R.string.week_cancelled),
                Modifier.weight(1f),
                muted = true,
            )
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .frosted(AppShapes.tile, alpha = 0.9f)
            .padding(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(if (muted) colors.chipSurface else colors.softBrand, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (muted) colors.textMuted else colors.brand, modifier = Modifier.size(18.dp))
        }
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(lineHeight = 20.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = colors.textMuted,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}
