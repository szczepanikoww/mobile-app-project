package com.trainingapp.feature.client.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.contract.account.UpdateMeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The signed-in client's own account data. The app connects it to the sign-in session (`PATCH /v1/me`). */
interface ClientAccount {
    /** Only [SaveResult.Saved] means the server confirmed it; the session then carries the new name. */
    suspend fun saveDisplayName(name: String): SaveResult

    /** "Moje siłownie": every public gym and the client's choice; null without a server. */
    suspend fun gyms(): ClientGyms? = null

    /** Stores the client's gyms; returns the stored state, or null when it could not be saved. */
    suspend fun saveGyms(ids: Set<String>): ClientGyms? = null

    /** "Mam kod od trenera": joins the trainer of an invite code. Needs the server; the demo has none. */
    suspend fun joinTrainer(code: String): JoinResult = JoinResult.Unavailable

    enum class SaveResult { Saved, Offline, Invalid, Failed }

    sealed interface JoinResult {
        data class Joined(val trainerName: String) : JoinResult
        /** Unknown, expired or used up. */
        data object NotFound : JoinResult
        data object Offline : JoinResult
        data object Failed : JoinResult
        data object Unavailable : JoinResult
    }
}

data class GymOption(val id: String, val name: String, val address: String)

/** Every public gym and the ones the client attends. */
data class ClientGyms(val all: List<GymOption>, val selected: Set<String>)

enum class JoinError { Required, NotFound, Offline, Failed, Unavailable }

/** The "Mam kod od trenera" sheet; the typed code survives a failed attempt. */
data class JoinState(val code: String = "", val joining: Boolean = false, val error: JoinError? = null)

enum class NameError { Required, TooLong, Rejected, Offline, Failed }

/** The "Edytuj dane" sheet. The draft survives a failed save, so the client never retypes it. */
data class NameEditorState(
    val draft: String,
    val original: String,
    val saving: Boolean = false,
    val error: NameError? = null,
) {
    val changed: Boolean get() = draft.trim() != original.trim()
}

data class ClientProfileUiState(
    val editor: NameEditorState? = null,
    /** Set once after a confirmed save, for the shell's snackbar. */
    val saved: Boolean = false,
    val join: JoinState? = null,
    /** "Moje siłownie"; null without a server or until loaded. */
    val gyms: ClientGyms? = null,
    val gymsSaving: Boolean = false,
    val gymsFailed: Boolean = false,
    /** The trainer just joined, once, for the shell's snackbar. */
    val joinedTrainer: String? = null,
)

/** The client's own data on "Profil": currently the display name. */
class ClientProfileViewModel(private val account: ClientAccount) : ViewModel() {
    private val mutableState = MutableStateFlow(ClientProfileUiState())
    val state: StateFlow<ClientProfileUiState> = mutableState.asStateFlow()

    fun openEditor(currentName: String) = mutableState.update {
        it.copy(editor = NameEditorState(draft = currentName, original = currentName))
    }

    fun changeName(value: String) = mutableState.update { state ->
        state.copy(editor = state.editor?.takeUnless { it.saving }?.copy(draft = value, error = null) ?: state.editor)
    }

    /** Closing is ignored while the request runs, so its result is never lost. */
    fun closeEditor() = mutableState.update { state -> if (state.editor?.saving == true) state else state.copy(editor = null) }

    fun save() {
        val editor = state.value.editor ?: return
        if (editor.saving) return
        val name = editor.draft.trim()
        val invalid = when {
            name.isEmpty() -> NameError.Required
            name.length > UpdateMeRequest.DISPLAY_NAME_MAX_LENGTH -> NameError.TooLong
            else -> null
        }
        if (invalid != null) {
            mutableState.update { it.copy(editor = editor.copy(error = invalid)) }
            return
        }
        if (!editor.changed) {
            mutableState.update { it.copy(editor = null) }
            return
        }
        mutableState.update { it.copy(editor = editor.copy(saving = true, error = null)) }
        viewModelScope.launch {
            val result = account.saveDisplayName(name)
            mutableState.update { state ->
                val current = state.editor ?: return@update state
                when (result) {
                    ClientAccount.SaveResult.Saved -> state.copy(editor = null, saved = true)
                    ClientAccount.SaveResult.Offline -> state.copy(editor = current.copy(saving = false, error = NameError.Offline))
                    ClientAccount.SaveResult.Invalid -> state.copy(editor = current.copy(saving = false, error = NameError.Rejected))
                    ClientAccount.SaveResult.Failed -> state.copy(editor = current.copy(saving = false, error = NameError.Failed))
                }
            }
        }
    }

    fun dismissSaved() = mutableState.update { it.copy(saved = false) }

    init {
        viewModelScope.launch {
            val gyms = runCatching { account.gyms() }.getOrNull()
            mutableState.update { it.copy(gyms = gyms) }
        }
    }

    fun saveGyms(ids: Set<String>) {
        val previous = state.value.gyms ?: return
        if (state.value.gymsSaving) return
        mutableState.update { it.copy(gyms = previous.copy(selected = ids), gymsSaving = true, gymsFailed = false) }
        viewModelScope.launch {
            val saved = runCatching { account.saveGyms(ids) }.getOrNull()
            mutableState.update {
                if (saved != null) it.copy(gyms = saved, gymsSaving = false) else it.copy(gyms = previous, gymsSaving = false, gymsFailed = true)
            }
        }
    }

    /** Opens the sheet, with [code] filled in when it came from an invite link. */
    fun openJoin(code: String = "") = mutableState.update { it.copy(join = JoinState(code = code.trim().uppercase())) }

    fun changeJoinCode(value: String) = mutableState.update { state ->
        state.copy(join = state.join?.takeUnless { it.joining }?.copy(code = value.filter { it.isLetterOrDigit() }.uppercase().take(16), error = null) ?: state.join)
    }

    fun closeJoin() = mutableState.update { state -> if (state.join?.joining == true) state else state.copy(join = null) }

    fun submitJoin() {
        val join = state.value.join ?: return
        if (join.joining) return
        val code = join.code.trim()
        if (code.isEmpty()) {
            mutableState.update { it.copy(join = join.copy(error = JoinError.Required)) }
            return
        }
        mutableState.update { it.copy(join = join.copy(joining = true, error = null)) }
        viewModelScope.launch {
            val result = account.joinTrainer(code)
            mutableState.update { state ->
                val current = state.join ?: return@update state
                when (result) {
                    is ClientAccount.JoinResult.Joined -> state.copy(join = null, joinedTrainer = result.trainerName)
                    ClientAccount.JoinResult.NotFound -> state.copy(join = current.copy(joining = false, error = JoinError.NotFound))
                    ClientAccount.JoinResult.Offline -> state.copy(join = current.copy(joining = false, error = JoinError.Offline))
                    ClientAccount.JoinResult.Failed -> state.copy(join = current.copy(joining = false, error = JoinError.Failed))
                    ClientAccount.JoinResult.Unavailable -> state.copy(join = current.copy(joining = false, error = JoinError.Unavailable))
                }
            }
        }
    }

    fun dismissJoined() = mutableState.update { it.copy(joinedTrainer = null) }
}
