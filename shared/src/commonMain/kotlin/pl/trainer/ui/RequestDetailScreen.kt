package pl.trainer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toLocalDateTime
import pl.trainer.domain.*
import pl.trainer.presentation.TrainerUiState
import pl.trainer.resources.*

/**
 * B4 "Szczegóły prośby": the trainer answers a booking or reschedule request with everything in one place. The
 * current time stays the confirmed one until acceptance succeeds; a taken slot disables "Akceptuj". For a late
 * reschedule the fee of the client's policy version is shown and can be waived in full (D8).
 */
@Composable
fun RequestDetailScreen(
    state: TrainerUiState,
    session: Session,
    onRetry: () -> Unit,
    onClient: () -> Unit,
    onAccept: (waiveFee: Boolean, reason: String) -> Unit,
    onProposeOther: () -> Unit,
    onReject: () -> Unit,
) {
    val client = state.data!!.client(session.clientId)
    val review = state.review?.takeIf { it.session.id == session.id }
    var confirmReject by remember(session.id) { mutableStateOf(false) }
    var waive by rememberSaveable(session.id) { mutableStateOf(false) }
    var reason by rememberSaveable(session.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.section)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.padding), verticalAlignment = Alignment.CenterVertically) {
            Avatar(client, large = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
                Text(client.name, style = MaterialTheme.typography.titleLarge)
                StatusLabel(session)
                val payment = session.payment
                if (payment?.kind == PaymentKind.Package && payment.packageUsed != null && payment.packageTotal != null) {
                    MetaLine(Icons.Outlined.CardMembership, tr(Res.string.request_package, payment.packageUsed, payment.packageTotal))
                }
                val requestedAt = review?.requestedAt
                if (review != null && requestedAt != null) {
                    val at = requestedAt
                    val minutes = (review.serverNow - at).inWholeMinutes.coerceAtLeast(0)
                    MetaLine(Icons.Outlined.Schedule, when {
                        minutes < 1 -> tr(Res.string.requested_just_now)
                        minutes < 60 -> tr(Res.string.requested_minutes_ago, minutes.toInt())
                        minutes < 48 * 60 -> tr(Res.string.requested_hours_ago, (minutes / 60).toInt())
                        else -> tr(Res.string.requested_on, DateFormats.date(at.toLocalDateTime(TrainerZone).date, DateFormats.defaultLanguage(), "d MMM"))
                    })
                }
            }
        }

        when {
            state.reviewLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            state.reviewFailure != null && review == null -> {
                InfoBanner(failureText(state.reviewFailure), error = true)
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = Layout.target)) { Text(tr(Res.string.retry)) }
            }
        }

        val proposal = session.proposal
        if (proposal != null) {
            TimeCard(tr(Res.string.original_time), session.start, session.end, session.timeZone, highlighted = false)
            TimeCard(tr(Res.string.proposed_time), proposal.start, proposal.end, session.timeZone, highlighted = true)
            Text(tr(Res.string.original_stays), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TimeCard(tr(Res.string.requested_time), session.start, session.end, session.timeZone, highlighted = true)
            InfoBanner(tr(Res.string.booking_notice), Icons.Outlined.Schedule)
        }
        MetaLine(Icons.Outlined.LocationOn, session.location)

        review?.let {
            if (it.slotAvailable) {
                Surface(color = AppColors.successSurface, contentColor = AppColors.success, shape = Layout.pill) {
                    Row(Modifier.padding(horizontal = Layout.medium, vertical = Layout.small), horizontalArrangement = Arrangement.spacedBy(Layout.small),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.EventAvailable, null, Modifier.size(18.dp))
                        Text(tr(Res.string.slot_available), style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                InfoBanner(tr(Res.string.slot_taken), Icons.Outlined.EventBusy, error = true)
            }
        }

        val fee = review?.lateFee
        val policy = review?.policy
        if (fee != null && policy != null && !fee.free) {
            Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
                SectionTitle(tr(Res.string.late_change_policy, policy.version))
                Text(tr(Res.string.late_change_fee, "${fee.policyPercent}%", formatMoney(fee.amount)), style = MaterialTheme.typography.bodyLarge)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Layout.target)
                        .toggleable(value = waive, enabled = !state.submitting, role = Role.Switch, onValueChange = { waive = it }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Layout.medium),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                        Text(tr(Res.string.waive_fee), style = MaterialTheme.typography.bodyLarge)
                        Text(tr(Res.string.waive_fee_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = waive, onCheckedChange = null)
                }
                if (waive) {
                    OutlinedTextField(reason, { reason = it }, label = { Text(tr(Res.string.waive_reason)) }, enabled = !state.submitting,
                        modifier = Modifier.fillMaxWidth(), shape = Layout.card, singleLine = true)
                }
            }
        }

        ActionRow(Icons.Outlined.PersonOutline, tr(Res.string.view_profile), onClick = onClient)
        Column(verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            PrimaryButton(tr(Res.string.accept), enabled = review?.slotAvailable == true, loading = state.submitting,
                onClick = { onAccept(waive, reason) })
            if (proposal != null) {
                OutlinedButton(onClick = onProposeOther, enabled = !state.submitting, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) {
                    Icon(Icons.Outlined.EditCalendar, null, Modifier.size(20.dp)); Spacer(Modifier.width(Layout.small)); Text(tr(Res.string.propose_other_time))
                }
            }
            TextButton(onClick = { confirmReject = true }, enabled = !state.submitting, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) {
                Text(tr(Res.string.reject), color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(Layout.small))
    }
    if (confirmReject) {
        ConfirmDialog(
            title = tr(Res.string.reject_title),
            body = tr(Res.string.reject_body),
            confirm = tr(Res.string.reject),
            context = client.name + "\n" + DateFormats.date((session.proposal?.start ?: session.start).toLocalDateTime(TrainerZone).date, DateFormats.defaultLanguage(), "d MMMM yyyy") +
                " · " + timeRange(session.proposal?.start ?: session.start, session.proposal?.end ?: session.end, session.timeZone),
            submitting = state.submitting,
            onDismiss = { confirmReject = false },
            onConfirm = { confirmReject = false; onReject() },
        )
    }
}

@Composable
private fun TimeCard(label: String, start: kotlin.time.Instant, end: kotlin.time.Instant, zone: String, highlighted: Boolean) {
    Surface(
        shape = Layout.panel,
        color = if (highlighted) AppColors.softAccent else MaterialTheme.colorScheme.surface,
        border = if (highlighted) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Layout.padding), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (highlighted) AppColors.onSoftAccent else MaterialTheme.colorScheme.onSurfaceVariant)
            TimeSummary(start, end, zone)
            Text(tr(Res.string.time_zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
