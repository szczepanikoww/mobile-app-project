package com.trainingapp.server.billing

import com.stripe.StripeClient
import com.stripe.exception.SignatureVerificationException
import com.stripe.exception.StripeException
import com.stripe.net.RequestOptions
import com.stripe.net.Webhook
import com.stripe.param.PaymentIntentCreateParams
import com.stripe.param.PaymentIntentCreateParams.AllowedPaymentMethodType
import com.trainingapp.contract.wallet.PaymentMethodDto
import com.trainingapp.server.common.ApiException
import com.trainingapp.server.common.ErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

/**
 * Stripe in test mode (`PAYMENT_PROVIDER=stripe`, docs/features/stripe-payments.md). One platform account, no Connect
 * (decision R7). Each payment is one PaymentIntent limited to the method the client chose, confirmed in the app's
 * PaymentSheet with the returned client secret. Only the signed webhook (or the intent's own status) settles it.
 *
 * The webhook body is verified with Stripe's signature check and then read as plain JSON, so the account's API version
 * does not have to match the library's.
 */
class StripePaymentProvider(
    secretKey: String,
    override val publishableKey: String,
    private val webhookSecret: String,
    private val client: StripeClient = StripeClient(secretKey),
) : PaymentProvider {
    init {
        // Never a live key: the university phase runs in test mode only (architecture §4).
        require(secretKey.startsWith("sk_test_") || secretKey.startsWith("rk_test_")) { "STRIPE_SECRET_KEY must be a test-mode key" }
        require(publishableKey.startsWith("pk_test_")) { "STRIPE_PUBLISHABLE_KEY must be a test-mode key" }
    }

    override val name = "stripe"
    override val methods = listOf(PaymentMethodDto.CARD, PaymentMethodDto.BLIK, PaymentMethodDto.PRZELEWY24)

    override fun createIntent(paymentId: Uuid, amountMinor: Long, currency: String, method: String, idempotencyKey: Uuid): ProviderIntent {
        val params = PaymentIntentCreateParams.builder()
            .setAmount(amountMinor)
            .setCurrency(currency.lowercase())
            .addAllowedPaymentMethodType(stripeMethod(method))
            // Only our own id: no names, sessions or other private data in Stripe metadata.
            .putMetadata("payment_id", paymentId.toString())
            .build()
        val intent = call { client.v1().paymentIntents().create(params, options(idempotencyKey.toString())) }
        return ProviderIntent(ref = intent.id, clientSecret = intent.clientSecret)
    }

    override fun fetchStatus(ref: String): String? = statusOf(call { client.v1().paymentIntents().retrieve(ref) }.status)

    override fun cancel(ref: String): Boolean {
        val status = call { client.v1().paymentIntents().retrieve(ref) }.status
        if (status !in CANCELLABLE) return status == "canceled"
        return try {
            client.v1().paymentIntents().cancel(ref).status == "canceled"
        } catch (e: StripeException) {
            // It moved on in the meantime (e.g. the client confirmed it): ask again instead of guessing.
            log.info("Stripe cancel of {} refused: {}", ref, e.code)
            call { client.v1().paymentIntents().retrieve(ref) }.status == "canceled"
        }
    }

    override fun parseWebhook(body: String, header: (String) -> String?): WebhookEvent? {
        val signature = header("Stripe-Signature") ?: return null
        try {
            Webhook.Signature.verifyHeader(body, signature, webhookSecret, SIGNATURE_TOLERANCE_SECONDS)
        } catch (_: SignatureVerificationException) {
            return null
        }
        return eventOf(body)
    }

    private fun options(idempotencyKey: String) = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build()

    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: StripeException) {
        log.warn("Stripe request failed: {} ({})", e.code, e.statusCode)
        throw ApiException(503, ErrorCode.INTERNAL, "payment provider unavailable")
    }

    companion object {
        private val log = LoggerFactory.getLogger(StripePaymentProvider::class.java)
        private const val SIGNATURE_TOLERANCE_SECONDS = 300L

        /** Intent states in which the client has not paid yet, so cancelling cannot lose money. */
        private val CANCELLABLE = setOf("requires_payment_method", "requires_confirmation", "requires_action")

        fun stripeMethod(method: String): AllowedPaymentMethodType = when (method) {
            "CARD" -> AllowedPaymentMethodType.CARD
            "BLIK" -> AllowedPaymentMethodType.BLIK
            "PRZELEWY24" -> AllowedPaymentMethodType.P24
            else -> throw ApiException(422, ErrorCode.VALIDATION, "method not available with Stripe")
        }

        /** Stripe's intent status in our terms. Everything unfinished (including a declined attempt) stays PENDING. */
        fun statusOf(stripeStatus: String?): String? = when (stripeStatus) {
            null -> null
            "succeeded" -> "SUCCEEDED"
            "canceled" -> "FAILED"
            else -> "PENDING"
        }

        /**
         * Maps a Stripe event to ours. A declined attempt (`payment_intent.payment_failed`) is not final: the client
         * may retry in the same sheet, so only `succeeded` and `canceled` settle the payment.
         */
        fun eventOf(body: String): WebhookEvent? {
            val json = Json.parseToJsonElement(body) as? JsonObject ?: return null
            val id = json["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return null
            val obj = json["data"]?.jsonObject?.get("object") as? JsonObject
            val ref = obj?.takeIf { it["object"]?.jsonPrimitive?.contentOrNull == "payment_intent" }?.get("id")?.jsonPrimitive?.contentOrNull
            val normalized = when (type) {
                "payment_intent.succeeded" -> "payment.succeeded"
                "payment_intent.canceled" -> "payment.failed"
                else -> type
            }
            return WebhookEvent(id, normalized, ref)
        }
    }
}
