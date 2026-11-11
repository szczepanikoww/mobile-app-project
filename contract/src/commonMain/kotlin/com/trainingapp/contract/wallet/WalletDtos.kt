package com.trainingapp.contract.wallet

import com.trainingapp.contract.api.IsoInstant
import com.trainingapp.contract.api.MoneyDto
import com.trainingapp.contract.schedule.ChargeKindDto
import com.trainingapp.contract.schedule.TrainerRefDto
import kotlinx.serialization.Serializable

/*
 * The client's wallet (`GET /v1/client/wallet`, plan §8.2). States come from the database views, never from rules in
 * the app. A payment is PENDING until the provider's webhook confirms it; the API never answers "paid" to a pay call.
 */

/** From `billing.charge.kind`. `SESSION` and `PACKAGE_PERIOD` have no app value yet (gap G-9). */
@Serializable
enum class ChargeReasonDto { LATE_CANCELLATION, LATE_RESCHEDULE, NO_SHOW, SESSION, PACKAGE_PERIOD }

@Serializable
enum class ChargeStateDto { UNPAID, PAYMENT_PENDING, SETTLED }

/** The database's method set (conflict C2). `CASH` is recorded by the trainer, never paid in the app. */
@Serializable
enum class PaymentMethodDto { CASH, CARD, BLIK, PRZELEWY24, TEST_MODE }

@Serializable
enum class PaymentStateDto { PENDING, PAID, FAILED, REFUNDED }

/** No expiry, renewal or rollover logic: credits never expire (DB6), so [validUntil] is always null. */
@Serializable
data class SessionPackageDto(
    val id: String,
    val name: String,
    val trainer: TrainerRefDto,
    val used: Int,
    val total: Int,
    val validUntil: String? = null,
)

@Serializable
data class ChargeDto(
    val id: String,
    val reason: ChargeReasonDto,
    val sessionId: String?,
    val sessionStart: IsoInstant?,
    val trainer: TrainerRefDto,
    val amount: MoneyDto,
    /** Still open; equals [amount] until something is paid, waived or adjusted. */
    val open: MoneyDto,
    val kind: ChargeKindDto,
    val state: ChargeStateDto,
    val createdAt: IsoInstant,
    val dueAt: IsoInstant? = null,
    /** For a package credit: the share of one package entry that was used. */
    val packagePercent: Int? = null,
)

@Serializable
data class PaymentDto(
    val id: String,
    val chargeId: String?,
    val reason: ChargeReasonDto?,
    val trainer: TrainerRefDto,
    val amount: MoneyDto,
    val method: PaymentMethodDto,
    val state: PaymentStateDto,
    val at: IsoInstant,
)

@Serializable
data class WalletDto(
    val packages: List<SessionPackageDto>,
    val charges: List<ChargeDto>,
    val payments: List<PaymentDto>,
    val serverNow: IsoInstant,
)

/** `POST /v1/client/charges/{id}/payments` with the `Idempotency-Key` header. */
@Serializable
data class PayRequestDto(val method: PaymentMethodDto)

/** Always PENDING. [clientSecret] is handed to the provider's payment sheet (Stripe later; the fake provider's id now). */
@Serializable
data class PayResponseDto(val payment: PaymentDto, val clientSecret: String?)
