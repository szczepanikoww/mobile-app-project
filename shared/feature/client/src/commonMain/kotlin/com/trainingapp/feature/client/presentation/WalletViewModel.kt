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
import pl.trainer.domain.CheckoutResult
import pl.trainer.domain.Failure
import pl.trainer.domain.PaymentMethod
import pl.trainer.domain.PaymentState
import pl.trainer.domain.ProviderCheckout
import pl.trainer.domain.Wallet
import pl.trainer.domain.WalletRepository

data class PaymentSheetState(
    val charge: Charge,
    val method: PaymentMethod = PaymentMethod.Blik,
    /** One key per attempt: retrying the same request never pays twice; a new attempt after a closed sheet gets a new key. */
    val idempotencyKey: String,
    val failure: Failure? = null,
    /** The provider's sheet could not take the payment; nothing was charged. */
    val checkoutFailed: Boolean = false,
)

/** A started payment waiting for the provider's own sheet (Stripe). [presented] keeps it from opening twice. */
data class CheckoutState(val checkout: ProviderCheckout, val chargeId: String, val presented: Boolean = false)

data class WalletUiState(
    val loading: Boolean = true,
    val wallet: Wallet? = null,
    val loadFailure: Failure? = null,
    val offline: Boolean = false,
    val submitting: Boolean = false,
    val payment: PaymentSheetState? = null,
    /** Set after a payment was submitted; it stays "pending" until the server confirms it. */
    val paymentSubmitted: Boolean = false,
    /** Set while the provider's sheet should be shown or is open. */
    val checkout: CheckoutState? = null,
) {
    /** The methods to offer: the provider's, in the app's order. */
    val paymentMethods: List<PaymentMethod> get() = wallet?.paymentMethods ?: PaymentMethod.payableInApp

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
                    // A charge settled elsewhere closes its payment sheet; one being confirmed in the provider's sheet keeps it.
                    val sheet = state.payment?.takeIf { sheet ->
                        state.checkout?.chargeId == sheet.charge.id ||
                            wallet.charges.any { it.id == sheet.charge.id && it.state == ChargeState.Unpaid }
                    }
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
        val methods = state.value.paymentMethods
        val method = PaymentMethod.Blik.takeIf { it in methods } ?: methods.firstOrNull() ?: PaymentMethod.Blik
        mutableState.update { it.copy(payment = PaymentSheetState(charge, method, idempotencyKey = ids.newId()), paymentSubmitted = false) }
    }

    fun paymentMethod(method: PaymentMethod) =
        mutableState.update { it.copy(payment = it.payment?.copy(method = method, failure = null, checkoutFailed = false)) }

    fun pay() {
        val sheet = state.value.payment ?: return
        if (state.value.submitting) return
        mutableState.update { it.copy(submitting = true, payment = sheet.copy(failure = null, checkoutFailed = false)) }
        viewModelScope.launch {
            try {
                val start = repository.startPayment(sheet.charge.id, sheet.method, sheet.idempotencyKey)
                val checkout = start.checkout
                if (checkout != null) {
                    // Still submitting: the provider's sheet takes over until it reports back (onCheckoutResult).
                    mutableState.update { it.copy(checkout = CheckoutState(checkout, sheet.charge.id)) }
                    return@launch
                }
                mutableState.update { it.copy(submitting = false, payment = null, paymentSubmitted = true) }
                refresh(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(submitting = false, payment = it.payment?.copy(failure = e.failure())) }
            }
        }
    }

    /** The UI opened the provider's sheet for the current checkout. */
    fun checkoutPresented() = mutableState.update { state -> state.copy(checkout = state.checkout?.copy(presented = true)) }

    /**
     * The provider's sheet finished. Completed: pending until the server has the provider's confirmation. Closed or
     * failed: the server cancels the payment (unless the provider is already processing it) and the client may try
     * again with a new key.
     */
    fun onCheckoutResult(result: CheckoutResult) {
        val current = state.value.checkout ?: return
        if (result == CheckoutResult.Completed) {
            mutableState.update { it.copy(submitting = false, payment = null, checkout = null, paymentSubmitted = true) }
            refresh(silent = true)
            return
        }
        viewModelScope.launch {
            val after = try {
                repository.cancelPayment(current.checkout.paymentId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // the server releases an unfinished intent before the next attempt anyway
            }
            if (after?.state == PaymentState.Pending) {
                // The provider is already processing it (e.g. BLIK confirmed in the bank app): wait for the server.
                mutableState.update { it.copy(submitting = false, payment = null, checkout = null, paymentSubmitted = true) }
            } else {
                mutableState.update {
                    it.copy(
                        submitting = false,
                        checkout = null,
                        payment = it.payment?.copy(idempotencyKey = ids.newId(), checkoutFailed = result is CheckoutResult.Failed),
                    )
                }
            }
            refresh(silent = true)
        }
    }

    fun closePayment() {
        if (state.value.submitting) return
        mutableState.update { it.copy(payment = null) }
    }

    fun dismissSubmitted() = mutableState.update { it.copy(paymentSubmitted = false) }
}
