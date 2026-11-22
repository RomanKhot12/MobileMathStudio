package com.example.mobilemathstudio

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashGuardTest {

    @Test
    fun runReturnsFallbackInsteadOfThrowing() {
        val value = CrashGuard.run("ok") { error("boom") }
        assertEquals("ok", value)
        CrashGuard.run { error("also boom") }
    }

    @Test
    fun roundPxIgnoresNonFiniteValues() {
        assertEquals(0, CrashGuard.roundPx(Float.NaN))
        assertEquals(0, CrashGuard.roundPx(Float.POSITIVE_INFINITY))
        assertEquals(4, CrashGuard.roundPx(3.6f))
    }

    @Test
    fun finiteSubstitutesAFallback() {
        assertEquals(0f, CrashGuard.finite(Float.NaN), 0f)
        assertEquals(2f, CrashGuard.finite(Float.NEGATIVE_INFINITY, 2f), 0f)
        assertEquals(1.5f, CrashGuard.finite(1.5f), 0f)
    }
}
