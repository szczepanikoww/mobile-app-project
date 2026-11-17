package com.trainingapp.server

import com.trainingapp.contract.api.IDEMPOTENCY_KEY_HEADER
import com.trainingapp.contract.schedule.BookingRequestDto
import com.trainingapp.contract.schedule.CancelRequestDto
import com.trainingapp.contract.schedule.CancellationQuoteDto
import com.trainingapp.contract.schedule.PaymentKindDto
import com.trainingapp.contract.schedule.RevisionDto
import com.trainingapp.contract.schedule.SessionDto
import com.trainingapp.contract.schedule.SessionStatus
import com.trainingapp.contract.wallet.ChargeStateDto
import com.trainingapp.contract.wallet.PayRequestDto
import com.trainingapp.contract.wallet.PayResponseDto
import com.trainingapp.contract.wallet.PaymentMethodDto
import com.trainingapp.contract.wallet.PaymentStateDto
import com.trainingapp.contract.wallet.WalletDto
import com.trainingapp.server.common.ApiException
import com.trainingapp.server.common.Ids
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Decision P1: an online session is paid when it is booked; every outcome settles from that prepayment. */
class PrepaidBookingTest : ApiTest(StripeLikeProvider()) {
    private val stripe get() = services.paymentProvider as StripeLikeProvider
    private val trainer = fixtures.trainer("trainer-p", priceZloty = 120, freeLateCancellations = 0)
    private val clientId = fixtures.client("client-p")

    private suspend fun HttpClient.bookPrepaid(hours: Int = 60, kind: PaymentKindDto = PaymentKindDto.ONLINE) =
        post("/v1/client/booking-requests") {
            auth("client-p"); json(); header(IDEMPOTENCY_KEY_HEADER, Ids.v7().toString())
            val slot = slotIn(hours)
            setBody(BookingRequestDto(trainer.id.toString(), slot.first, slot.second, trainer.gymName, trainer.policyId.toString(), paymentKind = kind, payNow = true))
        }

    private suspend fun HttpClient.wallet() = get("/v1/client/wallet") { auth("client-p") }.body<WalletDto>()

    private suspend fun HttpClient.session(id: String) =
        get("/v1/client/schedule") { auth("client-p") }.body<com.trainingapp.contract.schedule.ClientScheduleDto>().sessions.single { it.id == id }

    /** Books, pays by card and lets the provider confirm it. Returns the session and the payment's intent ref. */
    private suspend fun HttpClient.bookAndPay(hours: Int = 60): Pair<SessionDto, String> {
        val session = bookPrepaid(hours).body<SessionDto>()
        val key = Ids.v7()
        post("/v1/client/charges/${session.payment.prepaymentChargeId}/payments") {
            auth("client-p"); json(); header(IDEMPOTENCY_KEY_HEADER, key.toString()); setBody(PayRequestDto(PaymentMethodDto.CARD))
        }.body<PayResponseDto>()
        val ref = "pi_$key"
        stripe.status[ref] = "succeeded"
        services.billing.handleProviderEvent("stripe", "evt_$key", "payment.succeeded", ref, JsonObject(emptyMap()))
        return session to ref
    }

    private fun requestedEvents() = count("SELECT count(*) FROM platform.outbox_event WHERE type = 'session.requested'")

    @Test
    fun `an unpaid request is not a request for the trainer until the payment is confirmed`() = api { http ->
        val booked = http.bookPrepaid()
        assertEquals(HttpStatusCode.Created, booked.status)
        val session = booked.body<SessionDto>()
        assertTrue(session.payment.awaitingPayment)
        val chargeId = assertNotNull(session.payment.prepaymentChargeId)
        assertEquals(12000L, http.wallet().charges.single { it.id == chargeId }.amount.minor)
        assertEquals(0, requestedEvents())
        // The trainer cannot accept what they cannot see.
        assertFailsWith<ApiException> { services.scheduling.confirm(trainer.id, Uuid.parse(session.id)) }

        val key = Ids.v7()
        http.post("/v1/client/charges/$chargeId/payments") {
            auth("client-p"); json(); header(IDEMPOTENCY_KEY_HEADER, key.toString()); setBody(PayRequestDto(PaymentMethodDto.BLIK))
        }
        stripe.status["pi_$key"] = "succeeded"
        services.billing.handleProviderEvent("stripe", "evt_1", "payment.succeeded", "pi_$key", JsonObject(emptyMap()))

        assertFalse(http.session(session.id).payment.awaitingPayment)
        assertEquals(1, requestedEvents())
        services.scheduling.confirm(trainer.id, Uuid.parse(session.id))
        assertEquals(ChargeStateDto.SETTLED, http.wallet().charges.single { it.id == chargeId }.state)
    }

    @Test
    fun `cash cannot be paid now`() = api { http ->
        assertEquals(HttpStatusCode.UnprocessableEntity, http.bookPrepaid(kind = PaymentKindDto.CASH).status)
    }

    @Test
    fun `a held prepaid session is not charged again at check-in`() = api { http ->
        val (session, _) = http.bookAndPay(hours = 60)
        services.scheduling.confirm(trainer.id, Uuid.parse(session.id))
        services.scheduling.checkIn(trainer.id, Uuid.parse(session.id))
        assertEquals(1, count("SELECT count(*) FROM billing.charge WHERE kind = 'SESSION'"))
        assertEquals(0, count("SELECT count(*) FROM billing.refund"))
        assertEquals(PaymentStateDto.PAID, http.wallet().payments.single().state)
    }

