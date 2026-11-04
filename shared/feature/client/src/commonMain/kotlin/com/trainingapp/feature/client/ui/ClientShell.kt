package com.trainingapp.feature.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.designsystem.component.AppBottomNavigation
import com.trainingapp.designsystem.component.DetailHeader
import com.trainingapp.designsystem.component.InitialsAvatar
import com.trainingapp.designsystem.component.NavItem
import com.trainingapp.designsystem.component.TopLevelHeader
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.feature.client.presentation.ClientRoute
import com.trainingapp.feature.client.presentation.ClientScheduleViewModel
import com.trainingapp.feature.client.presentation.ClientShellViewModel
import com.trainingapp.feature.client.presentation.ClientTab
import com.trainingapp.feature.client.presentation.ScheduleMessage
import com.trainingapp.feature.client.presentation.TodayWorkoutViewModel
import com.trainingapp.feature.client.presentation.WalletViewModel
import com.trainingapp.feature.client.resources.*
import org.jetbrains.compose.resources.stringResource
import pl.trainer.domain.ClientSession
import pl.trainer.ui.PlatformBackHandler

/** What the client shell needs from the app: other features' entry points and the session actions. */
data class ClientShellActions(
    /** A1: resumes the workout in progress or starts today's plan (the centre action, D3). */
    val onStartWorkout: () -> Unit,
    /** A6, provided by the Android host; null hides the chat icon. */
    val onOpenChat: (() -> Unit)?,
    /** The public trainer list (guest views), for a client without a trainer. */
    val onFindTrainer: () -> Unit,
    /** Shown only when the signed-in session holds both roles. Replaces the back stack with the trainer shell. */
    val onSwitchToTrainer: (() -> Unit)?,
    val onLogOut: () -> Unit,
    /** The Android calendar insert intent (G5, D6); null on desktop. */
    val onAddToCalendar: ((ClientSession) -> Unit)? = null,
)

/**
 * B0: the one frame around every client view. It owns the top bar, the bottom navigation, snackbars, the pushed
 * session details and the sheets; the tab contents render only their content. A3 "Postępy" is provided by the app
 * as [progressTab] and rendered without its own header.
 */
