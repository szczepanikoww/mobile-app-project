package com.trainingapp.feature.client.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.common.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.trainer.domain.Charge
import pl.trainer.domain.ChargeState
import pl.trainer.domain.Failure
import pl.trainer.domain.PaymentMethod
import pl.trainer.domain.Wallet
import pl.trainer.domain.WalletRepository

data class PaymentSheetState(
    val charge: Charge,
    val method: PaymentMethod = PaymentMethod.Blik,
    /** One key per sheet: retrying the same payment never pays twice. */
    val idempotencyKey: String,
    val failure: Failure? = null,
)

data class WalletUiState(
    val loading: Boolean = true,
    val wallet: Wallet? = null,
    val loadFailure: Failure? = null,
    val offline: Boolean = false,
    val submitting: Boolean = false,
    val payment: PaymentSheetState? = null,
    /** Set after a payment was submitted; it stays "pending" until the server confirms it. */
    val paymentSubmitted: Boolean = false,
) {
    val outstanding: List<Charge> get() = wallet?.outstanding.orEmpty()
}

/** B3 "Portfel": packages, charges to pay and the payment history. */
class WalletViewModel(
    private val repository: WalletRepository,
    network: NetworkMonitor,
    private val ids: IdGenerator = IdGenerator.Uuids,
) : ViewModel() {
    private val mutableState = MutableStateFlow(WalletUiState(offline = !network.isOnline.value))
    val state: StateFlow<WalletUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch { network.isOnline.collect { online -> mutableState.update { it.copy(offline = !online) } } }
        // The provider's confirmation (and charges created by cancellations) arrive as store updates.
        viewModelScope.launch { repository.updates.drop(1).collect { refresh(silent = true) } }
        refresh(silent = false)
    }

    fun reload() = refresh(silent = false)

    private fun refresh(silent: Boolean) {
        loadJob?.cancel()
        if (!silent) mutableState.update { it.copy(loading = it.wallet == null, loadFailure = null) }
        loadJob = viewModelScope.launch {
            try {
                val wallet = repository.load()
                mutableState.update { state ->
                    // A charge settled elsewhere closes its payment sheet.
                    val sheet = state.payment?.takeIf { sheet -> wallet.charges.any { it.id == sheet.charge.id && it.state == ChargeState.Unpaid } }
                    state.copy(wallet = wallet, loading = false, loadFailure = null, payment = sheet)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(loading = false, loadFailure = if (it.wallet == null) e.failure() else null) }
            }
        }
    }

    fun openPayment(charge: Charge) {
        if (!charge.payableInApp) return
        mutableState.update { it.copy(payment = PaymentSheetState(charge, idempotencyKey = ids.newId()), paymentSubmitted = false) }
    }

    fun paymentMethod(method: PaymentMethod) = mutableState.update { it.copy(payment = it.payment?.copy(method = method, failure = null)) }

    fun pay() {
        val sheet = state.value.payment ?: return
        if (state.value.submitting) return
        mutableState.update { it.copy(submitting = true, payment = sheet.copy(failure = null)) }
        viewModelScope.launch {
            try {
                repository.pay(sheet.charge.id, sheet.method, sheet.idempotencyKey)
                mutableState.update { it.copy(submitting = false, payment = null, paymentSubmitted = true) }
                refresh(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(submitting = false, payment = it.payment?.copy(failure = e.failure())) }
            }
        }
    }

    fun closePayment() {
        if (state.value.submitting) return
        mutableState.update { it.copy(payment = null) }
    }

    fun dismissSubmitted() = mutableState.update { it.copy(paymentSubmitted = false) }
}
