package com.trainingapp.feature.guest.trainerprofile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.feature.guest.model.GuestCatalogRepository
import com.trainingapp.feature.guest.model.TrainerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A public detail screen's content state. */
sealed interface DetailContent<out T> {
    data object Loading : DetailContent<Nothing>
    data object NotFound : DetailContent<Nothing>
    data object Failed : DetailContent<Nothing>
    data class Ready<T>(val data: T) : DetailContent<T>
}

data class TrainerProfileUiState(
    val content: DetailContent<TrainerProfile> = DetailContent.Loading,
    val loginSheetVisible: Boolean = false,
    val policySheetVisible: Boolean = false,
    val offline: Boolean = false,
)

class TrainerProfileViewModel(
    private val trainerId: String,
    private val repository: GuestCatalogRepository,
    network: NetworkMonitor,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TrainerProfileUiState(offline = !network.isOnline.value))
    val state: StateFlow<TrainerProfileUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { network.isOnline.collect { online -> mutableState.update { it.copy(offline = !online) } } }
        load()
    }

    fun retry() = load()

    private fun load() {
        mutableState.update { if (it.content is DetailContent.Ready) it else it.copy(content = DetailContent.Loading) }
        viewModelScope.launch {
            val next = try {
                repository.trainer(trainerId)?.let { DetailContent.Ready(it) } ?: DetailContent.NotFound
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            mutableState.update { current ->
                current.copy(content = next ?: if (current.content is DetailContent.Ready) current.content else DetailContent.Failed)
            }
        }
    }

    /** The "request a session" CTA: guests must sign in first. The screen behind keeps its state. */
    fun requestSession() = mutableState.update { it.copy(loginSheetVisible = true) }

    fun dismissLoginSheet() = mutableState.update { it.copy(loginSheetVisible = false) }

    fun showPolicy(visible: Boolean) = mutableState.update { it.copy(policySheetVisible = visible) }
}
