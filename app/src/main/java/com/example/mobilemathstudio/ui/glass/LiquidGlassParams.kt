package com.example.mobilemathstudio.ui.glass

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.example.mobilemathstudio.performance.GlassQuality
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometric shapes supported by the Liquid Glass SDF engine.
 */
enum class GlassShapeKind {
    PILL,
    ROUNDED_CARD,
    OVAL,
    CIRCLE,
}

/**
 * Resolved runtime rendering tier for physical Liquid Glass surfaces.
 *
 * Strict activation contract:
 * - [FULL_AGSL] activates ONLY when [GlassQuality.Full] (maximum quality mode) is active
 *   AND the device runs Android 13+ (API 33+) AND `RuntimeShader` compiled cleanly.
 * - [BLUR_FALLBACK] is used in [GlassQuality.Blur] (Balanced) or when API 33 AGSL is unavailable
 *   on API 31+ devices.
 * - [SCRIM_FALLBACK] is used in [GlassQuality.Scrim] (Power Saver) or on API < 31 devices.
 */
enum class PhysicalGlassTier {
    FULL_AGSL,
    BLUR_FALLBACK,
    SCRIM_FALLBACK,
}

/**
 * Deterministically resolves the rendering tier from app-level [glassQuality], Android [sdkInt],
 * and runtime shader compilation status [shaderAvailable].
 */
fun resolvePhysicalGlassTier(
    glassQuality: GlassQuality,
    sdkInt: Int = Build.VERSION.SDK_INT,
    shaderAvailable: Boolean = true,
): PhysicalGlassTier = when (glassQuality) {
    GlassQuality.Full -> when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU && shaderAvailable -> PhysicalGlassTier.FULL_AGSL
        sdkInt >= Build.VERSION_CODES.S -> PhysicalGlassTier.BLUR_FALLBACK
        else -> PhysicalGlassTier.SCRIM_FALLBACK
    }
    GlassQuality.Blur -> {
        if (sdkInt >= Build.VERSION_CODES.S) PhysicalGlassTier.BLUR_FALLBACK
        else PhysicalGlassTier.SCRIM_FALLBACK
    }
    GlassQuality.Scrim -> PhysicalGlassTier.SCRIM_FALLBACK
}

/**
 * Complete set of 17 physically grounded Liquid Glass optical parameters ported from the
 * reference AGSL prototype (`liquid_glass`).
 *
 * Note on [dispersion]: the reference slider spans `0.0..0.15`, displayed as `0%..100%`
 * via `(dispersion / 0.15f * 100f)`. Thus `59%` dispersion equals `0.59f * 0.15f = 0.0885f`.
 */
@Immutable
data class LiquidGlassParams(
    // 1. Blur & rim clarity
    val blurRadiusDp: Float = 8.4f,
    val rimCrispness: Float = 0.24f,

    // 2. Peripheral Snell refraction & chromatic dispersion
    val refractionStrength: Float = 0.12f,
    val bevelWidthDp: Float = 8f,
    val ior: Float = 1.85f,
    val dispersion: Float = 0.0885f,

    // 3. Symmetrical mirror reflection across rim axis
    val mirrorStrength: Float = 0.85f,
    val mirrorAxisDp: Float = 15.9f,
    val showMirrorAxisOverlay: Boolean = false,

    // 4. Directional cut lighting, opposite-side shadow, and Fresnel hairline
    val lightAngleDeg: Float = 138f,
    val highlightIntensity: Float = 0.83f,
    val highlightWidthDp: Float = 2.70f,
    val shadowIntensity: Float = 0.57f,
    val fresnelIntensity: Float = 0.67f,

    // 5. Glass material tone & geometry
    val tintAlpha: Float = 0.02f,
    val saturationBoost: Float = 1.14f,
    val darkGlass: Boolean = false,
    val cornerRadiusDp: Float = 28f,
    val shapeKind: GlassShapeKind = GlassShapeKind.PILL,
) {
    /**
     * Normalized 2D screen-space unit vector pointing TOWARD the light source.
     * 0° = right (+x), 90° = top (-y), 138° = upper-left (-0.743, -0.669).
     */
    fun lightDirectionXY(): Pair<Float, Float> {
        val rad = Math.toRadians(lightAngleDeg.toDouble())
        val lx = cos(rad).toFloat()
        val ly = -sin(rad).toFloat()
        return lx to ly
    }

    fun tintRgb(): Triple<Float, Float, Float> = if (darkGlass) {
        Triple(0.08f, 0.10f, 0.15f)
    } else {
        Triple(0.95f, 0.965f, 0.985f)
    }

    fun tintComposeColor(): Color = if (darkGlass) {
        Color(0xFF141822)
    } else {
        Color(0xFFF3F6FA)
    }

    fun sanitized(): LiquidGlassParams = copy(
        blurRadiusDp = blurRadiusDp.coerceIn(0f, 36f),
        rimCrispness = rimCrispness.coerceIn(0f, 1f),
        refractionStrength = refractionStrength.coerceIn(0f, 1f),
        bevelWidthDp = bevelWidthDp.coerceIn(4f, 44f),
        ior = ior.coerceIn(1.01f, 2.20f),
        dispersion = dispersion.coerceIn(0f, LiquidGlassPresets.DISPERSION_MAX),
        mirrorStrength = mirrorStrength.coerceIn(0f, 1f),
        mirrorAxisDp = mirrorAxisDp.coerceIn(2f, 24f),
        lightAngleDeg = ((lightAngleDeg % 360f) + 360f) % 360f,
        highlightIntensity = highlightIntensity.coerceIn(0f, 1f),
        highlightWidthDp = highlightWidthDp.coerceIn(0.6f, 6.0f),
        shadowIntensity = shadowIntensity.coerceIn(0f, 1f),
        fresnelIntensity = fresnelIntensity.coerceIn(0f, 1f),
        tintAlpha = tintAlpha.coerceIn(0f, 0.65f),
        saturationBoost = saturationBoost.coerceIn(0.6f, 1.6f),
        cornerRadiusDp = cornerRadiusDp.coerceIn(4f, 64f),
    )
}

