package com.trainingapp.contract.money

/**
 * An amount of money in integer minor units (grosze for PLN) plus an ISO 4217 currency code. Never a floating
 * point value. Charges, received money, debt and payouts are different values that all use this type.
 *
 * Lives in `:contract` so the app's fee preview and the server's charge use one type and one calculation.
 */
@kotlinx.serialization.Serializable
data class Money(val minor: Long, val currency: String) {
    init {
        require(currency.length == 3) { "Currency must be an ISO 4217 code" }
    }

    val isZero: Boolean get() = minor == 0L

    /** [percent] of this amount, rounded half up to a whole minor unit. */
    fun percentOf(percent: Int): Money {
        require(percent in 0..100) { "Percent must be between 0 and 100" }
        return Money((minor * percent + 50) / 100, currency)
    }

    operator fun plus(other: Money): Money {
        require(other.currency == currency) { "Currencies differ" }
        return Money(minor + other.minor, currency)
    }

    companion object {
        fun pln(zloty: Long) = Money(zloty * 100, "PLN")
        fun zero(currency: String) = Money(0, currency)
    }
}