@Composable
fun ClientShell(
    displayName: String,
    initials: String,
    shell: ClientShellViewModel,
    schedule: ClientScheduleViewModel,
    wallet: WalletViewModel,
    workout: TodayWorkoutViewModel,
    actions: ClientShellActions,
    progressTab: @Composable () -> Unit,
    chatUnread: Int = 0,
) {
    val shellState by shell.state.collectAsStateWithLifecycle()
    val scheduleState by schedule.state.collectAsStateWithLifecycle()
    val walletState by wallet.state.collectAsStateWithLifecycle()
    val workoutState by workout.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val tabs = rememberSaveableStateHolder()
    val route = shellState.route
    val firstName = displayName.substringBefore(' ').ifBlank { displayName }

    PlatformBackHandler(route != null) { shell.back() }

    // A guest's pending "request a session" opens the booking sheet once the data is there; it never sends anything.
    LaunchedEffect(shellState.hasPendingIntent, scheduleState.schedule != null) {
        if (shellState.hasPendingIntent && scheduleState.schedule != null) {
            val (open, trainerId) = shell.consumeIntent(hasTrainer = scheduleState.hasTrainer)
            if (open) schedule.openBooking(trainerId)
        }
    }

    val messages = mapOf(
        ScheduleMessage.RequestSent to stringResource(Res.string.message_request_sent),
        ScheduleMessage.RescheduleSent to stringResource(Res.string.message_reschedule_sent),
        ScheduleMessage.ProposalAccepted to stringResource(Res.string.message_proposal_accepted),
        ScheduleMessage.ProposalDeclined to stringResource(Res.string.message_proposal_declined),
        ScheduleMessage.RequestWithdrawn to stringResource(Res.string.message_request_withdrawn),
        ScheduleMessage.SessionCancelled to stringResource(Res.string.message_session_cancelled),
    )
    LaunchedEffect(scheduleState.message) {
        scheduleState.message?.let { message ->
            schedule.dismissMessage()
            snackbar.showSnackbar(messages.getValue(message))
        }
    }
    val paymentPending = stringResource(Res.string.message_payment_pending)
    LaunchedEffect(walletState.paymentSubmitted) {
        if (walletState.paymentSubmitted) {
            wallet.dismissSubmitted()
            snackbar.showSnackbar(paymentPending)
        }
    }

    val openSession: (String) -> Unit = { shell.open(ClientRoute.SessionDetail(it)) }
    val openWallet: () -> Unit = { shell.select(ClientTab.Wallet) }
    val workoutLabel = stringResource(if (workoutState.inProgress) Res.string.resume_workout else Res.string.start_workout)

    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = {
            if (route != null) {
                DetailHeader(stringResource(Res.string.session_details), onBack = { shell.back() }, backEnabled = !scheduleState.submitting)
            } else {
                TopLevelHeader(stringResource(shellState.tab.title())) {
                    actions.onOpenChat?.let { chat -> ChatButton(chatUnread, chat) }
                    AccountMenu(initials, actions)
                }
            }
        },
        bottomBar = {
            if (route == null) {
                AppBottomNavigation(
                    items = ClientTab.entries.map { tab ->
                        NavItem(stringResource(tab.title()), tab.icon(), tab == shellState.tab) { shell.select(tab) }
                    },
                    createLabel = workoutLabel,
                    onCreate = actions.onStartWorkout,
                    createIcon = Icons.Outlined.FitnessCenter,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = Sizes.contentWidth).fillMaxWidth()) {
                when (route) {
                    is ClientRoute.SessionDetail -> {
                        val session = scheduleState.session(route.sessionId)
                        if (session == null) {
                            com.trainingapp.designsystem.component.LoadingState()
                        } else {
                            ClientSessionDetailContent(
                                session = session,
                                submitting = scheduleState.submitting,
                                actionFailure = scheduleState.actionFailure,
                                canChangeNow = scheduleState.schedule?.let { session.session.start > it.serverNow } == true,
                                actions = ClientSessionDetailActions(
                                    onReschedule = schedule::openReschedule,
                                    onCancel = schedule::openCancellation,
                                    onWithdraw = schedule::withdraw,
                                    onAnswerProposal = schedule::answerProposal,
                                    onRequestAgain = { trainerId -> schedule.openBooking(trainerId) },
                                    onShowPolicy = { schedule.showPolicy(it) },
                                    onChat = actions.onOpenChat,
                                    onAddToCalendar = actions.onAddToCalendar,
                                ),
                            )
                        }
                    }
                    null -> tabs.SaveableStateProvider(shellState.tab.name) {
                        when (shellState.tab) {
                            ClientTab.Start -> ClientHomeContent(
                                firstName = firstName,
                                schedule = scheduleState,
                                wallet = walletState,
                                workout = workoutState,
                                actions = ClientHomeActions(
                                    onOpenSession = openSession,
                                    onReschedule = schedule::openReschedule,
                                    onRequestSession = { schedule.openBooking() },
                                    onStartWorkout = actions.onStartWorkout,
                                    onChat = actions.onOpenChat,
                                    onFindTrainer = actions.onFindTrainer,
                                    onOpenWallet = openWallet,
                                    onRetry = schedule::reload,
                                ),
                            )
                            ClientTab.Calendar -> ClientCalendarContent(
                                state = scheduleState,
                                actions = ClientCalendarActions(
                                    onDate = schedule::selectDate,
                                    onShiftWeek = schedule::shiftWeek,
                                    onToday = schedule::showToday,
                                    onOpenSession = openSession,
                                    onRequestSession = { schedule.openBooking() },
                                    onAnswerProposal = schedule::answerProposal,
                                    onWithdraw = schedule::withdraw,
                                    onOpenWallet = openWallet,
                                    onFindTrainer = actions.onFindTrainer,
                                    onRetry = schedule::reload,
                                ),
                            )
                            ClientTab.Progress -> progressTab()
                            ClientTab.Wallet -> WalletContent(walletState, onPay = wallet::openPayment, onRetry = wallet::reload)
                        }
                    }
                }
            }
        }
    }

    scheduleState.booking?.let { sheet ->
        BookingRequestSheet(
            state = sheet,
            trainers = scheduleState.schedule?.trainers.orEmpty(),
            submitting = scheduleState.submitting,
            offline = scheduleState.offline,
            actions = BookingSheetActions(
                onTrainer = schedule::bookingTrainer,
                onDate = schedule::bookingDate,
                onSlot = schedule::bookingSlot,
                onPlace = schedule::bookingPlace,
                onPolicyAccepted = schedule::bookingPolicyAccepted,
                onShowPolicy = { schedule.showPolicy(it) },
                onSend = schedule::sendBooking,
                onDismiss = schedule::closeBooking,
            ),
        )
    }
    scheduleState.reschedule?.let { sheet ->
        RescheduleSheet(
            state = sheet,
            submitting = scheduleState.submitting,
            offline = scheduleState.offline,
            actions = RescheduleSheetActions(
                onDate = schedule::rescheduleDate,
                onSlot = schedule::rescheduleSlot,
                onSend = schedule::sendReschedule,
                onDismiss = schedule::closeReschedule,
            ),
        )
    }
    scheduleState.cancellation?.let { sheet ->
        CancellationSheet(
            state = sheet,
            submitting = scheduleState.submitting,
            actions = CancellationSheetActions(
                onConfirm = schedule::confirmCancellation,
                onRefreshQuote = schedule::refreshQuote,
                onShowPolicy = { schedule.showPolicy(it) },
                onDismiss = schedule::closeCancellation,
            ),
        )
    }
    scheduleState.policy?.let { policy -> PolicySheet(policy, onDismiss = schedule::closePolicy) }
    walletState.payment?.let { sheet ->
        PaymentSheet(
            state = sheet,
            submitting = walletState.submitting,
            offline = walletState.offline,
            onMethod = wallet::paymentMethod,
            onPay = wallet::pay,
            onDismiss = wallet::closePayment,
        )
    }
}

