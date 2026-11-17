package com.trainingapp.server.billing

import com.trainingapp.contract.wallet.PaymentMethodDto
import com.trainingapp.server.config.PaymentConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

/** What the provider returned for a new payment. [clientSecret] goes to the app's payment sheet. */
data class ProviderIntent(val ref: String, val clientSecret: String?)

/**
 * A verified webhook, reduced to what billing needs. [type] is normalized: `payment.succeeded`, `payment.failed`, or
 * anything else (stored, not applied). [paymentRef] is null for events that are not about a payment intent.
 */
data class WebhookEvent(val id: String, val type: String, val paymentRef: String?)

/**
 * The payment vendor behind our own interface (architecture §4), so Stripe can be added or replaced. Calls are made
 * outside database transactions and always with the payment's operation id as idempotency key.
 */
interface PaymentProvider {
    /** Stored in `billing.payment.provider` and used in the webhook path (`/v1/webhooks/{name}`). */
    val name: String

    /** The methods the client may choose in the app with this provider. */
    val methods: List<PaymentMethodDto>

    /** The public key the app's payment sheet needs; null when the provider has no native sheet. */
    val publishableKey: String? get() = null

    fun createIntent(paymentId: Uuid, amountMinor: Long, currency: String, method: String, idempotencyKey: Uuid): ProviderIntent

    /** The provider's authoritative status (`SUCCEEDED`, `FAILED`, `PENDING`), used for out-of-order webhooks. */
    fun fetchStatus(ref: String): String?

    /**
     * Cancels an intent the client did not finish. True when it is cancelled now (or already was), so the payment may
     * be marked FAILED; false when it succeeded or is still being processed, so it stays PENDING.
     */
    fun cancel(ref: String): Boolean

    /** Verifies a webhook's signature over the raw body and parses it. Null when the signature is not valid. */
    fun parseWebhook(body: String, header: (String) -> String?): WebhookEvent?

    companion object {
        /** `PAYMENT_PROVIDER=fake` (default) or `stripe` (test keys required). */
        fun from(config: PaymentConfig): PaymentProvider = when (config.provider) {
            "fake" -> FakePaymentProvider(config.fakeWebhookSecret)
            "stripe" -> StripePaymentProvider(
                secretKey = config.stripeSecretKey ?: error("STRIPE_SECRET_KEY must be set for PAYMENT_PROVIDER=stripe"),
                publishableKey = config.stripePublishableKey ?: error("STRIPE_PUBLISHABLE_KEY must be set for PAYMENT_PROVIDER=stripe"),
                webhookSecret = config.stripeWebhookSecret ?: error("STRIPE_WEBHOOK_SECRET must be set for PAYMENT_PROVIDER=stripe"),
            )
            else -> error("Unknown PAYMENT_PROVIDER '${config.provider}'")
        }
    }
}

/**
 * The synthetic provider (`payment.provider = 'fake'`, method TEST_MODE allowed). No money moves. The worker confirms a
 * payment after a delay by sending a signed event through the same webhook code path. Its intents behave as if they
 * were already being processed: they cannot be cancelled.
 */
class FakePaymentProvider(private val webhookSecret: String) : PaymentProvider {
    override val name = "fake"
    override val methods = listOf(PaymentMethodDto.TEST_MODE, PaymentMethodDto.CARD, PaymentMethodDto.BLIK, PaymentMethodDto.PRZELEWY24)
    private val failing = mutableSetOf<String>()

    override fun createIntent(paymentId: Uuid, amountMinor: Long, currency: String, method: String, idempotencyKey: Uuid) =
        ProviderIntent(ref = "fake_pi_$idempotencyKey", clientSecret = "fake_secret_$idempotencyKey")

    override fun fetchStatus(ref: String): String = if (ref in failing) "FAILED" else "SUCCEEDED"

    override fun cancel(ref: String): Boolean = false

    /** Test hook: the next confirmation of [ref] fails. */
    fun failNext(ref: String) { failing += ref }

    override fun parseWebhook(body: String, header: (String) -> String?): WebhookEvent? {
        val signature = header("X-Signature") ?: return null
        if (!java.security.MessageDigest.isEqual(sign(body).encodeToByteArray(), signature.encodeToByteArray())) return null
        val json = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val id = json["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return null
        return WebhookEvent(id, type, json["paymentRef"]?.jsonPrimitive?.contentOrNull)
    }

    fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(webhookSecret.encodeToByteArray(), "HmacSHA256"))
        return mac.doFinal(body.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
