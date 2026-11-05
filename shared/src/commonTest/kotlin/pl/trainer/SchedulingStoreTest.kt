package pl.trainer

import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.PolicyPreset
import com.trainingapp.contract.policy.PolicyPresets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import pl.trainer.data.*
import pl.trainer.domain.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The shared demo server seen from both roles (plan §9: scheduling store, cancellation, wallet, policy versions). */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingStoreTest {
    private val kasia = ClientIdentity("demo-client", "Katarzyna Nowak")

    private class Fixture(scope: TestScope, var now: Instant = DemoServerNow, var online: Boolean = true, user: ClientIdentity) {
        val store = DemoSchedulingStore({ now }, scope.backgroundScope, paymentConfirmationMillis = 1_000)
        val trainer = DemoTrainerRepository(0, store = store)
        val client = DemoClientScheduleRepository(store, { user }, { online }, latencyMillis = 0)
        val wallet = DemoWalletRepository(store, { user }, { online }, latencyMillis = 0)
        val policies = DemoPolicyRepository(store, { online }, latencyMillis = 0)
    }

    private fun TestScope.fixture(user: ClientIdentity = kasia) = Fixture(this, user = user)

    private suspend fun Fixture.firstSlot(date: LocalDate = LocalDate(2026, 11, 2)) = client.availableSlots(DemoTrainerId, date).first()

    @Test fun aClientBookingRequestAppearsInTheTrainerCalendarAndAcceptingConfirmsIt() = runTest {
        val f = fixture()
        val options = f.client.bookingOptions(DemoTrainerId)
        val slot = f.firstSlot()
        val sent = f.client.requestBooking(BookingRequest(DemoTrainerId, slot.start, slot.end, options.places.first(), options.policy.versionId))
        assertEquals(ClientSessionState.AwaitingTrainer, sent.state)
        val seenByTrainer = f.trainer.load().sessions.first { it.id == sent.id }
        assertEquals(SessionStatus.Requested, seenByTrainer.status)
        assertEquals(options.policy.versionId, seenByTrainer.policyVersionId)
        f.trainer.acceptRequest(seenByTrainer.id, seenByTrainer.revision)
        assertEquals(ClientSessionState.Confirmed, f.client.load().sessions.first { it.id == sent.id }.state)
    }

    @Test fun aRequestNeedsTheCurrentPolicyVersionAndAFreeSlot() = runTest {
        val f = fixture()
        val options = f.client.bookingOptions(DemoTrainerId)
        val slot = f.firstSlot()
        f.policies.save(DemoTrainerId, PolicyPresets.rules(PolicyPreset.Strict), options.policy.versionId)
        val outdated = assertFailsWith<TrainerException> {
            f.client.requestBooking(BookingRequest(DemoTrainerId, slot.start, slot.end, "Studio Centrum", options.policy.versionId))
        }
        assertEquals(Failure.Conflict, outdated.failure)
        val current = f.client.bookingOptions(DemoTrainerId).policy
        f.client.requestBooking(BookingRequest(DemoTrainerId, slot.start, slot.end, "Studio Centrum", current.versionId))
        assertFalse(f.client.availableSlots(DemoTrainerId, LocalDate(2026, 11, 2)).any { it.start == slot.start })
    }

    @Test fun weekendsHaveNoDemoAvailability() = runTest {
        assertTrue(fixture().client.availableSlots(DemoTrainerId, LocalDate(2026, 10, 31)).isEmpty())
    }

    @Test fun aNewClientWithoutTrainersStartsEmptyAndABookingStartsTheRelationship() = runTest {
        val f = fixture(ClientIdentity("user-new", "Jan Kowalski"))
        assertTrue(f.client.load().trainers.isEmpty())
        val options = f.client.bookingOptions("tr-michal")
        assertEquals(DemoTrainerId, options.trainer.id)
        val slot = f.firstSlot()
        val sent = f.client.requestBooking(BookingRequest("tr-michal", slot.start, slot.end, "Studio Centrum", options.policy.versionId))
        assertEquals(listOf(DemoTrainerId), f.client.load().trainers.map { it.id })
        val dashboard = f.trainer.load()
        assertEquals("Jan Kowalski", dashboard.client(dashboard.sessions.first { it.id == sent.id }.clientId).name)
    }

    @Test fun aCatalogueTrainerIsRegisteredWithTheirPublicPolicy() = runTest {
        val store = DemoSchedulingStore()
        val client = DemoClientScheduleRepository(store, { kasia }, catalog = { id ->
            CatalogTrainer(id, "Anna Wiśniewska", "AW", Money.pln(140), listOf("Siłownia Mokotów"), freeCancellationHours = 12)
        }, latencyMillis = 0)
        val options = client.bookingOptions("tr-anna")
        assertEquals(Money.pln(140), options.price)
        assertEquals(12, options.policy.freeUntilHours)
    }

    @Test fun aRescheduleMovesTheSessionOnlyAfterAcceptance() = runTest {
        val f = fixture()
        val session = f.client.load().sessions.first { it.id == "s13" }
        val slot = f.firstSlot()
        val pending = f.client.requestReschedule(RescheduleRequest(session.id, session.revision, slot.start, slot.end))
        assertEquals(ClientSessionState.RescheduleRequested, pending.state)
        assertEquals(session.session.start, pending.session.start)
        val review = f.trainer.requestReview(session.id)
        assertEquals(RequestKind.Reschedule, review.kind)
        assertTrue(review.slotAvailable)
        f.trainer.acceptRequest(session.id, pending.revision)
        val moved = f.client.load().sessions.first { it.id == session.id }
        assertEquals(slot.start, moved.session.start)
        assertEquals(ClientSessionState.Confirmed, moved.state)
    }

    @Test fun rejectingARescheduleKeepsTheOriginalTime() = runTest {
        val f = fixture()
        val session = f.client.load().sessions.first { it.id == "s13" }
        val slot = f.firstSlot()
        val pending = f.client.requestReschedule(RescheduleRequest(session.id, session.revision, slot.start, slot.end))
        f.trainer.rejectRequest(session.id, pending.revision)
        val after = f.client.load().sessions.first { it.id == session.id }
        assertEquals(session.session.start, after.session.start)
        assertNull(after.session.proposal)
        assertEquals(ClientSessionState.Confirmed, after.state)
    }

    @Test fun aStaleRevisionIsAConflict() = runTest {
        val f = fixture()
        val session = f.client.load().sessions.first { it.id == "s8" }
        f.trainer.cancelSession(session.id, session.revision)
        val failure = assertFailsWith<TrainerException> { f.client.answerProposal(session.id, session.revision, accept = true) }
        assertEquals(Failure.Conflict, failure.failure)
    }

    @Test fun acceptingTheTrainersProposalMovesTheSession() = runTest {
        val f = fixture()
        val session = f.client.load().sessions.first { it.id == "s8" }
        assertEquals(ClientSessionState.ProposalFromTrainer, session.state)
        val moved = f.client.answerProposal(session.id, session.revision, accept = true)
        assertEquals(session.session.proposal!!.start, moved.session.start)
        assertNull(moved.session.proposal)
    }

    @Test fun withdrawingARequestCostsNothing() = runTest {
        val f = fixture()
        val request = f.client.load().sessions.first { it.id == "s9" }
        val before = f.wallet.load().charges.size
        val withdrawn = f.client.withdrawRequest(request.id, request.revision)
        assertEquals(ClientSessionState.Cancelled, withdrawn.state)
        assertEquals(before, f.wallet.load().charges.size)
        assertTrue(f.trainer.load().sessions.first { it.id == "s9" }.isClosedWithoutTraining())
    }

    @Test fun anUnansweredRequestExpiresAtItsStart() = runTest {
        val f = fixture()
        f.now = Instant.parse("2026-11-05T18:30:00Z")
        assertEquals(ClientSessionState.Expired, f.client.load().sessions.first { it.id == "s9" }.state)
    }

    @Test fun aLateCancellationIsQuotedByTheServerAndChargedOnline() = runTest {
        val f = fixture()
        val session = f.client.load().sessions.first { it.id == "s13" }
        val quote = f.client.quoteCancellation(session.id, session.revision)
        // 21 h 40 min before the start, policy v1: 50% of 120 zł, the month's free late cancellation is used up.
        assertEquals(Money.pln(60), quote.fee.amount)
        assertEquals(0, quote.freeLateCancellationsLeft)
        assertEquals(ChargeKind.Online, quote.chargeKind)
        val cancelled = f.client.cancel(session.id, session.revision, quote.quoteId)
        assertEquals(ClientSessionState.Cancelled, cancelled.state)
        val charge = f.wallet.load().charges.first { it.sessionId == session.id }
        assertEquals(Money.pln(60), charge.amount)
        assertTrue(charge.payableInApp)
        assertEquals(SessionStatus.CancelledByClient, f.trainer.load().sessions.first { it.id == session.id }.status)
    }

    @Test fun aPackageSessionIsChargedAsAShareOfAnEntry() = runTest {
        val f = fixture()
        f.now = Instant.parse("2026-11-02T12:00:00Z")
        val session = f.client.load().sessions.first { it.id == "s8" }
        // A new month: one free late cancellation is available, and it covers the fee.
        val free = f.client.quoteCancellation(session.id, session.revision)
        assertTrue(free.fee.usesFreeCancellation)
        assertTrue(free.fee.amount.isZero)
        f.client.cancel(session.id, session.revision, free.quoteId)
        assertTrue(f.wallet.load().charges.none { it.sessionId == session.id })
    }

    @Test fun anExpiredQuoteOrAChangedSessionNeedsAFreshQuote() = runTest {
        val f = fixture()
        val session = f.client.load().sessions.first { it.id == "s13" }
        val quote = f.client.quoteCancellation(session.id, session.revision)
        f.now += 6.minutes
        assertEquals(Failure.QuoteExpired, assertFailsWith<TrainerException> { f.client.cancel(session.id, session.revision, quote.quoteId) }.failure)
        val fresh = f.client.quoteCancellation(session.id, session.revision)
        val moved = f.trainer.proposeTime(session.id, session.revision,
            RescheduleProposal(Instant.parse("2026-11-02T08:00:00Z"), Instant.parse("2026-11-02T09:00:00Z"), ProposalAuthor.Trainer))
        val newRevision = moved.sessions.first { it.id == session.id }.revision
        assertEquals(Failure.QuoteExpired, assertFailsWith<TrainerException> { f.client.cancel(session.id, newRevision, fresh.quoteId) }.failure)
        assertEquals(SessionStatus.Confirmed, f.trainer.load().sessions.first { it.id == session.id }.status)
    }

    @Test fun aDoubleTapCancelsOnceAndChargesOnce() = runTest {
        val f = fixture()
        val session = f.client.load().sessions.first { it.id == "s13" }
        val quote = f.client.quoteCancellation(session.id, session.revision)
        val results = List(2) { async { runCatching { f.client.cancel(session.id, session.revision, quote.quoteId) } } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, f.wallet.load().charges.count { it.sessionId == session.id })
    }

    @Test fun offlineCancellationIsUnsentAndChangesNothing() = runTest {
        val f = fixture()
        val session = f.client.load().sessions.first { it.id == "s13" }
        val quote = f.client.quoteCancellation(session.id, session.revision)
        f.online = false
        assertEquals(Failure.Offline, assertFailsWith<TrainerException> { f.client.cancel(session.id, session.revision, quote.quoteId) }.failure)
        assertEquals(ClientSessionState.Confirmed, f.client.load().sessions.first { it.id == session.id }.state)
    }

    @Test fun trainerCancellationNeverChargesTheClient() = runTest {
        val f = fixture()
        val session = f.trainer.load().sessions.first { it.id == "s13" }
        val before = f.wallet.load().charges.size
        f.trainer.cancelSession(session.id, session.revision)
        assertEquals(before, f.wallet.load().charges.size)
    }

    @Test fun payingMovesFromPendingToPaidOnlyAfterTheProviderConfirms() = runTest {
        val f = fixture()
        val charge = f.wallet.load().charges.first { it.payableInApp }
        val payment = f.wallet.pay(charge.id, PaymentMethod.Blik, "key-1")
        assertEquals(PaymentState.Pending, payment.state)
        assertEquals(ChargeState.PaymentPending, f.wallet.load().charges.first { it.id == charge.id }.state)
        advanceTimeBy(1_001)
        runCurrent()
        val wallet = f.wallet.load()
        assertEquals(PaymentState.Paid, wallet.payments.first { it.id == payment.id }.state)
        assertEquals(ChargeState.Settled, wallet.charges.first { it.id == charge.id }.state)
        assertTrue(wallet.outstanding.isEmpty())
    }

    @Test fun aDoubleTapPaysOnce() = runTest {
        val f = fixture()
        val charge = f.wallet.load().charges.first { it.payableInApp }
        val first = f.wallet.pay(charge.id, PaymentMethod.Card, "key-1")
        val second = f.wallet.pay(charge.id, PaymentMethod.Card, "key-1")
        assertEquals(first.id, second.id)
        assertEquals(1, f.wallet.load().payments.count { it.chargeId == charge.id })
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> { f.wallet.pay(charge.id, PaymentMethod.Card, "key-2") }.failure)
    }

    @Test fun cashChargesAreNotPayableInTheApp() = runTest {
        val f = fixture()
        val session = f.trainer.load().sessions.first { it.id == "s3" }
        f.now = session.start + 20.minutes
        f.trainer.markNoShow(session.id, session.revision)
        val store = f.store
        val wallet = DemoWalletRepository(store, { ClientIdentity("user-c4", "Tomasz") }, latencyMillis = 0)
        store.clientIdsByUser["user-c4"] = "c4"
        val charge = wallet.load().charges.single()
        assertEquals(ChargeKind.Cash, charge.kind)
        assertEquals(Money.pln(120), charge.amount)
        assertFalse(charge.payableInApp)
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> { wallet.pay(charge.id, PaymentMethod.Card, "k") }.failure)
    }

    @Test fun savingAPolicyCreatesAVersionAndSessionsKeepTheirs() = runTest {
        val f = fixture()
        val v1 = f.policies.current(DemoTrainerId)
        val v2 = f.policies.save(DemoTrainerId, PolicyPresets.rules(PolicyPreset.Flexible), v1.versionId)
        assertEquals(v1.version + 1, v2.version)
        assertEquals(v1.versionId, f.trainer.load().sessions.first { it.id == "s13" }.policyVersionId)
        // The old version still decides the existing session's quote.
        val session = f.client.load().sessions.first { it.id == "s13" }
        assertEquals(v1.versionId, f.client.quoteCancellation(session.id, session.revision).policyVersionId)
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> {
            f.policies.save(DemoTrainerId, PolicyPresets.rules(PolicyPreset.Strict), v1.versionId)
        }.failure)
    }

    @Test fun aWaiverRemovesTheLateRescheduleFee() = runTest {
        for (waive in listOf(false, true)) {
            val f = fixture()
            val session = f.client.load().sessions.first { it.id == "s13" }
            val slot = f.firstSlot()
            val pending = f.client.requestReschedule(RescheduleRequest(session.id, session.revision, slot.start, slot.end))
            val review = f.trainer.requestReview(session.id)
            assertEquals(Money.pln(60), review.lateFee?.amount)
            f.trainer.acceptRequest(session.id, pending.revision, if (waive) FeeWaiver("Choroba") else null)
            val charge = f.wallet.load().charges.firstOrNull { it.sessionId == session.id && it.reason == ChargeReason.LateReschedule }
            if (waive) assertNull(charge) else assertEquals(Money.pln(60), charge?.amount)
        }
    }

    @Test fun aTakenSlotIsReportedToTheTrainerReview() = runTest {
        val f = fixture()
        val session = f.client.load().sessions.first { it.id == "s13" }
        val slot = f.firstSlot()
        val pending = f.client.requestReschedule(RescheduleRequest(session.id, session.revision, slot.start, slot.end))
        f.trainer.addBlock(slot.start, slot.end)
        assertFalse(f.trainer.requestReview(session.id).slotAvailable)
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> { f.trainer.acceptRequest(session.id, pending.revision) }.failure)
    }

    @Test fun checkInUsesTheSessionsPolicyVersion() = runTest {
        val f = fixture()
        val v1 = f.policies.current(DemoTrainerId)
        f.policies.save(DemoTrainerId, PolicyPresets.rules(PolicyPreset.Standard).copy(graceMinutes = 30), v1.versionId)
        assertEquals(15, f.trainer.checkIn("s1").graceMinutes)
    }
}
