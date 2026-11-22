package com.example.mobilemathstudio.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPerformanceControllerTest {
    @Test
    fun hysteresisRequiresStableWindow() {
        val c = AutoPerformanceController()
        val t0 = 1_000_000L
        // Start high
        var snap = c.resolve(PerformanceMode.Auto, fps = 60f, isPowerSave = false, nowMs = t0)
        assertEquals(PerformanceMode.High, snap.effectiveMode)

        // Brief dip below 45 — should NOT switch yet
        snap = c.resolve(PerformanceMode.Auto, fps = 40f, isPowerSave = false, nowMs = t0 + 500)
        assertEquals(PerformanceMode.High, snap.effectiveMode)

        // Still unstable window
        snap = c.resolve(PerformanceMode.Auto, fps = 40f, isPowerSave = false, nowMs = t0 + 1500)
        assertEquals(PerformanceMode.High, snap.effectiveMode)

        // After stable duration — switch
        snap = c.resolve(PerformanceMode.Auto, fps = 40f, isPowerSave = false, nowMs = t0 + 500 + AutoPerformanceController.STABLE_MS)
        assertEquals(PerformanceMode.PowerSaver, snap.effectiveMode)
        assertFalse(snap.animationsEnabled)
    }

    @Test
    fun interactionLockFreezesMode() {
        val c = AutoPerformanceController()
        val t0 = 2_000_000L
        c.resolve(PerformanceMode.Auto, 60f, false, t0)
        c.notifyInteraction(t0)
        val snap = c.resolve(PerformanceMode.Auto, fps = 30f, isPowerSave = false, nowMs = t0 + 200)
        assertTrue(snap.interactionLocked)
        assertEquals(PerformanceMode.High, snap.effectiveMode)
    }

    @Test
    fun powerSaverDisablesMotionWhileHighAndBalancedKeepIt() {
        val saver = PerformanceSnapshot.fromEffective(
            PerformanceMode.PowerSaver,
            PerformanceMode.PowerSaver,
            60f,
            false,
        )
        assertFalse(saver.animationsEnabled)

        val high = PerformanceSnapshot.fromEffective(
            PerformanceMode.High,
            PerformanceMode.High,
            60f,
            false,
        )
        assertTrue(high.animationsEnabled)

        val balanced = PerformanceSnapshot.fromEffective(
            PerformanceMode.Balanced,
            PerformanceMode.Balanced,
            60f,
            false,
        )
        assertTrue(balanced.animationsEnabled)
    }

    @Test
    fun motionTokensSnapWhenAnimationsAreDisabled() {
        val off = Motion.spatial<Float>(enabled = false)
        val on = Motion.spatial<Float>(enabled = true)
        assertTrue(off is androidx.compose.animation.core.SnapSpec)
        assertTrue(on is androidx.compose.animation.core.SpringSpec)
        val fxOff = Motion.effects<Float>(enabled = false, durationMillis = 280)
        assertTrue(fxOff is androidx.compose.animation.core.SnapSpec)
    }
}
