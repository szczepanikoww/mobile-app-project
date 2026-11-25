package com.trainingapp.feature.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import com.trainingapp.designsystem.component.ExercisePhotoBox
import com.trainingapp.designsystem.component.ExercisePhoto
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CardMembership
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.CardRow
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.IconBadge
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.MetaLine
import com.trainingapp.designsystem.component.PersonPortrait
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.component.SecondaryButton
import com.trainingapp.designsystem.component.TonalButton
import com.trainingapp.designsystem.component.brandHeroBrush
import com.trainingapp.designsystem.component.heroDecoration
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.client.presentation.ScheduleUiState
import com.trainingapp.feature.client.presentation.TodayWorkoutUiState
import com.trainingapp.feature.client.presentation.WalletUiState
import com.trainingapp.feature.client.resources.*
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import pl.trainer.domain.ChargeKind
import pl.trainer.domain.ClientSession
import pl.trainer.domain.ClientSessionState
import pl.trainer.ui.DateFormats

data class ClientHomeActions(
    val onOpenSession: (String) -> Unit,
    val onReschedule: (ClientSession) -> Unit,
    val onRequestSession: () -> Unit,
    val onStartWorkout: () -> Unit,
    /** Null when the chat is not available on this platform. */
    val onChat: (() -> Unit)?,
    val onFindTrainer: () -> Unit,
    val onOpenWallet: () -> Unit,
    val onRetry: () -> Unit,
    /** Opens the calendar tab; the shortcut grid links to destinations the client already has. */
    val onOpenCalendar: () -> Unit = {},
)

/**
 * B1 "Home": what matters today. The next session as a hero card, today's workout and what needs the client; the
 * package card links to the wallet. No discovery, statistics or tips (plan §3, B1). The imagery is drawn (portraits,
 * icon badges, the brand fill), not stock photos.
 */
@Composable
fun ClientHomeContent(
    firstName: String,
    schedule: ScheduleUiState,
    wallet: WalletUiState,
    workout: TodayWorkoutUiState,
    actions: ClientHomeActions,
) {
    when {
        schedule.loading -> LoadingState()
        schedule.loadFailure != null && schedule.schedule == null -> ErrorState(failureMessage(schedule.loadFailure), onRetry = actions.onRetry)
        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
        ) {
            Greeting(firstName, schedule.today)
            if (schedule.offline) InlineMessage(stringResource(Res.string.offline_cached), tone = MessageTone.Offline)
            if (!schedule.hasTrainer) {
                EmptyState(
                    title = stringResource(Res.string.home_no_trainer_title),
                    message = stringResource(Res.string.home_no_trainer_body),
                    actionLabel = stringResource(Res.string.find_trainer),
                    onAction = actions.onFindTrainer,
                    icon = Icons.Outlined.Search,
                )
                return@Column
            }
            // Club layout: the next session as the big brand card, then the shortcut grid, then what needs attention.
            NextSessionSection(schedule.nextSession, actions)
            ShortcutGrid(actions)
            AttentionSection(schedule, wallet, actions)
            TodayWorkoutSection(workout, actions.onStartWorkout)
            wallet.wallet?.packages?.firstOrNull()?.let { pack ->
                PackageCard(pack.name, pack.used, pack.total, actions.onOpenWallet)
            }
        }
    }
}

