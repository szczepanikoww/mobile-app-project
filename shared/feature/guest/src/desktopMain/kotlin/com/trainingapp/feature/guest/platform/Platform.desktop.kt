package com.trainingapp.feature.guest.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.prefs.Preferences

@Composable
actual fun rememberShareAction(): ShareAction = remember {
    ShareAction { _, text ->
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }.isSuccess
    }
}

/** Backed by java.util.prefs, so the desktop preview also shows the welcome screen only once. */
class DesktopWelcomePreferences : WelcomePreferences {
    private val node = Preferences.userRoot().node("com/trainingapp/guest")
    override fun welcomeSeen() = node.getBoolean(KEY, false)
    override fun markWelcomeSeen() {
        node.putBoolean(KEY, true)
    }

    private companion object {
        const val KEY = "welcome_seen"
    }
}

internal actual fun org.koin.core.scope.Scope.platformWelcomePreferences(): WelcomePreferences = DesktopWelcomePreferences()

@Composable
actual fun rememberAutoAdvanceAllowed(): Boolean = true
