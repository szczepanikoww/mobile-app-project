package com.trainingapp.server

import com.trainingapp.contract.api.IDEMPOTENCY_KEY_HEADER
import com.trainingapp.contract.api.MoneyDto
import com.trainingapp.contract.schedule.CancelRequestDto
import com.trainingapp.contract.schedule.CancellationQuoteDto
import com.trainingapp.contract.schedule.PaymentKindDto
import com.trainingapp.contract.schedule.RevisionDto
import com.trainingapp.contract.schedule.SessionDto
import com.trainingapp.contract.wallet.ChargeStateDto
import com.trainingapp.contract.wallet.PayRequestDto
import com.trainingapp.contract.wallet.PayResponseDto
import com.trainingapp.contract.wallet.PaymentMethodDto
import com.trainingapp.contract.wallet.PaymentStateDto
import com.trainingapp.contract.wallet.WalletDto
import com.trainingapp.server.common.Ids
import com.trainingapp.server.common.dbNow
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
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

class WalletTest : ApiTest() {
    private val trainer = fixtures.trainer("trainer-w", priceZloty = 120, freeLateCancellations = 0)
    private val clientId = fixtures.client("client-w")

    /** Books, confirms and late-cancels a session (50% fee): returns the fee charge's id. */
    private suspend fun HttpClient.lateCancel(kind: PaymentKindDto? = null, hours: Int = 20): String {
        val session = book("client-w", trainer, slotIn(hours), kind = kind).body<SessionDto>()
        val confirmed = services.scheduling.confirm(trainer.id, Uuid.parse(session.id))
        val quote = post("/v1/client/sessions/${session.id}/cancellation-quotes") { auth("client-w"); json(); setBody(RevisionDto(confirmed.revision)) }
            .body<CancellationQuoteDto>()
        post("/v1/client/sessions/${session.id}/cancel") { auth("client-w"); json(); setBody(CancelRequestDto(confirmed.revision, quote.quoteId, Ids.v7().toString())) }
        return get("/v1/client/wallet") { auth("client-w") }.body<WalletDto>().charges.first { it.sessionId == session.id }.id
    }

    private suspend fun HttpClient.pay(chargeId: String, key: Uuid) = post("/v1/client/charges/$chargeId/payments") {
        auth("client-w"); json(); header(IDEMPOTENCY_KEY_HEADER, key.toString()); setBody(PayRequestDto(PaymentMethodDto.TEST_MODE))
    }

    @Test
    fun `acceptance test 2 - a payment is pending until the webhook, and duplicate events settle it once`() = api { http ->
        val chargeId = http.lateCancel()
        val key = Ids.v7()
        val started = http.pay(chargeId, key)
        assertEquals(HttpStatusCode.Created, started.status)
        val payment = started.body<PayResponseDto>().payment
        // Never "paid" from the pay call itself.
        assertEquals(PaymentStateDto.PENDING, payment.state)
        assertEquals(ChargeStateDto.PAYMENT_PENDING, http.get("/v1/client/wallet") { auth("client-w") }.body<WalletDto>().charges.single().state)

        // Same key → same payment; another key while one is pending → 409.
        assertEquals(payment.id, http.pay(chargeId, key).body<PayResponseDto>().payment.id)
        assertEquals(HttpStatusCode.Conflict, http.pay(chargeId, Ids.v7()).status)

        // The worker confirms through the webhook path; a replayed event is ignored.
        runBlocking { services.worker.runDueJobs() }
        val ref = "fake_pi_$key"
        assertFalse(runBlocking { services.billing.handleProviderEvent("fake", "evt_${payment.id}", "payment.succeeded", ref, JsonObject(emptyMap())) })
        val wallet = http.get("/v1/client/wallet") { auth("client-w") }.body<WalletDto>()
        assertEquals(PaymentStateDto.PAID, wallet.payments.single().state)
        assertEquals(ChargeStateDto.SETTLED, wallet.charges.single().state)
        assertEquals(1, count("SELECT count(*) FROM billing.provider_event"))
        assertEquals(1, count("SELECT count(*) FROM billing.payment WHERE status = 'SUCCEEDED'"))
        // A settled charge cannot be paid again.
        assertEquals(HttpStatusCode.Conflict, http.pay(chargeId, Ids.v7()).status)
    }

