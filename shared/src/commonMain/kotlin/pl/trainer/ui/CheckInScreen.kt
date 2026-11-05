package pl.trainer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.trainer.domain.*
import pl.trainer.presentation.Attendance
import pl.trainer.presentation.CheckInUiState
import pl.trainer.presentation.CheckInViewModel
import pl.trainer.resources.*

/**
 * A4 "Check-in sesji" with its view model. [onRecorded] runs once the server has recorded attendance; the host
 * closes the screen and shows a snackbar.
 */
@Composable
fun CheckInRoute(sessionId: String, repository: TrainerRepository, onBack: () -> Unit, onRecorded: (Attendance) -> Unit) {
    val model: CheckInViewModel = viewModel(key = "check-in-$sessionId") { CheckInViewModel(sessionId, repository) }
    val state by model.state.collectAsState()
    val currentOnRecorded by rememberUpdatedState(onRecorded)
    LaunchedEffect(state.recorded) { state.recorded?.let { currentOnRecorded(it) } }
    PlatformBackHandler(enabled = state.submitting == null, onBack = onBack)
    TrainerTheme {
        CheckInScreen(state, onBack, model::reload, model::markPresent, model::requestNoShow, model::confirmNoShow, model::dismissNoShow)
    }
}

/** Attendance for one confirmed session: "Obecny" completes it, "Nieobecny" unlocks after the policy's grace period. */
@Composable
fun CheckInScreen(
    state: CheckInUiState,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onPresent: () -> Unit,
    onNoShow: () -> Unit,
    onConfirmNoShow: () -> Unit,
    onDismissNoShow: () -> Unit,
) {
    val submitting = state.submitting != null
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = Layout.headerHeight)
                    .padding(horizontal = Layout.small, vertical = Layout.small), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, enabled = !submitting) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr(Res.string.back)) }
                    Text(tr(Res.string.check_in_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            val info = state.info
            when {
                state.loading && info == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.loadFailure != null && info == null -> Box(Modifier.padding(Layout.gutter)) {
                    EmptyContent(Icons.Outlined.CloudOff, tr(Res.string.load_error_title), failureText(state.loadFailure), tr(Res.string.retry), onReload)
                }
                info != null -> CheckInContent(state, info, onReload, onPresent, onNoShow)
            }
        }
    }
    val info = state.info
    if (state.confirmNoShow && info != null) {
        ConfirmDialog(
            title = tr(Res.string.check_in_no_show_title),
            body = tr(Res.string.check_in_no_show_body),
            confirm = tr(Res.string.check_in_no_show_confirm),
            context = info.client.name + "\n" + timeRange(info.session.start, info.session.end, info.session.timeZone),
            submitting = submitting,
            onDismiss = onDismissNoShow,
            onConfirm = onConfirmNoShow,
        )
    }
}

@Composable
private fun CheckInContent(state: CheckInUiState, info: CheckInInfo, onReload: () -> Unit, onPresent: () -> Unit, onNoShow: () -> Unit) {
    val session = info.session
    val submitting = state.submitting != null
    Column(Modifier.fillMaxHeight().widthIn(max = Layout.contentWidth).verticalScroll(rememberScrollState()).padding(Layout.gutter),
        verticalArrangement = Arrangement.spacedBy(Layout.section)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.padding), verticalAlignment = Alignment.CenterVertically) {
            Avatar(info.client, large = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
                Text(info.client.name, style = MaterialTheme.typography.titleLarge)
                StatusLabel(session)
            }
        }
        Surface(shape = Layout.frame, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
                TimeSummary(session.start, session.end, session.timeZone)
                MetaLine(Icons.Outlined.LocationOn, session.location)
                session.planTitle?.let { MetaLine(Icons.Outlined.FitnessCenter, tr(Res.string.check_in_plan, it)) }
                session.payment?.let { MetaLine(Icons.Outlined.Payments, paymentText(it)) }
            }
        }
        state.actionFailure?.let { failure ->
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(Layout.small)) {
                InfoBanner(
                    tr(when (failure) {
                        Failure.Offline -> Res.string.check_in_not_sent
                        Failure.Conflict -> Res.string.check_in_conflict
                        else -> Res.string.save_error
                    }),
                    if (failure == Failure.Offline) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline,
                    error = true,
                )
                if (failure == Failure.Conflict) TextButton(onClick = onReload, modifier = Modifier.heightIn(min = Layout.target)) { Text(tr(Res.string.refresh)) }
            }
        }
        when (session.status) {
            SessionStatus.Completed -> InfoBanner(tr(Res.string.check_in_done_present), Icons.Outlined.DoneAll)
            SessionStatus.NoShow -> InfoBanner(tr(Res.string.check_in_done_no_show), Icons.Outlined.PersonOff)
            SessionStatus.Confirmed -> Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
                SectionTitle(tr(Res.string.check_in_attendance))
                PrimaryButton(tr(Res.string.check_in_present), enabled = !submitting, loading = state.submitting == Attendance.Present, onClick = onPresent)
                OutlinedButton(onClick = onNoShow, enabled = !submitting && info.noShowAllowed,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) {
                    if (state.submitting == Attendance.NoShow) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Layout.small))
                    }
                    Text(tr(Res.string.check_in_no_show))
                }
                val graceText = if (info.noShowAllowed) {
                    tr(Res.string.check_in_fee_note)
                } else if (!info.started) {
                    tr(Res.string.check_in_not_started, DateFormats.time(info.noShowAvailableAt, session.timeZone, DateFormats.defaultLanguage()), info.graceMinutes)
                } else {
                    tr(Res.string.check_in_grace, DateFormats.time(info.noShowAvailableAt, session.timeZone, DateFormats.defaultLanguage()), info.graceMinutes)
                }
                Text(graceText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> InfoBanner(tr(Res.string.check_in_unavailable), Icons.Outlined.Info)
        }
    }
}

@Composable
private fun paymentText(payment: SessionPayment): String = when (payment.kind) {
    PaymentKind.Package -> {
        val used = payment.packageUsed
        val total = payment.packageTotal
        if (used != null && total != null) tr(Res.string.check_in_payment_package_count, used, total) else tr(Res.string.check_in_payment_package)
    }
    PaymentKind.Online -> tr(Res.string.check_in_payment_online)
    PaymentKind.Cash -> tr(Res.string.check_in_payment_cash)
}

