package pl.trainerapp.feature.trainerfinance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.trainerapp.feature.trainerfinance.model.FinancePeriod

class TrainerFinanceViewModel(private val repository: TrainerFinanceRepository) : ViewModel() {

    private val _state = MutableStateFlow(TrainerFinanceUiState())
    val state: StateFlow<TrainerFinanceUiState> = _state.asStateFlow()

    private val _events = Channel<TrainerFinanceEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun onAction(action: TrainerFinanceAction) {
        when (action) {
            TrainerFinanceAction.Refresh -> refresh()
            is TrainerFinanceAction.SelectPeriod -> selectPeriod(action.period)
            is TrainerFinanceAction.OpenPayment -> navigate(TrainerFinanceDestination.PaymentDetails(action.payment.id))
            TrainerFinanceAction.OpenAllPayments -> navigate(TrainerFinanceDestination.AllPayments)
            TrainerFinanceAction.RequestPayout -> if (!_state.value.isOffline) {
                navigate(TrainerFinanceDestination.Payout)
            }
        }
    }

    private fun selectPeriod(period: FinancePeriod) {
        if (period == _state.value.period) return
        _state.update { it.copy(period = period) }
        refresh()
    }

    private fun refresh() {
        val period = _state.value.period
        val hadData = _state.value.summary != null
        _state.update {
            if (hadData) it.copy(isRefreshing = true) else it.copy(status = FinanceLoadStatus.Loading)
        }
        // A newer request (e.g. another period tab) supersedes the one in flight.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val snapshot = repository.load(period)
                _state.update {
                    it.copy(
                        status = FinanceLoadStatus.Content,
                        summary = snapshot.summary,
                        isOffline = snapshot.isOffline,
                        lastUpdated = snapshot.lastUpdated,
                        isRefreshing = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing cached content when a refresh fails; otherwise surface a recoverable error.
                _state.update {
                    if (it.summary != null) {
                        it.copy(isOffline = true, isRefreshing = false)
                    } else {
                        it.copy(status = FinanceLoadStatus.Error, isRefreshing = false)
                    }
                }
            }
        }
    }

    private fun navigate(destination: TrainerFinanceDestination) {
        viewModelScope.launch { _events.send(TrainerFinanceEvent.Navigate(destination)) }
    }
}
