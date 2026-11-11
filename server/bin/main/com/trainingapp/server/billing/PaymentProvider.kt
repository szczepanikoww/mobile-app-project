package com.trainingapp.server.billing

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

/** What the provider returned for a new payment. [clientSecret] goes to the app's payment sheet. */
data class ProviderIntent(val ref: String, val clientSecret: String?)

/**
 * The payment vendor behind our own interface (architecture §4), so Stripe can be added or replaced. Calls are made
 * outside database transactions and always with the payment's operation id as idempotency key.
 */
interface PaymentProvider {
    val name: String

    fun createIntent(paymentId: Uuid, amountMinor: Long, currency: String, method: String, idempotencyKey: Uuid): ProviderIntent

    /** The provider's authoritative status (`SUCCEEDED`, `FAILED`, `PENDING`), used for out-of-order webhooks. */
    fun fetchStatus(ref: String): String?

    /** Checks a webhook's signature over the raw body. */
    fun verifySignature(body: String, signature: String?): Boolean
}

/**
 * The synthetic provider of the university phase (`payment.provider = 'fake'`, method TEST_MODE allowed). No money
 * moves. The worker confirms a payment after a delay by sending a signed event through the same webhook code path.
 */
class FakePaymentProvider(private val webhookSecret: String) : PaymentProvider {
    override val name = "fake"
    private val failing = mutableSetOf<String>()

    override fun createIntent(paymentId: Uuid, amountMinor: Long, currency: String, method: String, idempotencyKey: Uuid) =
        ProviderIntent(ref = "fake_pi_$idempotencyKey", clientSecret = "fake_secret_$idempotencyKey")

    override fun fetchStatus(ref: String): String = if (ref in failing) "FAILED" else "SUCCEEDED"

    /** Test hook: the next confirmation of [ref] fails. */
    fun failNext(ref: String) { failing += ref }

    override fun verifySignature(body: String, signature: String?): Boolean =
        signature != null && java.security.MessageDigest.isEqual(sign(body).encodeToByteArray(), signature.encodeToByteArray())

    fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(webhookSecret.encodeToByteArray(), "HmacSHA256"))
        return mac.doFinal(body.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
