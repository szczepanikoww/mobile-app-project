package com.trainingapp.feature.workoutlogging.rest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** The desktop preview posts no notifications, so there is nothing to ask for. */
@Composable
actual fun rememberNotificationPermission(): NotificationPermission = remember {
    object : NotificationPermission {
        override val granted: Boolean = true
        override fun request(onResult: (Boolean) -> Unit) = onResult(true)
        override fun openSettings() = Unit
    }
}