    @Test
    fun `an out-of-order failure event is checked against the provider before it is applied`() = api { http ->
        val chargeId = http.lateCancel()
        val key = Ids.v7()
        val payment = http.pay(chargeId, key).body<PayResponseDto>().payment
        // A "failed" event arrives, but the provider says the intent succeeded: the provider's state wins.
        runBlocking { services.billing.handleProviderEvent("fake", "evt_late_fail", "payment.failed", "fake_pi_$key", JsonObject(emptyMap())) }
        val wallet = http.get("/v1/client/wallet") { auth("client-w") }.body<WalletDto>()
        assertEquals(PaymentStateDto.PAID, wallet.payments.single { it.id == payment.id }.state)
    }

    @Test
    fun `webhooks need a valid signature`() = api { http ->
        val body = """{"id":"evt_x","type":"payment.succeeded","paymentRef":"fake_pi_x"}"""
        val unsigned = http.post("/v1/webhooks/fake") { json(); setBody(body) }
        assertEquals(HttpStatusCode.Unauthorized, unsigned.status)
        val signed = http.post("/v1/webhooks/fake") { json(); header("X-Signature", fakeProvider.sign(body)); setBody(body) }
        assertEquals(HttpStatusCode.OK, signed.status)
        assertEquals(1, count("SELECT count(*) FROM billing.provider_event WHERE event_id = 'evt_x'"))
    }

    @Test
    fun `the cash example - one 180 zl payment settles a 60 zl fee and a 120 zl session and clears the overdue flag`() = api { http ->
        http.lateCancel(kind = PaymentKindDto.CASH)
        val session = http.book("client-w", trainer, slotIn(30), kind = PaymentKindDto.CASH).body<SessionDto>()
        services.scheduling.confirm(trainer.id, Uuid.parse(session.id))
        services.scheduling.checkIn(trainer.id, Uuid.parse(session.id))
        val relationshipId = db.txBlocking { with(services.identity) { openRelationship(trainer.id, clientId) }!!.id }

        val before = http.get("/v1/client/wallet") { auth("client-w") }.body<WalletDto>()
        assertEquals(listOf(6000L, 12000L), before.charges.map { it.amount.minor }.sorted())
        // Cash charges are shown but cannot be paid in the app.
        assertEquals(HttpStatusCode.Conflict, http.pay(before.charges.first().id, Ids.v7()).status)
        assertEquals(1, count("SELECT count(*) FROM billing.relationship_overdue WHERE has_overdue")) // the fee is due at once

        services.billing.recordCash(trainer.id, relationshipId, MoneyDto(18000, "PLN"), Ids.v7())
        val after = http.get("/v1/client/wallet") { auth("client-w") }.body<WalletDto>()
        assertEquals(setOf(ChargeStateDto.SETTLED), after.charges.map { it.state }.toSet())
        assertEquals(0, count("SELECT count(*) FROM billing.relationship_overdue WHERE has_overdue"))
    }

    @Test
    fun `a package session cancelled late consumes credit instead of a money charge`() = api { http ->
        val relationshipKey = http.book("client-w", trainer, slotIn(100)).body<SessionDto>()
        services.scheduling.confirm(trainer.id, Uuid.parse(relationshipKey.id)) // activates the relationship
        val relationshipId = db.txBlocking { with(services.identity) { openRelationship(trainer.id, clientId) }!!.id }
        db.txBlocking { with(services.billing) { grantPackage(relationshipId, trainer.packageOfferId, Ids.v7(), dbNow()) } }

        http.lateCancel(kind = PaymentKindDto.PACKAGE)
        val wallet = http.get("/v1/client/wallet") { auth("client-w") }.body<WalletDto>()
        val fee = wallet.charges.single { it.packagePercent != null }
        assertEquals(50, fee.packagePercent)
        assertEquals(ChargeStateDto.SETTLED, fee.state)
        assertEquals(0, count("SELECT count(*) FROM billing.charge"))
        // 50 of 800 units used → shown as 0 whole sessions used out of 8.
        assertEquals(8, wallet.packages.single().total)
    }
}
