package com.trainingapp.feature.guest.platform

import org.koin.core.scope.Scope

/** The persisted welcome flag for this platform. Android reads the Context registered with `androidContext()`. */
internal expect fun Scope.platformWelcomePreferences(): WelcomePreferences
