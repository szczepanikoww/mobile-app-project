package com.trainingapp.feature.client

import androidx.lifecycle.ViewModelStore
import com.trainingapp.contract.money.Money
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.feature.client.presentation.WalletViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import pl.trainer.domain.Charge
import pl.trainer.domain.ChargeKind
import pl.trainer.domain.ChargeReason
import pl.trainer.domain.ChargeState
import pl.trainer.domain.CheckoutResult
import pl.trainer.domain.Payment
import pl.trainer.domain.PaymentMethod
import pl.trainer.domain.PaymentStart
import pl.trainer.domain.PaymentState
import pl.trainer.domain.ProviderCheckout
import pl.trainer.domain.TrainerRef
import pl.trainer.domain.Wallet
import pl.trainer.domain.WalletRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The wallet with a provider that confirms payments in its own sheet (Stripe): synthetic data, no network. */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletCheckoutTest {
    private val stores = mutableListOf<ViewModelStore>()

    @AfterTest
    fun after() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    /** One online charge; a started payment needs the provider's sheet, like the server with PAYMENT_PROVIDER=stripe. */
    private class StripeLikeWallet : WalletRepository {
        private val trainer = TrainerRef("t1", "Trener Testowy", "TT")
        val updated = MutableStateFlow(0L)
        override val updates get() = updated
        var chargeState = ChargeState.Unpaid
        val payments = mutableListOf<Payment>()
        val keys = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        /** What the server answers to "closed": FAILED, or PENDING when the provider is already processing it. */
        var cancelAnswer = PaymentState.Failed

        private val charge get() = Charge(
            "c1", ChargeReason.LateCancellation, null, null, trainer, Money(6000, "PLN"), ChargeKind.Online, chargeState, Instant.fromEpochSeconds(0),
        )

        override suspend fun load() = Wallet(
            packages = emptyList(), charges = listOf(charge), payments = payments.toList(), serverNow = Instant.fromEpochSeconds(0),
            paymentMethods = listOf(PaymentMethod.Card, PaymentMethod.Blik, PaymentMethod.Przelewy24),
        )

        override suspend fun pay(chargeId: String, method: PaymentMethod, idempotencyKey: String) =
            startPayment(chargeId, method, idempotencyKey).payment

        override suspend fun startPayment(chargeId: String, method: PaymentMethod, idempotencyKey: String): PaymentStart {
            keys += idempotencyKey
            val payment = Payment("p${payments.size}", chargeId, ChargeReason.LateCancellation, trainer, Money(6000, "PLN"), method, PaymentState.Pending, Instant.fromEpochSeconds(0))
            payments += payment
            chargeState = ChargeState.PaymentPending
            updated.update { it + 1 }
            return PaymentStart(payment, ProviderCheckout(payment.id, "pi_test_secret", "pk_test_unit"))
        }

        override suspend fun cancelPayment(paymentId: String): Payment {
            cancelled += paymentId
            val i = payments.indexOfFirst { it.id == paymentId }
            payments[i] = payments[i].copy(state = cancelAnswer)
            if (cancelAnswer == PaymentState.Failed) chargeState = ChargeState.Unpaid
            updated.update { it + 1 }
            return payments[i]
        }
    }

    private class Online : NetworkMonitor {
        override val isOnline = MutableStateFlow(true)
    }

    private fun TestScope.model(wallet: StripeLikeWallet): WalletViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var counter = 0
        return WalletViewModel(wallet, Online(), IdGenerator { "key-${counter++}" }).also { model ->
            stores += ViewModelStore().also { it.put("m", model) }
        }
    }

    private fun TestScope.startCheckout(wallet: StripeLikeWallet): WalletViewModel {
        val model = model(wallet)
        runCurrent()
        model.openPayment(model.state.value.outstanding.single())
        model.paymentMethod(PaymentMethod.Card)
        model.pay()
        runCurrent()
        return model
    }

    @Test
    fun theSheetOffersOnlyTheProvidersMethods() = runTest {
        val model = model(StripeLikeWallet())
        runCurrent()
        assertEquals(listOf(PaymentMethod.Card, PaymentMethod.Blik, PaymentMethod.Przelewy24), model.state.value.paymentMethods)
        model.openPayment(model.state.value.outstanding.single())
        assertEquals(PaymentMethod.Blik, model.state.value.payment?.method)
    }

    @Test
    fun aStartedPaymentWaitsForTheProvidersSheetAndKeepsTheAppSheetOpen() = runTest {
        val wallet = StripeLikeWallet()
        val model = startCheckout(wallet)
        val state = model.state.value
        assertNotNull(state.checkout)
        assertEquals("p0", state.checkout!!.checkout.paymentId)
        assertFalse(state.checkout!!.presented)
        assertTrue(state.submitting) // no second "Pay" while Stripe's sheet is open
        assertNotNull(state.payment)  // the charge is pending, but the app's sheet stays for the result
        model.pay()
        runCurrent()
        assertEquals(1, wallet.keys.size)

        model.checkoutPresented()
        assertTrue(model.state.value.checkout!!.presented)
    }

    @Test
    fun completedIsPendingUntilTheServerConfirms() = runTest {
        val wallet = StripeLikeWallet()
        val model = startCheckout(wallet)
        model.onCheckoutResult(CheckoutResult.Completed)
        runCurrent()
        val state = model.state.value
        assertNull(state.checkout)
        assertNull(state.payment)
        assertFalse(state.submitting)
        assertTrue(state.paymentSubmitted)
        assertEquals(PaymentState.Pending, state.wallet!!.payments.single().state)
        assertTrue(wallet.cancelled.isEmpty())
    }

    @Test
    fun aClosedSheetCancelsThePaymentAndAllowsANewAttemptWithANewKey() = runTest {
        val wallet = StripeLikeWallet()
        val model = startCheckout(wallet)
        val firstKey = model.state.value.payment!!.idempotencyKey
        model.onCheckoutResult(CheckoutResult.Canceled)
        runCurrent()
        assertEquals(listOf("p0"), wallet.cancelled)
        val state = model.state.value
        assertNull(state.checkout)
        assertFalse(state.submitting)
        assertFalse(state.paymentSubmitted)
        assertNotNull(state.payment)
        assertFalse(state.payment!!.checkoutFailed)
        assertNotEquals(firstKey, state.payment!!.idempotencyKey)
        assertEquals(ChargeState.Unpaid, state.wallet!!.charges.single().state)

        model.pay()
        runCurrent()
        assertEquals(listOf(firstKey, state.payment!!.idempotencyKey), wallet.keys)
    }

    @Test
    fun aFailedSheetShowsThatNothingWasCharged() = runTest {
        val wallet = StripeLikeWallet()
        val model = startCheckout(wallet)
        model.onCheckoutResult(CheckoutResult.Failed("unit"))
        runCurrent()
        assertTrue(model.state.value.payment!!.checkoutFailed)
        assertEquals(listOf("p0"), wallet.cancelled)
    }

    @Test
    fun aBookingsPaymentOpensForItsChargeAndAClosedStripeSheetReportsNothingCharged() = runTest {
        val wallet = StripeLikeWallet()
        val model = model(wallet)
        runCurrent()
        model.payCharge("c1", bookingSessionId = "s1")
        runCurrent()
        assertEquals("s1", model.state.value.payment?.bookingSessionId)
        model.pay()
        runCurrent()
        model.onCheckoutResult(CheckoutResult.Canceled)
        runCurrent()
        val state = model.state.value
        assertNull(state.payment) // the server withdraws the request; booking again starts over
        assertTrue(state.bookingNotPaid)
        assertEquals(listOf("p0"), wallet.cancelled)
    }

    @Test
    fun aCompletedBookingPaymentSaysTheTrainerGetsTheRequestAfterConfirmation() = runTest {
        val model = model(StripeLikeWallet())
        runCurrent()
        model.payCharge("c1", bookingSessionId = "s1")
        runCurrent()
        model.pay()
        runCurrent()
        model.onCheckoutResult(CheckoutResult.Completed)
        assertTrue(model.state.value.paymentSubmitted)
        assertTrue(model.state.value.bookingPaymentSubmitted)
    }

    @Test
    fun closingABookingsPaymentBeforePayingWithdrawsTheRequest() = runTest {
        val model = model(StripeLikeWallet())
        runCurrent()
        model.payCharge("c1", bookingSessionId = "s1")
        runCurrent()
        model.closePayment()
        assertEquals("s1", model.state.value.abandonedBooking)
        assertTrue(model.state.value.bookingNotPaid)
        model.consumeAbandonedBooking()
        assertNull(model.state.value.abandonedBooking)
    }

    @Test
    fun aPaymentTheProviderIsAlreadyProcessingStaysPending() = runTest {
        val wallet = StripeLikeWallet().apply { cancelAnswer = PaymentState.Pending }
        val model = startCheckout(wallet)
        model.onCheckoutResult(CheckoutResult.Canceled)
        runCurrent()
        val state = model.state.value
        assertNull(state.payment)
        assertTrue(state.paymentSubmitted)
        assertEquals(ChargeState.PaymentPending, state.wallet!!.charges.single().state)
    }
}
