package com.trainingapp.feature.workoutlogging.rest

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.trainingapp.feature.workoutlogging.resources.Res
import com.trainingapp.feature.workoutlogging.resources.rest_channel_description
import com.trainingapp.feature.workoutlogging.resources.rest_channel_name
import com.trainingapp.feature.workoutlogging.resources.rest_notification_text
import com.trainingapp.feature.workoutlogging.resources.rest_notification_text_generic
import com.trainingapp.feature.workoutlogging.resources.rest_notification_title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * Schedules the rest-end notification with [AlarmManager], which the system holds, so it also fires after the app
 * process was killed. Without the exact-alarm permission it falls back to an inexact alarm (may be a few seconds late).
 */
class AndroidRestTimerNotifier(context: Context) : RestTimerNotifier {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun schedule(alarm: RestAlarm) {
        scope.launch {
            val title = getString(Res.string.rest_notification_title)
            val text = if (alarm.nextSetNumber != null && alarm.exerciseName != null) {
                getString(Res.string.rest_notification_text, alarm.nextSetNumber, alarm.exerciseName)
            } else {
                getString(Res.string.rest_notification_text_generic)
            }
            val intent = pendingIntent(alarm.workoutLogId, title, text)
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            if (canExact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.endsAtEpochMs, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.endsAtEpochMs, intent)
            }
        }
    }

    override fun cancel(workoutLogId: String) {
        alarms.cancel(pendingIntent(workoutLogId, "", ""))
        NotificationManagerCompat.from(app).cancel(NOTIFICATION_TAG, workoutLogId.hashCode())
    }

    private fun pendingIntent(workoutLogId: String, title: String, text: String): PendingIntent {
        val intent = Intent(app, RestTimerReceiver::class.java)
            // The data URI makes one alarm per workout; extras do not distinguish PendingIntents.
            .setData(Uri.parse("trainingapp://rest/$workoutLogId"))
            .putExtra(EXTRA_WORKOUT_ID, workoutLogId)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_TEXT, text)
        return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val EXTRA_WORKOUT_ID = "workoutLogId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        /** Set on the launch intent when the notification is tapped; the app opens the live workout. */
        const val EXTRA_OPEN_WORKOUT = "com.trainingapp.openLiveWorkout"
        const val CHANNEL_ID = "rest_timer"
        const val NOTIFICATION_TAG = "rest"
    }
}

/** Posts the "Koniec przerwy" notification when the alarm fires. Declared in the app manifest. */
class RestTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val workoutId = intent.getStringExtra(AndroidRestTimerNotifier.EXTRA_WORKOUT_ID) ?: return
        val title = intent.getStringExtra(AndroidRestTimerNotifier.EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(AndroidRestTimerNotifier.EXTRA_TEXT).orEmpty()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // denied: the in-app timer is the only feedback
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ensureChannel(context)
                val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    ?.putExtra(AndroidRestTimerNotifier.EXTRA_OPEN_WORKOUT, true)
                val content = open?.let {
                    PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                }
                val notification = NotificationCompat.Builder(context, AndroidRestTimerNotifier.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setContentIntent(content)
                    .build()
                NotificationManagerCompat.from(context).notify(AndroidRestTimerNotifier.NOTIFICATION_TAG, workoutId.hashCode(), notification)
            } catch (e: SecurityException) {
                // The permission was revoked between the check and posting.
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(AndroidRestTimerNotifier.CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            AndroidRestTimerNotifier.CHANNEL_ID,
            getString(Res.string.rest_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(Res.string.rest_channel_description)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 120, 250)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build(),
            )
        }
        manager.createNotificationChannel(channel)
    }
}

@Composable
actual fun rememberNotificationPermission(): NotificationPermission {
    val context = LocalContext.current
    fun isGranted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var grantedNow by remember { mutableStateOf(isGranted()) }
    var callback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        grantedNow = result
        callback?.invoke(result)
        callback = null
    }
    val currentlyGranted = grantedNow
    return remember(launcher, currentlyGranted) {
        object : NotificationPermission {
            override val granted: Boolean = currentlyGranted

            override fun request(onResult: (Boolean) -> Unit) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isGranted()) {
                    onResult(true)
                    return
                }
                callback = onResult
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            override fun openSettings() {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}
