package com.trainingapp.feature.guest

import com.trainingapp.feature.guest.auth.AuthMode
import com.trainingapp.feature.guest.auth.AuthViewModel
import com.trainingapp.feature.guest.data.DemoAuthRepository
import com.trainingapp.feature.guest.data.DemoGuestCatalogRepository
import com.trainingapp.feature.guest.gymdetail.GymDetailViewModel
import com.trainingapp.feature.guest.model.AuthRepository
import com.trainingapp.feature.guest.model.GuestCatalogRepository
import com.trainingapp.feature.guest.platform.WelcomePreferences
import com.trainingapp.feature.guest.platform.platformWelcomePreferences
import com.trainingapp.feature.guest.trainerprofile.TrainerProfileViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Guest wiring. The repositories are explicit demo sources: no catalogue backend or identity provider exists yet.
 * [com.trainingapp.domain.common.NetworkMonitor] and [com.trainingapp.domain.common.TimeSource] come from the app.
 */
val guestModule = module {
    single<GuestCatalogRepository> { DemoGuestCatalogRepository() }
    single<AuthRepository> { DemoAuthRepository(get()) }
    single<WelcomePreferences> { platformWelcomePreferences() }

    viewModel { GuestCatalogViewModel(get(), get()) }
    viewModel { (trainerId: String) -> TrainerProfileViewModel(trainerId, get(), get()) }
    viewModel { (gymId: String) -> GymDetailViewModel(gymId, get(), get(), get()) }
    viewModel { (mode: AuthMode) -> AuthViewModel(mode, get()) }
}
