package com.trainingapp.feature.client

import com.trainingapp.feature.client.presentation.ClientScheduleViewModel
import com.trainingapp.feature.client.presentation.ClientShellViewModel
import com.trainingapp.feature.client.presentation.TodayWorkoutViewModel
import com.trainingapp.feature.client.presentation.WalletViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** What the shell is opened with: a guest's pending "request a session" intent, if any. */
data class ClientShellArgs(val pendingIntent: Boolean, val pendingTrainerId: String?)

/**
 * Client view models. The repositories ([pl.trainer.domain.ClientScheduleRepository],
 * [pl.trainer.domain.WalletRepository]), the network monitor, the id generator and the workout sources come from
 * the app, which wires them to the shared demo store and the signed-in session.
 */
val clientModule = module {
    viewModel { (args: ClientShellArgs) -> ClientShellViewModel(args.pendingIntent, args.pendingTrainerId) }
    viewModel { ClientScheduleViewModel(get(), get()) }
    viewModel { WalletViewModel(get(), get(), get()) }
    viewModel { TodayWorkoutViewModel(get(), get(), get()) }
}
