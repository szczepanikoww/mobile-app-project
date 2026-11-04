package com.trainingapp.feature.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CardMembership
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.component.ActionRow
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.CardRow
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.MetaLine
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.component.SecondaryButton
import com.trainingapp.designsystem.component.TonalButton
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.client.presentation.ScheduleUiState
import com.trainingapp.feature.client.presentation.TodayWorkoutUiState
import com.trainingapp.feature.client.presentation.WalletUiState
import com.trainingapp.feature.client.resources.*
import org.jetbrains.compose.resources.stringResource
import pl.trainer.domain.ChargeKind
import pl.trainer.domain.ClientSession
import pl.trainer.domain.ClientSessionState

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
)

/**
 * B1 "Start": what matters today. The next session, today's workout and what needs the client; the package line
 * links to the wallet. No discovery, statistics or tips (plan §3, B1).
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
            Text(
                stringResource(Res.string.home_greeting, firstName),
                style = AppTheme.type.sectionTitle,
                color = AppTheme.colors.text,
                modifier = Modifier.semantics { heading() },
            )
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
            AttentionSection(schedule, wallet, actions)
            NextSessionSection(schedule.nextSession, actions)
            TodayWorkoutSection(workout, actions.onStartWorkout)
            wallet.wallet?.packages?.firstOrNull()?.let { pack ->
                ActionRow(
                    Icons.Outlined.CardMembership,
                    stringResource(Res.string.home_package, pack.used, pack.total),
                    onClick = actions.onOpenWallet,
                    subtitle = pack.name,
                )
            }
        }
    }
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
                badges = { SessionStateChip(item.state) },
            )
        }
        unpaid.forEach { charge ->
            CardRow(
                title = stringResource(Res.string.attention_unpaid, money(charge.amount)),
                supporting = stringResource(chargeReasonText(charge.reason)),
                onClick = actions.onOpenWallet,
                leading = { androidx.compose.material3.Icon(Icons.Outlined.Payments, null, tint = AppTheme.colors.brandStrong) },
            )
        }
    }
}

@Composable
private fun NextSessionSection(next: ClientSession?, actions: ClientHomeActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SectionHeader(stringResource(Res.string.home_next_session))
        if (next == null) {
            Text(stringResource(Res.string.home_no_upcoming), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
            SecondaryButton(stringResource(Res.string.request_session), onClick = actions.onRequestSession, icon = Icons.Outlined.CalendarToday)
            return@Column
        }
        Surface(
            shape = AppShapes.featureCard,
            color = AppTheme.colors.surface,
            border = BorderStroke(1.dp, AppTheme.colors.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                SessionStateChip(next.state)
                Text(dayTitle(next.session.start.dateIn(next.zone)), style = AppTheme.type.itemTitle, color = AppTheme.colors.text)
                MetaLine(Icons.Outlined.Schedule, hours(next.session.start, next.session.end, next.zone))
                next.session.planTitle?.let { MetaLine(Icons.Outlined.FitnessCenter, stringResource(Res.string.plan_line, it)) }
                MetaLine(Icons.Outlined.PersonOutline, next.trainer.name)
                MetaLine(Icons.Outlined.LocationOn, next.session.location)
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = Spacing.s),
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

@Composable
private fun TodayWorkoutSection(workout: TodayWorkoutUiState, onStart: () -> Unit) {
    if (workout.loading) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SectionHeader(stringResource(Res.string.home_today_workout))
        when {
            workout.inProgress -> {
                Text(workout.inProgressName ?: stringResource(Res.string.workout_in_progress), style = AppTheme.type.itemTitle, color = AppTheme.colors.text)
                PrimaryButton(stringResource(Res.string.resume_workout), onClick = onStart, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.FitnessCenter)
            }
            workout.dayName != null -> {
                Text(workout.dayName, style = AppTheme.type.itemTitle, color = AppTheme.colors.text)
                Text(stringResource(Res.string.exercise_count, workout.exerciseCount), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                PrimaryButton(stringResource(Res.string.start_workout), onClick = onStart, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.FitnessCenter)
            }
            else -> {
                Text(stringResource(Res.string.home_no_plan), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                SecondaryButton(stringResource(Res.string.free_workout), onClick = onStart, icon = Icons.Outlined.FitnessCenter)
            }
        }
    }
}
