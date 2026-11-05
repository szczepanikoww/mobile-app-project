package com.trainingapp.feature.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.component.ActionRow
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.MetaLine
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.SecondaryButton
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.component.TonalButton
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.client.resources.*
import org.jetbrains.compose.resources.stringResource
import pl.trainer.domain.ClientSession
import pl.trainer.domain.ClientSessionState
import pl.trainer.domain.Failure

data class ClientSessionDetailActions(
    val onReschedule: (ClientSession) -> Unit,
    val onCancel: (ClientSession) -> Unit,
    val onWithdraw: (ClientSession) -> Unit,
    val onAnswerProposal: (ClientSession, Boolean) -> Unit,
    val onRequestAgain: (trainerId: String) -> Unit,
    val onShowPolicy: (versionId: String) -> Unit,
    /** Null when the chat is not available on this platform (A6 is Android only). */
    val onChat: (() -> Unit)?,
    /** The Android calendar insert intent (G5, D6); null on desktop. */
    val onAddToCalendar: ((ClientSession) -> Unit)?,
)

/**
 * The session details pushed over the calendar: time and time zone, place, trainer, plan, status and how it is
 * paid (read-only). The actions depend on the status.
 */
@Composable
fun ClientSessionDetailContent(
    session: ClientSession,
    submitting: Boolean,
    actionFailure: Failure?,
    canChangeNow: Boolean,
    actions: ClientSessionDetailActions,
) {
    val s = session.session
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        Surface(
            shape = AppShapes.frame,
            color = AppTheme.colors.surface,
            border = BorderStroke(1.dp, AppTheme.colors.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                SessionStateChip(session.state)
                if (s.proposal != null) {
                    Text(stringResource(Res.string.current_time_label), style = AppTheme.type.label, color = AppTheme.colors.textSecondary)
                }
                Text(dayTitle(s.start.dateIn(session.zone)), style = AppTheme.type.itemTitle, color = AppTheme.colors.text)
                MetaLine(Icons.Outlined.Schedule, hours(s.start, s.end, session.zone))
                MetaLine(Icons.Outlined.Public, stringResource(Res.string.time_zone_line, session.zone))
                HorizontalDivider(color = AppTheme.colors.outline)
                MetaLine(Icons.Outlined.LocationOn, s.location)
                MetaLine(Icons.Outlined.PersonOutline, session.trainer.name)
                s.planTitle?.let { MetaLine(Icons.Outlined.FitnessCenter, stringResource(Res.string.plan_line, it)) }
                MetaLine(Icons.Outlined.Payments, paymentText(s.payment))
            }
        }

        actionFailure?.let { InlineMessage(failureMessage(it), tone = MessageTone.Error) }

        when (session.state) {
            ClientSessionState.ProposalFromTrainer -> ProposalSection(session, submitting, actions)
            ClientSessionState.RescheduleRequested -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                val proposal = s.proposal
                if (proposal != null) {
                    InlineMessage(stringResource(Res.string.reschedule_requested_info, shortDateTime(proposal.start, session.zone)))
                }
                SecondaryButton(stringResource(Res.string.withdraw_request), onClick = { actions.onWithdraw(session) }, enabled = !submitting)
            }
            ClientSessionState.AwaitingTrainer -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                InlineMessage(stringResource(Res.string.request_sent_info))
                SecondaryButton(stringResource(Res.string.withdraw_request), onClick = { actions.onWithdraw(session) }, enabled = !submitting)
            }
            ClientSessionState.Rejected, ClientSessionState.Expired -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                InlineMessage(
                    stringResource(if (session.state == ClientSessionState.Rejected) Res.string.request_rejected_info else Res.string.request_expired_info),
                    tone = MessageTone.Warning,
                )
                PrimaryButton(
                    stringResource(Res.string.request_other_time),
                    onClick = { actions.onRequestAgain(session.trainer.id) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.CalendarToday,
                )
            }
            ClientSessionState.Confirmed -> if (canChangeNow) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    TonalButton(
                        stringResource(Res.string.reschedule),
                        onClick = { actions.onReschedule(session) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !submitting,
                        icon = Icons.Outlined.EditCalendar,
                    )
                    AppTextButton(
                        stringResource(Res.string.cancel_session),
                        onClick = { actions.onCancel(session) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !submitting,
                        destructive = true,
                    )
                }
            }
            else -> Unit
        }

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            actions.onChat?.let { chat -> ActionRow(Icons.AutoMirrored.Outlined.Chat, stringResource(Res.string.write_to_trainer), onClick = chat) }
            if (session.state == ClientSessionState.Confirmed && canChangeNow) {
                actions.onAddToCalendar?.let { add ->
                    ActionRow(Icons.Outlined.EventAvailable, stringResource(Res.string.add_to_calendar), onClick = { add(session) })
                }
            }
            s.policyVersionId?.let { versionId ->
                ActionRow(
                    Icons.Outlined.Description,
                    stringResource(Res.string.policy_link, session.trainer.name),
                    onClick = { actions.onShowPolicy(versionId) },
                    subtitle = stringResource(Res.string.policy_link_hint),
                )
            }
        }
    }
}

@Composable
private fun ProposalSection(session: ClientSession, submitting: Boolean, actions: ClientSessionDetailActions) {
    val proposal = session.session.proposal ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SectionHeader(stringResource(Res.string.trainer_proposal))
        Surface(shape = AppShapes.panel, color = AppTheme.colors.softAccent, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(dayTitle(proposal.start.dateIn(session.zone)), style = AppTheme.type.itemTitle, color = AppTheme.colors.onSoftAccent)
                Text(hours(proposal.start, proposal.end, session.zone), style = AppTheme.type.body, color = AppTheme.colors.onSoftAccent)
            }
        }
        Text(stringResource(Res.string.original_stays_confirmed), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SecondaryButton(stringResource(Res.string.decline_proposal), onClick = { actions.onAnswerProposal(session, false) }, enabled = !submitting)
            PrimaryButton(
                stringResource(Res.string.accept),
                onClick = { actions.onAnswerProposal(session, true) },
                modifier = Modifier.weight(1f),
                loading = submitting,
            )
        }
    }
}
