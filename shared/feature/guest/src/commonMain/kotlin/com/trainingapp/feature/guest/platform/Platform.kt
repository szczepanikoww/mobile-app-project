package com.trainingapp.feature.guest.platform

import androidx.compose.runtime.Composable

/**
 * Shares public text (name and short description; no public URL exists yet). Android opens the system share sheet;
 * desktop copies to the clipboard and returns true so the screen can confirm it.
 */
fun interface ShareAction {
    /** Returns true when the text was copied rather than handed to a share sheet. */
    fun share(title: String, text: String): Boolean
}

@Composable
expect fun rememberShareAction(): ShareAction

/** Whether the welcome screen was already shown on this install (D11: once per install). */
interface WelcomePreferences {
    fun welcomeSeen(): Boolean
    fun markWelcomeSeen()
}

class InMemoryWelcomePreferences(private var seen: Boolean = false) : WelcomePreferences {
    override fun welcomeSeen() = seen
    override fun markWelcomeSeen() {
        seen = true
    }
}

/** False when the user asked for reduced motion or a screen reader is active, so slides must not move by themselves. */
@Composable
expect fun rememberAutoAdvanceAllowed(): Boolean
