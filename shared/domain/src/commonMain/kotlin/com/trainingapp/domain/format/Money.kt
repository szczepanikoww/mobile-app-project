package com.trainingapp.domain.format

/**
 * An amount of money in integer minor units (grosze for PLN) plus an ISO 4217 currency code. Never a floating
 * point value. Charges, received money, debt and payouts are different values that all use this type.
 */
data class Money(val minor: Long, val currency: String) {
    init {
        require(currency.length == 3) { "Currency must be an ISO 4217 code" }
    }

    companion object {
        fun pln(zloty: Long) = Money(zloty * 100, "PLN")
    }
}
