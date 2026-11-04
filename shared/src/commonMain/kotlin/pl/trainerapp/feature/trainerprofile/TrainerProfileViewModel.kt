package pl.trainerapp.feature.trainerprofile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.trainerapp.feature.trainerfinance.model.Pln
import pl.trainerapp.feature.trainerprofile.model.ProfileDraft
import pl.trainerapp.feature.trainerprofile.model.SettingKey
import pl.trainerapp.feature.trainerprofile.model.TrainerSettings

class TrainerProfileViewModel(private val repository: TrainerProfileRepository) : ViewModel() {

    private val _state = MutableStateFlow(TrainerProfileUiState())
    val state: StateFlow<TrainerProfileUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun onAction(action: TrainerProfileAction) {
        when (action) {
            TrainerProfileAction.Refresh -> refresh()
            is TrainerProfileAction.SelectTab -> _state.update { it.copy(tab = action.tab) }
            is TrainerProfileAction.EditField -> editField(action)
            TrainerProfileAction.SaveProfile -> saveProfile()
            is TrainerProfileAction.SetSetting -> setSetting(action.key, action.enabled)
            is TrainerProfileAction.SetDayEnabled -> setDayEnabled(action.index, action.enabled)
        }
    }

    private fun refresh() {
        val hadData = _state.value.profile != null
        _state.update {
            if (hadData) it.copy(isRefreshing = true) else it.copy(status = ProfileLoadStatus.Loading)
        }
        viewModelScope.launch {
            try {
                val snapshot = repository.load()
                _state.update {
                    it.copy(
                        status = ProfileLoadStatus.Content,
                        profile = snapshot.profile,
                        // Unsaved edits survive a refresh.
                        draft = if (it.isDirty) it.draft else ProfileDraft.of(snapshot.profile),
                        settings = snapshot.settings,
                        availability = snapshot.availability,
                        payout = snapshot.payout,
                        isOffline = snapshot.isOffline,
                        lastUpdated = snapshot.lastUpdated,
                        isRefreshing = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (it.profile != null) {
                        it.copy(isOffline = true, isRefreshing = false)
                    } else {
                        it.copy(status = ProfileLoadStatus.Error, isRefreshing = false)
                    }
                }
            }
        }
    }

    private fun editField(action: TrainerProfileAction.EditField) {
        _state.update { state ->
            val draft = state.draft ?: return@update state
            val value = action.value
            state.copy(
                draft = when (action.field) {
                    ProfileField.Name -> draft.copy(name = value)
                    ProfileField.Phone -> draft.copy(phone = value)
                    ProfileField.Location -> draft.copy(location = value)
                    ProfileField.Bio -> draft.copy(bio = value)
                    ProfileField.Rate -> draft.copy(rateZl = value.filter { it.isDigit() }.take(5))
                },
                justSaved = false,
                saveFailed = false,
            )
        }
    }

    private fun saveProfile() {
        val state = _state.value
        val profile = state.profile ?: return
        val draft = state.draft ?: return
        val rate = draft.rate
        if (!state.canSave || rate == null) return
        val updated = profile.copy(
            name = draft.name.trim(),
            phone = draft.phone.trim(),
            location = draft.location.trim(),
            bio = draft.bio.trim(),
            hourlyRate = Pln.zl(rate),
        )
        _state.update { it.copy(isSaving = true, saveFailed = false, justSaved = false) }
        viewModelScope.launch {
            try {
                repository.saveProfile(updated)
                _state.update {
                    it.copy(profile = updated, draft = ProfileDraft.of(updated), isSaving = false, justSaved = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The form keeps the edits so the trainer can retry.
                _state.update { it.copy(isSaving = false, saveFailed = true) }
            }
        }
    }

    private fun setSetting(key: SettingKey, enabled: Boolean) {
        val previous = _state.value.settings ?: return
        val updated = previous.with(key, enabled)
        _state.update { it.copy(settings = updated) }
        viewModelScope.launch {
            try {
                repository.saveSettings(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Roll the switch back so the screen never shows a setting that was not stored.
                _state.update { it.copy(settings = previous) }
            }
        }
    }

    private fun setDayEnabled(index: Int, enabled: Boolean) {
        val previous = _state.value.availability
        if (index !in previous.indices) return
        val updated = previous.mapIndexed { i, day -> if (i == index) day.copy(enabled = enabled) else day }
        _state.update { it.copy(availability = updated) }
        viewModelScope.launch {
            try {
                repository.saveAvailability(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(availability = previous) }
            }
        }
    }
}

private fun TrainerSettings.with(key: SettingKey, enabled: Boolean) = when (key) {
    SettingKey.NotifyMessages -> copy(notifyMessages = enabled)
    SettingKey.NotifySessions -> copy(notifySessions = enabled)
    SettingKey.NotifyPayments -> copy(notifyPayments = enabled)
    SettingKey.EmailSummary -> copy(emailSummary = enabled)
    SettingKey.AutoConfirm -> copy(autoConfirm = enabled)
}
