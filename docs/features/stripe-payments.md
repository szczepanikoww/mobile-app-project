# Stripe payments (test mode)

Clients pay online charges from the wallet (B3) with Stripe in **test mode**. No real money moves. The synthetic `fake` provider is still the default, so tests and teammates without Stripe keys work unchanged.

Scope: client payments for existing online charges (session charges, late cancellation, late reschedule and no-show fees). Out of scope: Stripe Connect and per-trainer accounts (decision R7: one platform test account), refunds, package purchase (D1), invoices and trainer payouts.

## How a payment works

```mermaid
sequenceDiagram
  participant App
  participant Server
  participant Stripe
  App->>Server: POST /v1/client/charges/{id}/payments (method, Idempotency-Key)
  Server->>Stripe: create PaymentIntent (amount, pln, one allowed method, idempotency key)
  Server-->>App: payment PENDING + clientSecret + publishableKey + provider "stripe"
  App->>Stripe: PaymentSheet: card / BLIK / Przelewy24 entered in Stripe's UI
  Stripe-->>Server: signed webhook payment_intent.succeeded
  Server->>Server: payment SUCCEEDED, charge settled
  App->>Server: wallet reload shows Paid
```

- The pay call never answers "paid". Only the signed webhook, or Stripe's own intent status, settles a payment.
- The app never sees card or BLIK data. The client secret is kept in memory only and is never logged.
- **Closed sheet.** The app calls `POST /v1/client/payments/{id}/cancel`. The server cancels the intent at Stripe and marks the payment FAILED, so the charge can be paid again. If Stripe is already processing it (for example BLIK confirmed in the bank app), it stays PENDING.
- **Declined card.** `payment_intent.payment_failed` is not final, because the client can retry in the same sheet. Only `succeeded` and `canceled` settle a payment.
- **New attempt.** Before a new payment, unfinished intents for the same charge are cancelled at Stripe. Intents that are processing are kept, and the pay call answers 409.
- **Reconcile job.** `payment-reconcile` runs 15 minutes after each Stripe payment. It applies a webhook that never arrived (from Stripe's status) and cancels a sheet left open (for example when the app was killed).
- Live keys are refused at startup (`sk_test_` / `pk_test_` only).

Code: `server/.../billing/StripePaymentProvider.kt` (behind `PaymentProvider`), `BillingService.pay / abandon / reconcile`, the webhook route `/v1/webhooks/stripe`, `WalletViewModel.onCheckoutResult`, `pl.trainer.ui.ProviderPaymentSheet`, and `androidApp/.../StripePaymentSheetHost.kt`. On desktop there is no Stripe sheet, so a Stripe checkout fails safely and is cancelled.

## Running it locally

1. In the Stripe dashboard (test mode / sandbox, account country Poland), enable Card, BLIK and Przelewy24, and copy the keys from **Developers → API keys**.
2. Put them in the uncommitted `local.properties`:
   ```
   stripe.secretKey=sk_test_...
   stripe.publishableKey=pk_test_...
   stripe.webhookSecret=whsec_...
   ```
   Print the webhook secret once with `stripe listen --print-secret --api-key <sk_test_...>`. It stays the same for that key.
3. Start the database, the webhook forwarder and the server:
   ```bash
   docker compose -f server/docker-compose.yml up -d
   ```
   ```bash
   stripe listen --api-key <sk_test_...> --events "payment_intent.succeeded,payment_intent.canceled,payment_intent.payment_failed" --forward-to localhost:8080/v1/webhooks/stripe
   ```
   ```bash
   ./gradlew "-Ptrainer.ui=false" :server:run "-Ppayment.provider=stripe"
   ```
   The `run` task reads the three keys from `local.properties`. Environment variables (`STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`, `PAYMENT_PROVIDER`) take precedence. In PowerShell, quote the comma-separated `--events` list, or `stripe listen` treats it as one event name.
4. Run the app with `-Ptrainer.backend=local` and sign in as `klient@example.com`. The publishable key comes from the server with each payment, so the app needs no Stripe configuration.

Test data: card `4242 4242 4242 4242` (any future date and CVC) succeeds, `4000 0000 0000 0002` is declined, and `4000 0025 0000 3155` asks for 3D Secure. In test mode any 6-digit BLIK code is approved after a few seconds. Payments appear in the Stripe dashboard under the test account.

## Dependency changes

- `com.stripe:stripe-java` 34.1.0 (server). Stripe API version `2026-10-28`, which uses `allowed_payment_method_types`.
- `com.stripe:paymentsheet` 23.24.0 (`androidApp` only).
- `androidApp` `compileSdk` 35 → 36, required by PaymentSheet's AndroidX dependencies. `targetSdk` stays 35, and the shared modules still compile against 35.
