package com.trainingapp.feature.client

import androidx.lifecycle.ViewModelStore
import com.trainingapp.contract.money.Money
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.feature.client.presentation.ClientScheduleViewModel
import com.trainingapp.feature.client.presentation.ClientShellViewModel
import com.trainingapp.feature.client.presentation.ClientTab
import com.trainingapp.feature.client.presentation.ScheduleMessage
import com.trainingapp.feature.client.presentation.WalletViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import pl.trainer.data.ClientIdentity
import pl.trainer.data.DemoClientScheduleRepository
import pl.trainer.data.DemoSchedulingStore
import pl.trainer.data.DemoServerNow
import pl.trainer.data.DemoTrainerId
import pl.trainer.data.DemoTrainerRepository
import pl.trainer.data.DemoWalletRepository
import pl.trainer.domain.ChargeState
import pl.trainer.domain.ClientSessionState
import pl.trainer.domain.Failure
import pl.trainer.domain.PaymentState
import pl.trainer.domain.SessionStatus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan §9: cancellation, wallet and shell behaviour of the client view models over the shared demo store. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClientViewModelsTest {
    private val stores = mutableListOf<ViewModelStore>()

    @AfterTest
    fun after() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private class Online(value: Boolean = true) : NetworkMonitor {
        override val isOnline = MutableStateFlow(value)
    }

    private inner class Fixture(scope: TestScope) {
        val network = Online()
        val store = DemoSchedulingStore({ DemoServerNow }, scope.backgroundScope, paymentConfirmationMillis = 1_000)
        val user = ClientIdentity("demo-client", "Katarzyna Nowak")
        val schedule = DemoClientScheduleRepository(store, { user }, { network.isOnline.value }, latencyMillis = 0)
        val walletRepository = DemoWalletRepository(store, { user }, { network.isOnline.value }, latencyMillis = 0)
        val trainer = DemoTrainerRepository(0, store = store)
        private var counter = 0

        fun scheduleModel() = ClientScheduleViewModel(schedule, network, tickMillis = 10).also { keep(it) }
        fun walletModel() = WalletViewModel(walletRepository, network, IdGenerator { "key-${counter++}" }).also { keep(it) }

        private fun keep(model: androidx.lifecycle.ViewModel) {
            stores += ViewModelStore().also { it.put("m", model) }
        }
    }

    private fun TestScope.fixture(): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return Fixture(this)
    }

    @Test
    fun aPendingGuestIntentOpensTheBookingSheetAndNeverSendsARequest() = runTest {
        val f = fixture()
        val shell = ClientShellViewModel(pendingIntent = true, pendingTrainerId = "tr-michal")
        val model = f.scheduleModel()
        advanceUntilIdle()
        val before = f.trainer.load().sessions.size
        val (open, trainerId) = shell.consumeIntent(hasTrainer = model.state.value.hasTrainer)
        assertTrue(open)
        assertEquals(ClientTab.Calendar, shell.state.value.tab)
        model.openBooking(trainerId)
        advanceUntilIdle()
        val sheet = assertNotNull(model.state.value.booking)
        assertEquals(DemoTrainerId, sheet.options?.trainer?.id)
        assertFalse(sheet.policyAccepted)
        assertEquals(before, f.trainer.load().sessions.size)
        // The intent is handed over once.
        assertFalse(shell.consumeIntent(hasTrainer = true).first)
    }

    @Test
    fun aGymOnlyIntentWithoutATrainerStaysOnStart() {
        val shell = ClientShellViewModel(pendingIntent = true, pendingTrainerId = null)
        assertFalse(shell.consumeIntent(hasTrainer = false).first)
        assertEquals(ClientTab.Start, shell.state.value.tab)
        assertFalse(shell.state.value.hasPendingIntent)
    }

    @Test
    fun aBookingRequestNeedsAnExplicitPolicyConsent() = runTest {
        val f = fixture()
        val model = f.scheduleModel()
        advanceUntilIdle()
        model.openBooking()
        advanceUntilIdle()
        val slot = model.state.value.booking!!.slots.first()
        model.bookingSlot(slot)
        assertFalse(model.state.value.booking!!.canSend)
        model.sendBooking()
        advanceUntilIdle()
        assertNotNull(model.state.value.booking)
        model.bookingPolicyAccepted(true)
        model.sendBooking()
        model.sendBooking()
        advanceUntilIdle()
        assertNull(model.state.value.booking)
        assertEquals(ScheduleMessage.RequestSent, model.state.value.message)
        val requests = f.trainer.load().sessions.filter { it.status == SessionStatus.Requested && it.start == slot.start }
        assertEquals(1, requests.size)
        assertEquals(ClientSessionState.AwaitingTrainer, model.state.value.session(requests.single().id)?.state)
    }

    @Test
    fun anExpiredQuoteMustBeRefreshedBeforeConfirming() = runTest {
        val f = fixture()
        val model = f.scheduleModel()
        advanceUntilIdle()
        val session = model.state.value.session("s13")!!
        model.openCancellation(session)
        runCurrent()
        val sheet = model.state.value.cancellation!!
        assertEquals(Money.pln(60), sheet.quote?.fee?.amount)
        assertTrue(sheet.quoteUsable)
        advanceTimeBy(10L * 301)
        runCurrent()
        assertFalse(model.state.value.cancellation!!.quoteUsable)
        model.confirmCancellation()
        advanceUntilIdle()
        assertEquals(ClientSessionState.Confirmed, model.state.value.session("s13")?.state)
        model.refreshQuote()
        runCurrent()
        assertTrue(model.state.value.cancellation!!.quoteUsable)
        model.confirmCancellation()
        model.confirmCancellation()
        runCurrent()
        advanceTimeBy(5)
        runCurrent()
        assertNull(model.state.value.cancellation)
        assertEquals(ScheduleMessage.SessionCancelled, model.state.value.message)
        assertEquals(ClientSessionState.Cancelled, model.state.value.session("s13")?.state)
        assertEquals(1, f.walletRepository.load().charges.count { it.sessionId == "s13" })
    }

    @Test
    fun aSessionChangedByTheTrainerMakesTheQuoteStale() = runTest {
        val f = fixture()
        val model = f.scheduleModel()
        advanceUntilIdle()
        model.openCancellation(model.state.value.session("s13")!!)
        runCurrent()
        val session = f.trainer.load().sessions.first { it.id == "s13" }
        f.trainer.cancelSession(session.id, session.revision)
        runCurrent()
        assertTrue(model.state.value.cancellation!!.stale)
        assertFalse(model.state.value.cancellation!!.quoteUsable)
    }

    @Test
    fun anOfflineCancellationIsUnsentAndKeepsTheSheet() = runTest {
        val f = fixture()
        val model = f.scheduleModel()
        advanceUntilIdle()
        model.openCancellation(model.state.value.session("s13")!!)
        runCurrent()
        f.network.isOnline.value = false
        model.confirmCancellation()
        runCurrent()
        assertEquals(Failure.Offline, model.state.value.cancellation?.failure)
        assertTrue(model.state.value.offline)
        assertEquals(SessionStatus.Confirmed, f.trainer.load().sessions.first { it.id == "s13" }.status)
    }

    @Test
    fun answeringTheTrainersProposalMovesTheSessionAfterTheServerAccepts() = runTest {
        val f = fixture()
        val model = f.scheduleModel()
        advanceUntilIdle()
        val session = model.state.value.session("s8")!!
        assertTrue(session in model.state.value.attention)
        model.answerProposal(session, accept = true)
        advanceUntilIdle()
        val moved = model.state.value.session("s8")!!
        assertEquals(session.session.proposal!!.start, moved.session.start)
        assertFalse(moved in model.state.value.attention)
    }

    @Test
    fun payingStaysPendingUntilTheProviderConfirms() = runTest {
        val f = fixture()
        val model = f.walletModel()
        runCurrent()
        val charge = model.state.value.outstanding.first { it.payableInApp }
        model.openPayment(charge)
        model.pay()
        model.pay()
        runCurrent()
        assertTrue(model.state.value.paymentSubmitted)
        assertEquals(ChargeState.PaymentPending, model.state.value.wallet!!.charges.first { it.id == charge.id }.state)
        assertEquals(1, model.state.value.wallet!!.payments.count { it.chargeId == charge.id })
        advanceTimeBy(1_001)
        runCurrent()
        val wallet = model.state.value.wallet!!
        assertEquals(PaymentState.Paid, wallet.payments.first { it.chargeId == charge.id }.state)
        assertTrue(model.state.value.outstanding.isEmpty())
    }

    @Test
    fun anOfflinePaymentIsNotSent() = runTest {
        val f = fixture()
        val model = f.walletModel()
        runCurrent()
        val charge = model.state.value.outstanding.first()
        model.openPayment(charge)
        f.network.isOnline.value = false
        model.pay()
        runCurrent()
        assertEquals(Failure.Offline, model.state.value.payment?.failure)
        assertEquals(ChargeState.Unpaid, f.walletRepository.load().charges.first { it.id == charge.id }.state)
    }
}
