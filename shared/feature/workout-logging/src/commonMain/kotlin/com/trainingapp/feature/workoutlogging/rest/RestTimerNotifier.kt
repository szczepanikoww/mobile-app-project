package com.trainingapp.feature.workoutlogging.rest

import androidx.compose.runtime.Composable

/**
 * The rest-end system notification (decision D7). Android schedules an exact alarm that also fires when the app is
 * in the background or was killed; desktop has no notification. No Android type crosses this boundary.
 */
interface RestTimerNotifier {
    fun schedule(alarm: RestAlarm)

    fun cancel(workoutLogId: String)

    object None : RestTimerNotifier {
        override fun schedule(alarm: RestAlarm) = Unit
        override fun cancel(workoutLogId: String) = Unit
    }
}

/**
 * What the notification says: "Koniec przerwy" and "Czas na serię 3: Wyciskanie sztangi leżąc". Only workout
 * content; never notes, measurements or chat.
 */
data class RestAlarm(
    val workoutLogId: String,
    val endsAtEpochMs: Long,
    val nextSetNumber: Int?,
    val exerciseName: String?,
)

/** Whether the app may post notifications (Android 13+ asks the user once). */
interface NotificationPermission {
    val granted: Boolean

    /** Shows the system prompt; [onResult] gets whether it was granted. */
    fun request(onResult: (Boolean) -> Unit)

    /** Opens the app's notification settings, for a user who denied the prompt. */
    fun openSettings()
}

@Composable
expect fun rememberNotificationPermission(): NotificationPermission
