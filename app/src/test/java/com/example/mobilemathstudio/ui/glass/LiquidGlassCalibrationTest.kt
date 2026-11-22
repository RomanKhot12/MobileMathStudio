package com.example.mobilemathstudio.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.mobilemathstudio.performance.GlassQuality
import com.example.mobilemathstudio.settings.AppSettings
import com.example.mobilemathstudio.ui.keyboard.KeyRole
import com.example.mobilemathstudio.ui.keyboard.darkenForLightInk
import com.example.mobilemathstudio.ui.keyboard.keyPalette
import com.example.mobilemathstudio.ui.theme.GraphSeriesPaletteLight
import com.example.mobilemathstudio.ui.theme.WinLightCanvas
import com.example.mobilemathstudio.ui.theme.WinLightCardTop
import com.example.mobilemathstudio.ui.theme.WinLightGridLine
import com.example.mobilemathstudio.ui.theme.WinLightKeyDigit
import com.example.mobilemathstudio.ui.theme.WinLightKeyFunction
import com.example.mobilemathstudio.ui.theme.WinLightKeyboardTray
import com.example.mobilemathstudio.ui.theme.WinLightSurface
import com.example.mobilemathstudio.ui.theme.WinLightTextPrimary
import com.example.mobilemathstudio.ui.theme.WindowsAccentBlue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class LiquidGlassCalibrationTest {

    @Test
    fun floatingTopBarPresetMatchesAll17ExactParametersAndForcesDarkGlassInLightTheme() {
        for (dark in listOf(false, true)) {
            val p = LiquidGlassPresets.floatingTopBar(dark)
            assertEquals(8.4f, p.blurRadiusDp, 1e-5f)
            assertEquals(0.24f, p.rimCrispness, 1e-5f)
            assertEquals(0.12f, p.refractionStrength, 1e-5f)
            assertEquals(8f, p.bevelWidthDp, 1e-5f)
            assertEquals(1.85f, p.ior, 1e-5f)
            // 59% of the reference 0..0.15 dispersion range = 0.0885 (within 58%..60%)
            val dispersionPct = (p.dispersion / LiquidGlassPresets.DISPERSION_MAX) * 100f
            assertTrue("Dispersion should be in 58%..60%, was $dispersionPct%", dispersionPct in 58f..60f)
            assertEquals(0.0885f, p.dispersion, 1e-5f)
            assertEquals(0.85f, p.mirrorStrength, 1e-5f)
            assertEquals(15.9f, p.mirrorAxisDp, 1e-5f)
            assertFalse("Debug red mirror axis must be disabled", p.showMirrorAxisOverlay)
            assertEquals(138f, p.lightAngleDeg, 1e-5f)
            assertEquals(0.83f, p.highlightIntensity, 1e-5f)
            assertEquals(2.70f, p.highlightWidthDp, 1e-5f)
            assertEquals(0.57f, p.shadowIntensity, 1e-5f)
            assertEquals(0.67f, p.fresnelIntensity, 1e-5f)
            assertEquals(0.02f, p.tintAlpha, 1e-5f)
            assertEquals(1.14f, p.saturationBoost, 1e-5f)
            assertTrue("FloatingTopBar must enable Dark Glass in both dark and light themes", p.darkGlass)
            assertEquals(GlassShapeKind.PILL, p.shapeKind)
            assertEquals(p, p.sanitized())
        }
    }

    @Test
    fun keyboardKeyPresetRetainsCompactRimAndHighlightCalibration() {
        for (dark in listOf(false, true)) {
            val top = LiquidGlassPresets.floatingTopBar(dark)
            val key = LiquidGlassPresets.keyboardKey(dark)

            assertEquals(0.94f, key.highlightWidthDp, 1e-5f)
            assertEquals(0.51f, key.highlightIntensity, 1e-5f)
            assertEquals(14f, key.cornerRadiusDp, 1e-5f)
            assertEquals(GlassShapeKind.ROUNDED_CARD, key.shapeKind)
            assertEquals(dark, key.darkGlass)

            assertEquals(top.blurRadiusDp, key.blurRadiusDp, 1e-5f)
            assertEquals(top.rimCrispness, key.rimCrispness, 1e-5f)
            assertEquals(top.refractionStrength, key.refractionStrength, 1e-5f)
            assertEquals(top.bevelWidthDp, key.bevelWidthDp, 1e-5f)
            assertEquals(top.ior, key.ior, 1e-5f)
            assertEquals(top.dispersion, key.dispersion, 1e-5f)
            assertEquals(top.mirrorStrength, key.mirrorStrength, 1e-5f)
            assertEquals(top.mirrorAxisDp, key.mirrorAxisDp, 1e-5f)
            assertFalse(key.showMirrorAxisOverlay)
            assertEquals(top.lightAngleDeg, key.lightAngleDeg, 1e-5f)
            assertEquals(top.shadowIntensity, key.shadowIntensity, 1e-5f)
            assertEquals(top.fresnelIntensity, key.fresnelIntensity, 1e-5f)
            assertEquals(top.tintAlpha, key.tintAlpha, 1e-5f)
            assertEquals(top.saturationBoost, key.saturationBoost, 1e-5f)
            assertEquals(key, key.sanitized())
        }
    }

    @Test
    fun activationGateStrictlyRestrictsAgslToFullQualityOnApi33Plus() {
        assertEquals(
            PhysicalGlassTier.FULL_AGSL,
            resolvePhysicalGlassTier(GlassQuality.Full, sdkInt = 33, shaderAvailable = true),
        )
        assertEquals(
            PhysicalGlassTier.FULL_AGSL,
            resolvePhysicalGlassTier(GlassQuality.Full, sdkInt = 36, shaderAvailable = true),
        )
        assertEquals(
            PhysicalGlassTier.BLUR_FALLBACK,
            resolvePhysicalGlassTier(GlassQuality.Full, sdkInt = 34, shaderAvailable = false),
        )
        assertEquals(
            PhysicalGlassTier.BLUR_FALLBACK,
            resolvePhysicalGlassTier(GlassQuality.Full, sdkInt = 32, shaderAvailable = true),
        )
        assertEquals(
            PhysicalGlassTier.BLUR_FALLBACK,
            resolvePhysicalGlassTier(GlassQuality.Full, sdkInt = 31, shaderAvailable = true),
        )
        assertEquals(
            PhysicalGlassTier.SCRIM_FALLBACK,
            resolvePhysicalGlassTier(GlassQuality.Full, sdkInt = 30, shaderAvailable = true),
        )
        assertEquals(
            PhysicalGlassTier.BLUR_FALLBACK,
            resolvePhysicalGlassTier(GlassQuality.Blur, sdkInt = 36, shaderAvailable = true),
        )
        assertEquals(
            PhysicalGlassTier.SCRIM_FALLBACK,
            resolvePhysicalGlassTier(GlassQuality.Blur, sdkInt = 30, shaderAvailable = true),
        )
        assertEquals(
            PhysicalGlassTier.SCRIM_FALLBACK,
            resolvePhysicalGlassTier(GlassQuality.Scrim, sdkInt = 36, shaderAvailable = true),
        )
    }

    @Test
    fun liquidGlassSettingDefaultsOnAndDisablesToScrimTier() {
        val defaults = AppSettings()
        assertTrue(defaults.liquidGlassEnabled)
        val disabled = defaults.copy(liquidGlassEnabled = false)
        assertFalse(disabled.liquidGlassEnabled)
        // When liquidGlassEnabled == false, MathCadTheme maps GlassQuality -> Scrim
        val effectiveQuality = if (disabled.liquidGlassEnabled) GlassQuality.Full else GlassQuality.Scrim
        assertEquals(
            PhysicalGlassTier.SCRIM_FALLBACK,
            resolvePhysicalGlassTier(effectiveQuality, sdkInt = 36, shaderAvailable = true),
        )
    }

    @Test
    fun lightDirectionAt138DegreesIsUpperLeftUnitVector() {
        val (lx, ly) = LiquidGlassPresets.floatingTopBar(dark = false).lightDirectionXY()
        assertEquals(1.0, hypot(lx.toDouble(), ly.toDouble()), 1e-4)
        assertTrue("138° points left (lx < 0)", lx < -0.7f)
        assertTrue("138° points up in screen space (ly < 0)", ly < -0.6f)
    }

    @Test
    fun clampedBackdropBoundsNeverExceedLayerOrInvert() {
        val b = computeClampedBackdropBounds(
            originX = -20f,
            originY = -10f,
            padPx = 0f,
            layerW = 300f,
            layerH = 64f,
            rootW = 1080f,
            rootH = 2400f,
        )
        assertTrue(b[0] >= 20f)
        assertTrue(b[1] >= 10f)
        assertTrue(b[2] <= 299.5f)
        assertTrue(b[3] <= 63.5f)
        assertTrue(b[0] <= b[2] && b[1] <= b[3])
    }

    @Test
    fun agslShadersContainAllRequiredUniformsAndNoForbiddenGlslTokens() {
        val blurred = LiquidGlassShaders.BLURRED_PASS_AGSL
        val sharp = LiquidGlassShaders.SHARP_AND_LIGHTING_PASS_AGSL
        val kbdBlurred = LiquidGlassShaders.KEYBOARD_BLURRED_PASS_AGSL
        val kbdSharp = LiquidGlassShaders.KEYBOARD_SHARP_AND_LIGHTING_PASS_AGSL

        for (src in listOf(blurred, sharp, kbdBlurred, kbdSharp)) {
            assertFalse("AGSL must not use gl_FragCoord", src.contains("gl_FragCoord"))
            assertFalse("AGSL must not use texture2D", src.contains("texture2D"))
            assertTrue(src.contains("uniform shader backdrop;"))
            assertTrue(src.contains("uniform float uIor;"))
            assertTrue(src.contains("uniform float uDispersion;"))
            assertTrue(src.contains("uniform float uMirrorStrength;"))
            assertTrue(src.contains("uniform float uMirrorAxis;"))
            assertTrue(src.contains("uniform float uSaturationBoost;"))
        }
        for (src in listOf(sharp, kbdSharp)) {
            assertTrue(src.contains("uniform float uHighlightIntensity;"))
            assertTrue(src.contains("uniform float uHighlightWidth;"))
            assertTrue(src.contains("uniform float uShadowIntensity;"))
            assertTrue(src.contains("uniform float uFresnelIntensity;"))
            assertTrue(src.contains("uniform float uRimCrispness;"))
        }
        assertTrue(sharp.contains("uniform float uShowMirrorAxis;"))

        // Batched keyboard shaders must support all keys + chevron in a single pass with transparent gaps.
        assertTrue(LiquidGlassShaders.MAX_KEYBOARD_KEYS >= 43)
        for (src in listOf(kbdBlurred, kbdSharp)) {
            assertTrue(src.contains("uniform float uKeyCount;"))
            assertTrue(src.contains("uniform float4 uKeyRects[48];"))
            assertTrue(src.contains("uniform float4 uKeyTints[48];"))
            assertTrue(src.contains("uniform float uKeyRadii[48];"))
            assertTrue(src.contains("return half4(0.0);"))
        }
    }

    @Test
    fun keyboardPreservesFiveDistinctFunctionalGroupsInBothThemesAndGlassModes() {
        val groups = listOf(
            KeyRole.Digit,
            KeyRole.Operator,
            KeyRole.Template,
            KeyRole.Utility,
            KeyRole.Primary,
        )
        for (dark in listOf(false, true)) {
            for (glassLook in listOf(false, true)) {
                val palettes = groups.map { keyPalette(it, WindowsAccentBlue, dark, glassLook) }
                val fills = palettes.map { it.fill }
                assertEquals(
                    "All 5 functional groups must have distinct fills (dark=$dark, glass=$glassLook)",
                    groups.size,
                    fills.toSet().size,
                )
                val glassFilters = palettes.map { it.glassTint to it.glassTintAlpha }
                assertEquals(
                    "All 5 functional groups must have distinct translucent Liquid Glass filters (dark=$dark)",
                    groups.size,
                    glassFilters.toSet().size,
                )
                palettes.forEach { p ->
                    assertTrue(
                        "Floating glass key tint alpha must let live canvas show through (was ${p.glassTintAlpha})",
                        p.glassTintAlpha in 0.05f..0.85f,
                    )
                }
            }
        }
        // Verify exact cool slate-blue light keyboard palette
        assertEquals(WinLightKeyDigit, keyPalette(KeyRole.Digit, WindowsAccentBlue, dark = false, glassLook = true).fill)
        assertEquals(WinLightKeyFunction, keyPalette(KeyRole.Function, WindowsAccentBlue, dark = false, glassLook = true).fill)
        assertEquals(WinLightTextPrimary, keyPalette(KeyRole.Digit, WindowsAccentBlue, dark = false, glassLook = true).content)
        assertTrue(darkenForLightInk(WindowsAccentBlue).luminance() < 0.20f)
    }

    @Test
    fun lightThemeOverhaulUsesCoolSlateBluePaletteWithZeroGlaringWhite() {
        assertNotEquals(Color.White, WinLightCanvas)
        assertNotEquals(Color.White, WinLightSurface)
        assertNotEquals(Color.White, WinLightCardTop)
        assertNotEquals(Color.White, WinLightKeyDigit)
        assertNotEquals(Color.Black, WinLightTextPrimary)
        assertEquals(Color(0xFFE8ECF2), WinLightCanvas)
        assertEquals(Color(0xFFD0D7E2), WinLightGridLine)
        assertEquals(Color(0xFFDCE2EC), WinLightKeyboardTray)
        assertEquals(Color(0xFFF2F5F9), WinLightKeyDigit)
        assertEquals(Color(0xFFE1E7F0), WinLightKeyFunction)
        assertEquals(Color(0xFFF4F7FB), WinLightCardTop)
        assertEquals(Color(0xFF161B22), WinLightTextPrimary)
        assertEquals(6, GraphSeriesPaletteLight.size)
        GraphSeriesPaletteLight.forEach { traceColor ->
            assertTrue("Light graph trace $traceColor should have comfortable contrast", traceColor.luminance() < 0.35f)
        }
    }
}
