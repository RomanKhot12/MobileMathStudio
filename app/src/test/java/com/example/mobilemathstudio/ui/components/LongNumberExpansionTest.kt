package com.example.mobilemathstudio.ui.components

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.mobilemathstudio.math.DecimalExpansion

/** The long-number viewer must keep digits even when a later CAS request fails. */
class LongNumberExpansionTest {

    @Test
    fun aLaterRequestNeverErasesDigitsAlreadyShown() {
        val first = DecimalExpansion.of("π", 40)
        assertNotNull(first)
        val later = DecimalExpansion.of("π", 80) ?: first
        assertNotNull(later)
        assertTrue(later!!.render().startsWith("3.14159"))
        assertTrue(later.fraction.length >= first!!.fraction.length)
    }

    @Test
    fun repeatingRationalsDoNotReportCompleteAtAShortWindow() {
        val digits = DecimalExpansion.of("6/9", 60)!!
        assertFalse(digits.complete)
        assertTrue(digits.fraction.all { it == '6' })
        assertEquals("0", digits.whole)
    }

    @Test
    fun fullscreenLocksLandscapeAndCompactStaysPortrait() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            longNumberViewerOrientation(fullscreen = true),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            longNumberViewerOrientation(fullscreen = false),
        )
    }
}