    @Test
    fun `a rejected request is refunded in full through the provider`() = api { http ->
        val (session, ref) = http.bookAndPay()
        services.scheduling.reject(trainer.id, Uuid.parse(session.id))
        assertEquals(1, count("SELECT count(*) FROM billing.refund WHERE status = 'PENDING' AND amount_minor = 12000 AND reason = 'NOT_ACCEPTED'"))
        assertNotNull(http.wallet().payments.single().refundPending)

        assertEquals(1, services.billing.processRefunds())
        assertEquals(listOf(ref to 12000L), stripe.refunds)
        val payment = http.wallet().payments.single()
        assertEquals(PaymentStateDto.REFUNDED, payment.state)
        assertEquals(12000L, payment.refunded?.minor)
        assertNull(payment.refundPending)
        // Sent once.
        assertEquals(0, services.billing.processRefunds())
        assertEquals(1, stripe.refunds.size)
    }

    @Test
    fun `a late client cancellation keeps the fee and refunds the rest`() = api { http ->
        val (session, ref) = http.bookAndPay(hours = 20) // 50% tier
        val confirmed = services.scheduling.confirm(trainer.id, Uuid.parse(session.id))
        val quote = http.post("/v1/client/sessions/${session.id}/cancellation-quotes") { auth("client-p"); json(); setBody(RevisionDto(confirmed.revision)) }
            .body<CancellationQuoteDto>()
        assertEquals(6000L, quote.fee.amount.minor)
        http.post("/v1/client/sessions/${session.id}/cancel") {
            auth("client-p"); json(); setBody(CancelRequestDto(confirmed.revision, quote.quoteId, Ids.v7().toString()))
        }
        services.billing.processRefunds()
        assertEquals(listOf(ref to 6000L), stripe.refunds)
        val wallet = http.wallet()
        // Nothing is left to pay: the fee was taken from the prepayment.
        assertTrue(wallet.charges.all { it.state == ChargeStateDto.SETTLED })
        assertEquals(0, count("SELECT count(*) FROM billing.relationship_overdue WHERE has_overdue"))
        assertEquals(6000L, wallet.payments.single().refunded?.minor)
    }

    @Test
    fun `a trainer cancellation refunds the prepayment in full`() = api { http ->
        val (session, ref) = http.bookAndPay(hours = 20)
        services.scheduling.confirm(trainer.id, Uuid.parse(session.id))
        services.scheduling.cancelByTrainer(trainer.id, Uuid.parse(session.id), Ids.v7())
        services.billing.processRefunds()
        assertEquals(listOf(ref to 12000L), stripe.refunds)
    }

    @Test
    fun `closing the payment sheet withdraws the request and nothing is owed`() = api { http ->
        val session = http.bookPrepaid().body<SessionDto>()
        val pay = http.post("/v1/client/charges/${session.payment.prepaymentChargeId}/payments") {
            auth("client-p"); json(); header(IDEMPOTENCY_KEY_HEADER, Ids.v7().toString()); setBody(PayRequestDto(PaymentMethodDto.CARD))
        }.body<PayResponseDto>()
        http.post("/v1/client/payments/${pay.payment.id}/cancel") { auth("client-p") }
        runBlocking { services.worker.runDueJobs() }

        assertEquals(SessionStatus.CANCELLED_BY_CLIENT, http.session(session.id).status)
        assertTrue(http.wallet().charges.all { it.state == ChargeStateDto.SETTLED })
        assertEquals(0, count("SELECT count(*) FROM billing.refund"))
        assertEquals(0, requestedEvents())
    }

    @Test
    fun `an unpaid request expires after the payment window, and a late payment is returned`() = api { http ->
        val session = http.bookPrepaid().body<SessionDto>()
        val key = Ids.v7()
        http.post("/v1/client/charges/${session.payment.prepaymentChargeId}/payments") {
            auth("client-p"); json(); header(IDEMPOTENCY_KEY_HEADER, key.toString()); setBody(PayRequestDto(PaymentMethodDto.BLIK))
        }
        // BLIK still waiting for the bank: the window job leaves the request open.
        services.scheduling.expireUnpaid(Uuid.parse(session.id), abandoned = false)
        assertEquals(SessionStatus.REQUESTED, http.session(session.id).status)

        // The bank never confirms; the intent is cancelled, so the next window check closes the request.
        stripe.status["pi_$key"] = "canceled"
        services.billing.reconcile(Uuid.parse(http.wallet().payments.single().id))
        services.scheduling.expireUnpaid(Uuid.parse(session.id), abandoned = false)
        assertEquals(SessionStatus.EXPIRED, http.session(session.id).status)
        assertTrue(http.wallet().charges.all { it.state == ChargeStateDto.SETTLED })
        assertEquals(0, count("SELECT count(*) FROM billing.refund"))
    }

    @Test
    fun `a payment that succeeds after the request was withdrawn goes straight back`() = api { http ->
        val session = http.bookPrepaid().body<SessionDto>()
        val key = Ids.v7()
        http.post("/v1/client/charges/${session.payment.prepaymentChargeId}/payments") {
            auth("client-p"); json(); header(IDEMPOTENCY_KEY_HEADER, key.toString()); setBody(PayRequestDto(PaymentMethodDto.BLIK))
        }
        http.post("/v1/client/sessions/${session.id}/withdraw") { auth("client-p"); json(); setBody(RevisionDto(session.revision)) }
        assertEquals(SessionStatus.CANCELLED_BY_CLIENT, http.session(session.id).status)

        stripe.status["pi_$key"] = "succeeded"
        services.billing.handleProviderEvent("stripe", "evt_late", "payment.succeeded", "pi_$key", JsonObject(emptyMap()))
        services.billing.processRefunds()
        assertEquals(listOf("pi_$key" to 12000L), stripe.refunds)
        assertEquals(PaymentStateDto.REFUNDED, http.wallet().payments.single().state)
        assertEquals(0, requestedEvents())
        check(clientId != trainer.id)
    }
}