@Composable
private fun Greeting(firstName: String, today: LocalDate?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        today?.let {
            Text(dayTitle(it), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
        }
        Text(
            stringResource(Res.string.home_greeting, firstName),
            style = AppTheme.type.screenTitle,
            color = AppTheme.colors.text,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** A white, outlined card with the large radius, the frame of every home section. */
@Composable
private fun HomeCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(AppShapes.featureCard)
            .background(AppTheme.colors.surface)
            .border(BorderStroke(1.dp, AppTheme.colors.outline), AppShapes.featureCard)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        content = content,
    )
}

@Composable
private fun AttentionSection(schedule: ScheduleUiState, wallet: WalletUiState, actions: ClientHomeActions) {
    val sessions = schedule.attention
    val unpaid = wallet.outstanding.filter { it.kind != ChargeKind.PackageCredit }
    if (sessions.isEmpty() && unpaid.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SectionHeader(stringResource(Res.string.home_attention))
        sessions.forEach { item ->
            val title = stringResource(
                when (item.state) {
                    ClientSessionState.ProposalFromTrainer -> Res.string.attention_proposal
                    ClientSessionState.Expired -> Res.string.attention_expired
                    else -> Res.string.attention_rejected
                },
            )
            CardRow(
                title = title,
                supporting = shortDateTime(item.session.proposal?.start ?: item.session.start, item.zone) + " · " + item.trainer.name,
                onClick = { actions.onOpenSession(item.id) },
                leading = { PersonPortrait(item.trainer.initials, size = 44.dp, photoOf = item.trainer.id) },
                badges = { SessionStateChip(item.state) },
            )
        }
        unpaid.forEach { charge ->
            CardRow(
                title = stringResource(Res.string.attention_unpaid, money(charge.amount)),
                supporting = stringResource(chargeReasonText(charge.reason)),
                onClick = actions.onOpenWallet,
                leading = {
                    IconBadge(Icons.Outlined.Payments, container = AppTheme.colors.warningContainer, content = AppTheme.colors.onWarningContainer)
                },
            )
        }
    }
}

@Composable
private fun NextSessionSection(next: ClientSession?, actions: ClientHomeActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SectionHeader(stringResource(Res.string.home_next_session))
        if (next == null) {
            HomeCard {
                ExercisePhotoBox(ExercisePhoto.OfLibrary(NoSessionPhoto), Modifier.fillMaxWidth().height(120.dp), shape = RectangleShape)
                Row(
                    Modifier.padding(Spacing.cardPadding),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(Res.string.home_no_upcoming), style = AppTheme.type.itemTitle, color = AppTheme.colors.text)
                        Text(stringResource(Res.string.home_no_upcoming_hint), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                    }
                }
                SecondaryButton(
                    stringResource(Res.string.request_session),
                    onClick = actions.onRequestSession,
                    icon = Icons.Outlined.CalendarToday,
                    modifier = Modifier.fillMaxWidth().padding(start = Spacing.cardPadding, end = Spacing.cardPadding, bottom = Spacing.cardPadding),
                )
            }
            return@Column
        }
        val date = next.session.start.dateIn(next.zone)
        HomeCard {
            // Hero: a solid brand block with the date tile, the day and the hours in white.
            Box(Modifier.fillMaxWidth().background(AppTheme.colors.brandStrong)) {
                Column(Modifier.fillMaxWidth().padding(Spacing.cardPadding).padding(top = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    SessionStateChip(next.state)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                        DateTile(date)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(dayTitle(date), style = AppTheme.type.itemTitle, color = AppTheme.colors.onBrandStrong)
                            Text(hours(next.session.start, next.session.end, next.zone), style = AppTheme.type.body, color = AppTheme.colors.onBrandStrong)
                        }
                    }
                }
            }
            Column(Modifier.padding(Spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                    PersonPortrait(next.trainer.initials, size = 44.dp, photoOf = next.trainer.id)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(Res.string.home_next_with, next.trainer.name), style = AppTheme.type.bodyStrong, color = AppTheme.colors.text)
                        MetaLine(Icons.Outlined.LocationOn, next.session.location)
                    }
                }
                next.session.planTitle?.let { MetaLine(Icons.Outlined.FitnessCenter, stringResource(Res.string.plan_line, it)) }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    if (next.canReschedule) {
                        TonalButton(stringResource(Res.string.reschedule), onClick = { actions.onReschedule(next) }, icon = Icons.Outlined.EditCalendar)
                    }
                    actions.onChat?.let { chat ->
                        SecondaryButton(stringResource(Res.string.write_message), onClick = chat, icon = Icons.AutoMirrored.Outlined.Chat)
                    }
                    AppTextButton(stringResource(Res.string.details), onClick = { actions.onOpenSession(next.id) })
                }
            }
        }
    }
}

private data class Shortcut(val icon: ImageVector, val label: StringResource, val onClick: () -> Unit)

