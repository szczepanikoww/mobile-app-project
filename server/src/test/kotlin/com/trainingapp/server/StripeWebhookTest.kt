package com.trainingapp.server

import com.trainingapp.server.billing.StripePaymentProvider
import com.trainingapp.server.billing.WebhookEvent
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Stripe's webhook signature and event mapping, offline (no API calls are made). */
class StripeWebhookTest {
    private val secret = "whsec_unit_test"
    private val provider = StripePaymentProvider("sk_test_unit", "pk_test_unit", secret)

    /** Stripe's scheme: `t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">`. */
    private fun signature(body: String, at: Long = System.currentTimeMillis() / 1000, key: String = secret): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.encodeToByteArray(), "HmacSHA256")) }
        val v1 = mac.doFinal("$at.$body".encodeToByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$at,v1=$v1"
    }

    private fun event(type: String, objectType: String = "payment_intent", id: String = "pi_123") =
        """{"id":"evt_1","object":"event","type":"$type","data":{"object":{"id":"$id","object":"$objectType","status":"succeeded"}}}"""

    private fun parse(body: String, signature: String?) = provider.parseWebhook(body) { if (it == "Stripe-Signature") signature else null }

    @Test
    fun `a correctly signed success event is accepted and normalized`() {
        val body = event("payment_intent.succeeded")
        assertEquals(WebhookEvent("evt_1", "payment.succeeded", "pi_123"), parse(body, signature(body)))
    }

    @Test
    fun `a missing, foreign, tampered or old signature is rejected`() {
        val body = event("payment_intent.succeeded")
        assertNull(parse(body, null))
        assertNull(parse(body, signature(body, key = "whsec_other")))
        assertNull(parse(body.replace("pi_123", "pi_999"), signature(body)))
        assertNull(parse(body, signature(body, at = System.currentTimeMillis() / 1000 - 3600)))
    }

    @Test
    fun `only succeeded and canceled settle a payment`() {
        assertEquals("payment.failed", StripePaymentProvider.eventOf(event("payment_intent.canceled"))?.type)
        // A declined attempt keeps its own type, which billing does not apply.
        assertEquals("payment_intent.payment_failed", StripePaymentProvider.eventOf(event("payment_intent.payment_failed"))?.type)
        // Events about other objects carry no payment reference.
        assertNull(StripePaymentProvider.eventOf(event("charge.succeeded", objectType = "charge", id = "ch_1"))?.paymentRef)

        assertEquals("SUCCEEDED", StripePaymentProvider.statusOf("succeeded"))
        assertEquals("FAILED", StripePaymentProvider.statusOf("canceled"))
        assertEquals("PENDING", StripePaymentProvider.statusOf("requires_payment_method"))
        assertEquals("PENDING", StripePaymentProvider.statusOf("processing"))
    }

    @Test
    fun `live keys are refused`() {
        assertFailsWith<IllegalArgumentException> { StripePaymentProvider("sk_live_x", "pk_test_x", secret) }
        assertFailsWith<IllegalArgumentException> { StripePaymentProvider("sk_test_x", "pk_live_x", secret) }
    }
}