/**
 * Exact calibrated presets for MobileMathStudio UI elements.
 */
object LiquidGlassPresets {
    /** Upper bound of the reference dispersion slider (`0%..100%` maps to `0f..0.15f`). */
    const val DISPERSION_MAX: Float = 0.15f

    /** Shared Gaussian blur radius (dp) used by both top bar and keyboard keys. */
    const val SHARED_BLUR_RADIUS_DP: Float = 8.4f

    /** 59% on the reference dispersion scale (`0.59 * 0.15 = 0.0885`) for crisp chromatic rim shimmer. */
    const val CALIBRATED_DISPERSION: Float = 0.59f * DISPERSION_MAX

    /**
     * Floating top bar (`FloatingTopBar`) — all 17 exact parameters:
     * Blur 8.4dp, Edge Clarity 24%, Refraction 12%, Bevel 8dp, IOR 1.85,
     * Dispersion 59% (0.0885), Mirror 85%, Axis Depth 15.9dp, Debug Axis OFF,
     * Light 138°, Edge Highlight 83%, Rim Thickness 2.70dp, Backside Shadow 57%,
     * Fresnel 67%, Glass Tint 2%, Saturation 114%, Dark Glass = true in BOTH dark and light themes.
     */
    fun floatingTopBar(@Suppress("UNUSED_PARAMETER") dark: Boolean = true): LiquidGlassParams = LiquidGlassParams(
        blurRadiusDp = SHARED_BLUR_RADIUS_DP,
        rimCrispness = 0.24f,
        refractionStrength = 0.12f,
        bevelWidthDp = 8f,
        ior = 1.85f,
        dispersion = CALIBRATED_DISPERSION,
        mirrorStrength = 0.85f,
        mirrorAxisDp = 15.9f,
        showMirrorAxisOverlay = false,
        lightAngleDeg = 138f,
        highlightIntensity = 0.83f,
        highlightWidthDp = 2.70f,
        shadowIntensity = 0.57f,
        fresnelIntensity = 0.67f,
        tintAlpha = 0.02f,
        saturationBoost = 1.14f,
        darkGlass = true,
        cornerRadiusDp = 28f,
        shapeKind = GlassShapeKind.PILL,
    )

    /**
     * Math keyboard key (`MathKeyboard`) optical spec:
     * Rim / Facet Thickness = `0.94 dp`, Edge Highlight = `51%`, Reflection Axis Depth = `15.9 dp`,
     * Dispersion = `59%`, Dark Glass matched to theme.
     * Rendered via a single batched 2-pass keyboard AGSL engine (1 downsampled 0.5× blur pass +
     * 1 sharp lighting/refraction pass for ALL floating keys combined) to preserve 60 FPS on Mali-G57.
     */
    fun keyboardKey(dark: Boolean): LiquidGlassParams = floatingTopBar(dark).copy(
        highlightWidthDp = 0.94f,
        highlightIntensity = 0.51f,
        darkGlass = dark,
        cornerRadiusDp = 14f,
        shapeKind = GlassShapeKind.ROUNDED_CARD,
    )
}
