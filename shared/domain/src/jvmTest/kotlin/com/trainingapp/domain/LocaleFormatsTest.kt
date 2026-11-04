package com.trainingapp.domain

import com.trainingapp.domain.format.LocaleFormats
import com.trainingapp.domain.format.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocaleFormatsTest {
    private fun String.plainSpaces() = replace(' ', ' ').replace(' ', ' ')

    @Test
    fun polishUsesADecimalComma() {
        assertEquals(',', LocaleFormats.decimalSeparator("pl"))
        assertEquals('.', LocaleFormats.decimalSeparator("en"))
    }

    @Test
    fun wholeAmountsHaveNoDecimals() {
        assertEquals("120 zł", LocaleFormats.formatMoney(Money.pln(120), "pl").plainSpaces())
        assertEquals("1 200 zł", LocaleFormats.formatMoney(Money.pln(1200), "pl").plainSpaces())
    }

    @Test
    fun fractionalAmountsKeepMinorUnits() {
        assertEquals("120,50 zł", LocaleFormats.formatMoney(Money(12_050, "PLN"), "pl").plainSpaces())
        assertEquals("€12.05", LocaleFormats.formatMoney(Money(1_205, "EUR"), "en-IE").plainSpaces())
    }

    @Test
    fun decimalsFollowTheLocale() {
        assertEquals("4,9", LocaleFormats.formatDecimal(4.9, "pl"))
        assertEquals("4.9", LocaleFormats.formatDecimal(4.9, "en"))
        assertEquals("5", LocaleFormats.formatDecimal(5.0, "pl"))
    }

    @Test
    fun currencyMustBeAnIsoCode() {
        assertFailsWith<IllegalArgumentException> { Money(100, "zł") }
    }
}
