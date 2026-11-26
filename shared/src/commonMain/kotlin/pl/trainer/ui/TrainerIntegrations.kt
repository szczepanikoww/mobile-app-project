package pl.trainer.ui

import androidx.compose.runtime.Composable

/** Whose plan a plan section is about: the client's default plan, or with [sessionId] the plan of one session. */
data class PlanTarget(
    val clientId: String,
    val clientName: String,
    val sessionId: String? = null,
    /** Names a plan written out for the session ("Anna Nowak · 26 lis"). */
    val sessionTitle: String? = null,
    /** The session's own plan, as the dashboard reports it. */
    val planTitle: String? = null,
)

/**
 * What the trainer views need from the app around them: the chat and profile (owned by the shell), the plan section
 * (owned by the workout builder) and the platform share sheet. Every member is optional, so the views stay
 * previewable and testable without the app; a missing piece hides its control.
 */
data class TrainerIntegrations(
    /** The signed-in trainer's display name, for the Start greeting. */
    val trainerName: String = "",
    val onOpenChats: (() -> Unit)? = null,
    val onOpenChat: ((clientId: String) -> Unit)? = null,
    val onOpenProfile: (() -> Unit)? = null,
    /** Unread messages, shown as a dot on the chat icon. */
    val chatUnread: Int = 0,
    /** The plan of a client or a session: shows the current plan and lets the trainer assign or write one out. */
    val planSection: (@Composable (PlanTarget) -> Unit)? = null,
    /** Opens the system share sheet with [text] (the invite message). */
    val shareText: ((text: String) -> Unit)? = null,
)