private fun ClientTab.title() = when (this) {
    ClientTab.Start -> Res.string.tab_start
    ClientTab.Calendar -> Res.string.tab_calendar
    ClientTab.Progress -> Res.string.tab_progress
    ClientTab.Wallet -> Res.string.tab_wallet
}

private fun ClientTab.icon() = when (this) {
    ClientTab.Start -> Icons.Outlined.Home
    ClientTab.Calendar -> Icons.Outlined.CalendarToday
    ClientTab.Progress -> Icons.AutoMirrored.Outlined.ShowChart
    ClientTab.Wallet -> Icons.Outlined.AccountBalanceWallet
}

@Composable
private fun ChatButton(unread: Int, onClick: () -> Unit) {
    val description = if (unread > 0) stringResource(Res.string.chat_unread, unread) else stringResource(Res.string.chat)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) {
            Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null)
        }
    }
}

/** The avatar opens a short menu: switch to the trainer role (only with both roles) and log out. No bell (D4). */
@Composable
private fun AccountMenu(initials: String, actions: ClientShellActions) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.account_menu)
    Box {
        Box(
            Modifier
                .sizeIn(minWidth = Sizes.minTouchTarget, minHeight = Sizes.minTouchTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = label) { open = true }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { InitialsAvatar(initials) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.onSwitchToTrainer?.let { switch ->
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.switch_to_trainer)) },
                    leadingIcon = { Icon(Icons.Outlined.SwapHoriz, null) },
                    onClick = { open = false; switch() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.log_out)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                onClick = { open = false; actions.onLogOut() },
            )
        }
    }
}
