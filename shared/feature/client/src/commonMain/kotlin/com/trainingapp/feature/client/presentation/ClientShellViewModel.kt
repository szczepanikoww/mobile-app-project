package com.trainingapp.feature.client.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The client's top-level destinations. The centre "LIVE" button is an action, not a tab (D3). */
enum class ClientTab { Start, Calendar, Progress, Wallet }

/** Screens pushed over the tabs inside the client shell. */
sealed interface ClientRoute {
    data class SessionDetail(val sessionId: String) : ClientRoute
}

data class ClientShellUiState(
    val tab: ClientTab = ClientTab.Start,
    val routes: List<ClientRoute> = emptyList(),
    /** A guest's "request a session" intent that has not been handed to the booking sheet yet. */
    val pendingTrainerId: String? = null,
    val hasPendingIntent: Boolean = false,
) {
    val route: ClientRoute? get() = routes.lastOrNull()
}

/**
 * B0: the client shell's navigation state: the selected tab, the stack of pushed screens and the pending guest
 * intent. The intent only opens the booking sheet; it never sends a request by itself.
 */
class ClientShellViewModel(pendingIntent: Boolean, pendingTrainerId: String?) : ViewModel() {
    private val mutableState = MutableStateFlow(ClientShellUiState(pendingTrainerId = pendingTrainerId, hasPendingIntent = pendingIntent))
    val state: StateFlow<ClientShellUiState> = mutableState.asStateFlow()

    fun select(tab: ClientTab) = mutableState.update { it.copy(tab = tab, routes = emptyList()) }

    fun open(route: ClientRoute) = mutableState.update { it.copy(routes = it.routes + route) }

    /** Returns false when there was nothing to go back to. */
    fun back(): Boolean {
        if (state.value.routes.isEmpty()) return false
        mutableState.update { it.copy(routes = it.routes.dropLast(1)) }
        return true
    }

    /**
     * Hands the pending intent over once. Returns whether the booking sheet should open and the trainer to pre-fill
     * (null: the client's own trainer). Without a trainer in the intent and without an own trainer there is nothing to
     * book yet, so the client stays on Start, which offers "Znajdź trenera".
     */
    fun consumeIntent(hasTrainer: Boolean): Pair<Boolean, String?> {
        val current = state.value
        if (!current.hasPendingIntent) return false to null
        val open = current.pendingTrainerId != null || hasTrainer
        mutableState.update {
            it.copy(hasPendingIntent = false, pendingTrainerId = null, tab = if (open) ClientTab.Calendar else it.tab, routes = emptyList())
        }
        return open to current.pendingTrainerId
    }
}
