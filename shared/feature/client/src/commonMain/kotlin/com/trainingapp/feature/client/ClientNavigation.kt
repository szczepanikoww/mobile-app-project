package com.trainingapp.feature.client

import androidx.compose.runtime.Composable
import com.trainingapp.feature.client.presentation.ClientScheduleViewModel
import com.trainingapp.feature.client.presentation.ClientShellViewModel
import com.trainingapp.feature.client.presentation.TodayWorkoutViewModel
import com.trainingapp.feature.client.presentation.WalletViewModel
import com.trainingapp.feature.client.ui.ClientShell
import com.trainingapp.feature.client.ui.ClientShellActions
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The navigation- and DI-aware entry of the client shell, rendered by the app for `ClientHomeDestination`. The view
 * models are scoped to that destination, so the tabs share one schedule and one wallet.
 */
@Composable
fun ClientShellRoute(
    displayName: String,
    initials: String,
    args: ClientShellArgs,
    actions: ClientShellActions,
    progressTab: @Composable () -> Unit,
    chatUnread: Int = 0,
) {
    val shell: ClientShellViewModel = koinViewModel { parametersOf(args) }
    val schedule: ClientScheduleViewModel = koinViewModel()
    val wallet: WalletViewModel = koinViewModel()
    val workout: TodayWorkoutViewModel = koinViewModel()
    ClientShell(displayName, initials, shell, schedule, wallet, workout, actions, progressTab, chatUnread)
}
