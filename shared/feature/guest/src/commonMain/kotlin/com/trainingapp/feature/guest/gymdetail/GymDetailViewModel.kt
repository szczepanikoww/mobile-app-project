package com.trainingapp.feature.guest.gymdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.feature.guest.model.GuestCatalogRepository
import com.trainingapp.feature.guest.model.GymDetail
import com.trainingapp.feature.guest.model.isOpenAt
import com.trainingapp.feature.guest.trainerprofile.DetailContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant

data class GymDetailUiState(
    val content: DetailContent<GymDetail> = DetailContent.Loading,
    /** Computed from the gym's structured hours in its own time zone with the injected clock. */
    val openNow: Boolean? = null,
    val loginSheetVisible: Boolean = false,
    val offline: Boolean = false,
)

class GymDetailViewModel(
    private val gymId: String,
    private val repository: GuestCatalogRepository,
    network: NetworkMonitor,
    private val time: TimeSource,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GymDetailUiState(offline = !network.isOnline.value))
    val state: StateFlow<GymDetailUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { network.isOnline.collect { online -> mutableState.update { it.copy(offline = !online) } } }
        load()
    }

    fun retry() = load()

    private fun load() {
        mutableState.update { if (it.content is DetailContent.Ready) it else it.copy(content = DetailContent.Loading) }
        viewModelScope.launch {
            val next = try {
                repository.gym(gymId)?.let { DetailContent.Ready(it) } ?: DetailContent.NotFound
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            mutableState.update { current ->
                val content = next ?: if (current.content is DetailContent.Ready) current.content else DetailContent.Failed
                val now = Instant.fromEpochMilliseconds(time.nowEpochMs())
                current.copy(content = content, openNow = (content as? DetailContent.Ready)?.data?.isOpenAt(now))
            }
        }
    }

    fun requestSession() = mutableState.update { it.copy(loginSheetVisible = true) }

    fun dismissLoginSheet() = mutableState.update { it.copy(loginSheetVisible = false) }
}
