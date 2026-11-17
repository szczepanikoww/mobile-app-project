package com.trainingapp.server

import com.trainingapp.contract.api.IDEMPOTENCY_KEY_HEADER
import com.trainingapp.contract.schedule.CancelRequestDto
import com.trainingapp.contract.schedule.CancellationQuoteDto
import com.trainingapp.contract.schedule.RevisionDto
import com.trainingapp.contract.schedule.SessionDto
import com.trainingapp.contract.wallet.ChargeStateDto
import com.trainingapp.contract.wallet.PayRequestDto
import com.trainingapp.contract.wallet.PayResponseDto
import com.trainingapp.contract.wallet.PaymentDto
import com.trainingapp.contract.wallet.PaymentMethodDto
import com.trainingapp.contract.wallet.PaymentStateDto
import com.trainingapp.contract.wallet.WalletDto
import com.trainingapp.server.billing.PaymentProvider
import com.trainingapp.server.billing.ProviderIntent
import com.trainingapp.server.billing.ProviderRefund
import com.trainingapp.server.billing.StripePaymentProvider
import com.trainingapp.server.billing.WebhookEvent
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
import kotlin.uuid.Uuid

/**
 * Stripe's semantics without the network: an intent waits for the app's sheet (`requires_payment_method`), can be
 * cancelled until it is processing, and only `succeeded` / `canceled` are final.
 */
class StripeLikeProvider : PaymentProvider {
    override val name = "stripe"
    override val methods = listOf(PaymentMethodDto.CARD, PaymentMethodDto.BLIK, PaymentMethodDto.PRZELEWY24)
    override val publishableKey = "pk_test_stub"
    val status = mutableMapOf<String, String>()

    override fun createIntent(paymentId: Uuid, amountMinor: Long, currency: String, method: String, idempotencyKey: Uuid): ProviderIntent {
        StripePaymentProvider.stripeMethod(method)
        val ref = "pi_$idempotencyKey"
        status.putIfAbsent(ref, "requires_payment_method")
        return ProviderIntent(ref, "${ref}_secret")
    }

    override fun fetchStatus(ref: String) = StripePaymentProvider.statusOf(status[ref])

    override fun cancel(ref: String): Boolean {
        val current = status[ref]
        if (current in setOf("requires_payment_method", "requires_confirmation", "requires_action")) {
            status[ref] = "canceled"
            return true
        }
        return current == "canceled"
    }

    /** Refunds sent to the provider: (payment ref, amount). */
    val refunds = mutableListOf<Pair<String, Long>>()

    override fun refund(paymentRef: String, amountMinor: Long, idempotencyKey: Uuid): ProviderRefund {
        refunds += paymentRef to amountMinor
        return ProviderRefund("re_$idempotencyKey", "SUCCEEDED")
    }

    override fun refundStatus(ref: String) = ProviderRefund(ref, "SUCCEEDED")

    override fun parseWebhook(body: String, header: (String) -> String?): WebhookEvent? = null
}

class StripePaymentsTest : ApiTest(StripeLikeProvider()) {
    private val stripe get() = services.paymentProvider as StripeLikeProvider
    private val trainer = fixtures.trainer("trainer-s", priceZloty = 120, freeLateCancellations = 0)
    private val clientId = fixtures.client("client-s")
    private val otherClient = fixtures.client("client-other")

    private suspend fun HttpClient.lateCancel(): String {
        val session = book("client-s", trainer, slotIn(20)).body<SessionDto>()
        val confirmed = services.scheduling.confirm(trainer.id, Uuid.parse(session.id))
        val quote = post("/v1/client/sessions/${session.id}/cancellation-quotes") { auth("client-s"); json(); setBody(RevisionDto(confirmed.revision)) }
            .body<CancellationQuoteDto>()
        post("/v1/client/sessions/${session.id}/cancel") { auth("client-s"); json(); setBody(CancelRequestDto(confirmed.revision, quote.quoteId, Ids.v7().toString())) }
        return wallet().charges.first { it.sessionId == session.id }.id
    }

    private suspend fun HttpClient.wallet() = get("/v1/client/wallet") { auth("client-s") }.body<WalletDto>()

    private suspend fun HttpClient.pay(chargeId: String, key: Uuid, method: PaymentMethodDto = PaymentMethodDto.CARD) =
        post("/v1/client/charges/$chargeId/payments") {
            auth("client-s"); json(); header(IDEMPOTENCY_KEY_HEADER, key.toString()); setBody(PayRequestDto(method))
        }

    private suspend fun HttpClient.abandon(paymentId: String, subject: String = "client-s") =
        post("/v1/client/payments/$paymentId/cancel") { auth(subject) }

    @Test
    fun `the pay answer carries what the sheet needs and the wallet lists Stripe's methods`() = api { http ->
        val chargeId = http.lateCancel()
        assertEquals(listOf(PaymentMethodDto.CARD, PaymentMethodDto.BLIK, PaymentMethodDto.PRZELEWY24), http.wallet().paymentMethods)
        assertEquals(HttpStatusCode.UnprocessableEntity, http.pay(chargeId, Ids.v7(), PaymentMethodDto.TEST_MODE).status)

        val key = Ids.v7()
        val answer = http.pay(chargeId, key).body<PayResponseDto>()
        assertEquals(PaymentStateDto.PENDING, answer.payment.state)
        assertEquals("stripe", answer.provider)
        assertEquals("pk_test_stub", answer.publishableKey)
        assertEquals("pi_${key}_secret", answer.clientSecret)
        // A retry with the same key opens the same intent.
        assertEquals("pi_${key}_secret", http.pay(chargeId, key).body<PayResponseDto>().clientSecret)
    }

