package com.trainingapp.feature.guest.platform

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberShareAction(): ShareAction {
    val context = LocalContext.current
    return remember(context) {
        ShareAction { title, text ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            false
        }
    }
}

/** Backed by SharedPreferences; survives restarts, cleared with the app data. */
class AndroidWelcomePreferences(context: Context) : WelcomePreferences {
    private val prefs = context.applicationContext.getSharedPreferences("guest", Context.MODE_PRIVATE)
    override fun welcomeSeen() = prefs.getBoolean(KEY, false)
    override fun markWelcomeSeen() = prefs.edit().putBoolean(KEY, true).apply()

    private companion object {
        const val KEY = "welcome_seen"
    }
}

internal actual fun org.koin.core.scope.Scope.platformWelcomePreferences(): WelcomePreferences = AndroidWelcomePreferences(get<Context>())

@Composable
actual fun rememberAutoAdvanceAllowed(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val animations = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val accessibility = context.getSystemService(AccessibilityManager::class.java)
        animations > 0f && accessibility?.isTouchExplorationEnabled != true
    }
}
