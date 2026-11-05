package pl.trainer.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.trainer.domain.CheckInInfo
import pl.trainer.domain.Failure
import pl.trainer.domain.SessionStatus
import pl.trainer.domain.TrainerException
import pl.trainer.domain.TrainerRepository

enum class Attendance { Present, NoShow }

data class CheckInUiState(
    val loading: Boolean = true,
    val info: CheckInInfo? = null,
    val loadFailure: Failure? = null,
    /** The command being sent; buttons are disabled meanwhile, so a double tap records once. */
    val submitting: Attendance? = null,
    /** The last command failed: offline ("Niewysłane"), conflict (reload) or another error. Never shown as done. */
    val actionFailure: Failure? = null,
    val confirmNoShow: Boolean = false,
    /** Recorded by the server; the screen closes with a snackbar. */
    val recorded: Attendance? = null,
)

/** A4 "Check-in sesji": the trainer records attendance for a confirmed session through [TrainerRepository]. */
class CheckInViewModel(private val sessionId: String, private val repository: TrainerRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(CheckInUiState())
    val state = mutableState.asStateFlow()

    init { reload() }

    fun reload() {
        mutableState.update { it.copy(loading = true, loadFailure = null, actionFailure = null) }
        viewModelScope.launch {
            try {
                val info = repository.checkIn(sessionId)
                mutableState.update { it.copy(loading = false, info = info) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(loading = false, loadFailure = (e as? TrainerException)?.failure ?: Failure.Load) }
            }
        }
    }

    fun markPresent() = submit(Attendance.Present)

    fun requestNoShow() {
        val info = state.value.info ?: return
        if (info.noShowAllowed && info.canCheckIn) mutableState.update { it.copy(confirmNoShow = true) }
    }

    fun dismissNoShow() = mutableState.update { it.copy(confirmNoShow = false) }

    fun confirmNoShow() {
        mutableState.update { it.copy(confirmNoShow = false) }
        submit(Attendance.NoShow)
    }

    private fun submit(attendance: Attendance) {
        val s = state.value
        val info = s.info ?: return
        if (s.submitting != null || s.recorded != null || !info.canCheckIn) return
        if (attendance == Attendance.NoShow && !info.noShowAllowed) return
        mutableState.update { it.copy(submitting = attendance, actionFailure = null) }
        viewModelScope.launch {
            try {
                val session = info.session
                val dashboard = when (attendance) {
                    Attendance.Present -> repository.completeSession(session.id, session.revision)
                    Attendance.NoShow -> repository.markNoShow(session.id, session.revision)
                }
                val updated = dashboard.sessions.firstOrNull { it.id == session.id } ?: session
                val expected = if (attendance == Attendance.Present) SessionStatus.Completed else SessionStatus.NoShow
                mutableState.update {
                    it.copy(
                        submitting = null,
                        info = info.copy(session = updated),
                        recorded = if (updated.status == expected) attendance else null,
                        actionFailure = if (updated.status == expected) null else Failure.Conflict,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(submitting = null, actionFailure = (e as? TrainerException)?.failure ?: Failure.Save) }
            }
        }
    }
}