/** A two-column grid of shortcuts to places the client already has: workout, sessions, chat and package. */
@Composable
private fun ShortcutGrid(actions: ClientHomeActions) {
    val shortcuts = buildList {
        add(Shortcut(Icons.Outlined.FitnessCenter, Res.string.shortcut_workout, actions.onStartWorkout))
        add(Shortcut(Icons.Outlined.CalendarToday, Res.string.shortcut_sessions, actions.onOpenCalendar))
        actions.onChat?.let { add(Shortcut(Icons.AutoMirrored.Outlined.Chat, Res.string.shortcut_chat, it)) }
        add(Shortcut(Icons.Outlined.CardMembership, Res.string.shortcut_package, actions.onOpenWallet))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        shortcuts.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                row.forEach { item -> ShortcutTile(item, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ShortcutTile(item: Shortcut, modifier: Modifier = Modifier) {
    HomeCard(modifier, onClick = item.onClick) {
        Column(
            Modifier.padding(Spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            IconBadge(item.icon)
            Text(stringResource(item.label), style = AppTheme.type.bodyStrong, color = AppTheme.colors.text)
        }
    }
}

/** "OCT / 29" on a translucent white tile. Decorative: the full day title next to it is what is read out. */
@Composable
private fun DateTile(date: LocalDate) {
    val white = AppTheme.colors.onBrandStrong
    Column(
        Modifier.size(64.dp).background(white.copy(alpha = 0.16f), AppShapes.card).padding(Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(DateFormats.date(date, language(), "MMM").uppercase().trimEnd('.'), style = AppTheme.type.label, color = white)
        Text(date.day.toString(), style = AppTheme.type.sectionTitle, color = white)
    }
}

@Composable
private fun TodayWorkoutSection(workout: TodayWorkoutUiState, onStart: () -> Unit) {
    if (workout.loading) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SectionHeader(stringResource(Res.string.home_today_workout))
        HomeCard {
            // The first exercise's photo as the banner, with the day's name on it.
            val first = workout.exerciseIds.firstOrNull()?.let { ExercisePhoto.OfExercise(it) } ?: ExercisePhoto.OfLibrary(FreeWorkoutPhoto)
            ExercisePhotoBox(first, Modifier.fillMaxWidth().height(150.dp), shape = RectangleShape, scrim = true) {
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(Spacing.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    val white = AppTheme.colors.onBrandStrong
                    when {
                        workout.inProgress -> Text(workout.inProgressName ?: stringResource(Res.string.workout_in_progress), style = AppTheme.type.sectionTitle, color = white)
                        workout.dayName != null -> {
                            Text(workout.dayName, style = AppTheme.type.sectionTitle, color = white)
                            Text(stringResource(Res.string.exercise_count, workout.exerciseCount), style = AppTheme.type.compactBody, color = white)
                        }
                        else -> Text(stringResource(Res.string.home_no_plan), style = AppTheme.type.itemTitle, color = white)
                    }
                }
            }
            Column(Modifier.padding(Spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                val rest = workout.exerciseIds.drop(1)
                if (rest.isNotEmpty()) ExerciseStrip(rest)
                if (workout.dayName == null && !workout.inProgress) {
                    Text(stringResource(Res.string.home_free_workout_hint), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                }
                when {
                    workout.inProgress -> PrimaryButton(stringResource(Res.string.resume_workout), onClick = onStart, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.FitnessCenter)
                    workout.dayName != null -> PrimaryButton(stringResource(Res.string.start_workout), onClick = onStart, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.FitnessCenter)
                    else -> SecondaryButton(stringResource(Res.string.free_workout), onClick = onStart, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.FitnessCenter)
                }
            }
        }
    }
}

/** The day's next exercises as small photos; the rest as "+N". */
@Composable
private fun ExerciseStrip(exerciseIds: List<String>) {
    val shown = exerciseIds.take(StripSize)
    val more = exerciseIds.size - shown.size
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        shown.forEach { id ->
            ExercisePhotoBox(ExercisePhoto.OfExercise(id), Modifier.weight(1f).aspectRatio(1f))
        }
        if (more > 0) {
            Box(
                Modifier.weight(1f).aspectRatio(1f).clip(AppShapes.thumbnail).background(AppTheme.colors.softAccent),
                contentAlignment = Alignment.Center,
            ) {
                Text("+$more", style = AppTheme.type.itemTitle, color = AppTheme.colors.onSoftAccent)
            }
        } else {
            repeat(StripSize + 1 - shown.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private const val StripSize = 4

/* Library photos (free-exercise-db, public domain) for cards without an exercise of their own. */
private const val NoSessionPhoto = "Dumbbell_Bench_Press"
private const val FreeWorkoutPhoto = "Dumbbell_Lunges"

/** The active package with a progress bar of used sessions; opens the wallet. */
@Composable
private fun PackageCard(name: String, used: Int, total: Int, onOpenWallet: () -> Unit) {
    HomeCard(onClick = onOpenWallet) {
        Row(
            Modifier.padding(Spacing.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(Icons.Outlined.CardMembership)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(Res.string.home_package, used, total), style = AppTheme.type.bodyStrong, color = AppTheme.colors.text)
                Text(name, style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (used.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().clip(AppShapes.pill),
                        color = AppTheme.colors.brandStrong,
                        trackColor = AppTheme.colors.softAccent,
                        drawStopIndicator = {},
                    )
                }
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = AppTheme.colors.textSecondary)
        }
    }
}
