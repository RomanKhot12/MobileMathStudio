package com.example.mobilemathstudio.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DecimalDisplayTest {

    @Test
    fun defaultIsTenPlaces() {
        assertEquals(10, AppSettings().decimalPlaces)
        assertEquals(10, DecimalDisplay.DEFAULT_PLACES)
    }

    @Test
    fun roundsAPlainDecimal() {
        assertEquals("1.23", DecimalDisplay.applyPlaces("1.23456", 2))
        assertEquals("2", DecimalDisplay.applyPlaces("1.9", 0))
    }

    @Test
    fun roundsNumbersInsideAnAnswerLine() {
        assertEquals("x = 1.23", DecimalDisplay.applyPlaces("x = 1.23456", 2))
        assertEquals("x = 1.23\ny = 4.57", DecimalDisplay.applyPlaces("x = 1.234\ny = 4.567", 2))
    }

    @Test
    fun leavesIntegersAndExactTokensAlone() {
        assertEquals("x = 2", DecimalDisplay.applyPlaces("x = 2", 4))
        assertEquals("π", DecimalDisplay.applyPlaces("π", 4))
    }

    @Test
    fun clampsOutOfRangeSettings() {
        assertEquals(0, DecimalDisplay.clamp(-3))
        assertEquals(15, DecimalDisplay.clamp(99))
    }
}
