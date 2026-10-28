package com.trainingapp.contract

import com.trainingapp.contract.text.SearchText
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTextTest {

    @Test
    fun foldsPolishDiacriticsAndCase() {
        assertEquals("wyciskanie sztangi lezac", SearchText.normalize("Wyciskanie SZTANGI leżąc"))
        assertEquals("zolw zrebie gesl cma", SearchText.normalize("Żółw źrebię gęśl ćma"))
    }

    @Test
    fun punctuationBecomesSingleSpaces() {
        assertEquals("barbell bench press medium grip", SearchText.normalize("Barbell Bench Press - Medium Grip"))
        assertEquals("3 4 sit up", SearchText.normalize("  3/4 Sit-Up  "))
        assertEquals(listOf("hip", "thrust"), SearchText.tokens("Hip–Thrust"))
    }
}
