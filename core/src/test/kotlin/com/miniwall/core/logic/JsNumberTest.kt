package com.miniwall.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsNumberTest {
    @Test
    fun parsesLikeJavaScriptsNumber() {
        assertEquals(5.0, Targeting.jsParse("5"))
        assertEquals(-1.5e3, Targeting.jsParse("-1.5e3"))
        assertEquals(0.5, Targeting.jsParse(".5"))
        assertEquals(255.0, Targeting.jsParse("0xff"))
        assertEquals(5.0, Targeting.jsParse("0b101"))
        assertEquals(Double.POSITIVE_INFINITY, Targeting.jsParse("Infinity"))
        // JavaScript turns these into NaN; the platforms' own parsers accept some of them.
        for (text in listOf("5f", "NaN", "inf", "0x1p3", "1,5", "--1", "5 5", "0xzz", "1e")) assertTrue(Targeting.jsParse(text).isNaN(), text)
    }
}