    @Test
    fun `a sheet closed without paying fails the payment and the charge can be paid again`() = api { http ->
        val chargeId = http.lateCancel()
        val key = Ids.v7()
        val payment = http.pay(chargeId, key).body<PayResponseDto>().payment

        val closed = http.abandon(payment.id)
        assertEquals(HttpStatusCode.OK, closed.status)
        assertEquals(PaymentStateDto.FAILED, closed.body<PaymentDto>().state)
        assertEquals("canceled", stripe.status["pi_$key"])
        assertEquals(ChargeStateDto.UNPAID, http.wallet().charges.single().state)

        val again = http.pay(chargeId, Ids.v7())
        assertEquals(HttpStatusCode.Created, again.status)
        assertEquals(PaymentStateDto.PENDING, again.body<PayResponseDto>().payment.state)
    }

    @Test
    fun `an unfinished intent is released when the client starts again, a processing one is not`() = api { http ->
        val chargeId = http.lateCancel()
        val first = http.pay(chargeId, Ids.v7()).body<PayResponseDto>().payment

        // The app was closed with the sheet open: a new attempt cancels the old intent instead of answering 409.
        val secondKey = Ids.v7()
        val second = http.pay(chargeId, secondKey)
        assertEquals(HttpStatusCode.Created, second.status)
        assertEquals(PaymentStateDto.FAILED, http.wallet().payments.single { it.id == first.id }.state)

        // BLIK waiting for the bank: neither a new attempt nor "close" may cancel it.
        stripe.status["pi_$secondKey"] = "processing"
        assertEquals(HttpStatusCode.Conflict, http.pay(chargeId, Ids.v7()).status)
        val secondId = second.body<PayResponseDto>().payment.id
        assertEquals(PaymentStateDto.PENDING, http.abandon(secondId).body<PaymentDto>().state)
        assertEquals(1, count("SELECT count(*) FROM billing.payment WHERE status = 'PENDING'"))
    }

    @Test
    fun `a declined attempt does not fail the payment and the later success settles it once`() = api { http ->
        val chargeId = http.lateCancel()
        val key = Ids.v7()
        val payment = http.pay(chargeId, key).body<PayResponseDto>().payment
        val ref = "pi_$key"

        // Declined card: Stripe keeps the intent open for another try in the same sheet.
        runBlocking { services.billing.handleProviderEvent("stripe", "evt_declined", "payment_intent.payment_failed", ref, JsonObject(emptyMap())) }
        assertEquals(PaymentStateDto.PENDING, http.wallet().payments.single { it.id == payment.id }.state)

        stripe.status[ref] = "succeeded"
        runBlocking { services.billing.handleProviderEvent("stripe", "evt_ok", "payment.succeeded", ref, JsonObject(emptyMap())) }
        runBlocking { services.billing.handleProviderEvent("stripe", "evt_ok", "payment.succeeded", ref, JsonObject(emptyMap())) }
        val wallet = http.wallet()
        assertEquals(PaymentStateDto.PAID, wallet.payments.single().state)
        assertEquals(ChargeStateDto.SETTLED, wallet.charges.single().state)
        // Once paid, closing the sheet late changes nothing.
        assertEquals(PaymentStateDto.PAID, http.abandon(payment.id).body<PaymentDto>().state)
    }

    @Test
    fun `the reconcile job cancels a forgotten sheet and applies a missed success`() = api { http ->
        val forgottenCharge = http.lateCancel()
        val forgotten = http.pay(forgottenCharge, Ids.v7()).body<PayResponseDto>().payment
        // The app was killed with the sheet open: the job (normally 15 minutes later) releases the charge.
        services.billing.reconcile(Uuid.parse(forgotten.id))
        assertEquals(PaymentStateDto.FAILED, http.wallet().payments.single { it.id == forgotten.id }.state)
        assertEquals(1, count("SELECT count(*) FROM platform.job WHERE kind = 'payment-reconcile'"))

        val paidCharge = http.lateCancel()
        val key = Ids.v7()
        val paid = http.pay(paidCharge, key).body<PayResponseDto>().payment
        stripe.status["pi_$key"] = "succeeded" // the webhook never arrived
        services.billing.reconcile(Uuid.parse(paid.id))
        val wallet = http.wallet()
        assertEquals(PaymentStateDto.PAID, wallet.payments.single { it.id == paid.id }.state)
        assertEquals(ChargeStateDto.SETTLED, wallet.charges.single { it.id == paidCharge }.state)
    }

    @Test
    fun `only the paying client can cancel a payment`() = api { http ->
        val chargeId = http.lateCancel()
        val payment = http.pay(chargeId, Ids.v7()).body<PayResponseDto>().payment
        assertEquals(HttpStatusCode.NotFound, http.abandon(payment.id, subject = "client-other").status)
        assertEquals(PaymentStateDto.PENDING, http.wallet().payments.single().state)
        check(clientId != otherClient)
    }
}
