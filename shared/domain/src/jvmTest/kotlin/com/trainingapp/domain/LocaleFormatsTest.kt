package com.trainingapp.domain

import com.trainingapp.domain.format.LocaleFormats
import kotlin.test.Test
import kotlin.test.assertEquals

class LocaleFormatsTest {
    @Test
    fun polishUsesADecimalComma() {
        assertEquals(',', LocaleFormats.decimalSeparator("pl"))
        assertEquals('.', LocaleFormats.decimalSeparator("en"))
    }
}
