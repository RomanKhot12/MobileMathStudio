package com.example.mobilemathstudio.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import kotlin.math.abs

/**
 * Isolated Android 13+ (API 33) `RuntimeShader` + `RenderEffect` factory shared across all
 * physical Liquid Glass surfaces (top bar + keyboard keys).
 *
 * Performance & compatibility guarantees:
 * - Instantiated ONLY behind `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU` so older
 *   ART runtimes never class-load `android.graphics.RuntimeShader`.
 * - Compiles the two AGSL shaders ONCE per screen session instead of once per button.
 * - Because `RenderEffect.createRuntimeShaderEffect(shader, "backdrop")` snapshots current
 *   uniform values synchronously on the UI thread, one shared renderer safely mints cached
 *   per-surface `RenderEffect` objects without allocating or updating uniforms during pan/scroll.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class LiquidGlassApi33Renderer {

    private var blurredPassShader: RuntimeShader? = null
    private var sharpAndLightingShader: RuntimeShader? = null
    private var kbdBlurredPassShader: RuntimeShader? = null
    private var kbdSharpPassShader: RuntimeShader? = null
    private var cachedBlurPx: Float = -1f
    private var cachedBlurEffect: RenderEffect? = null
    private var cachedHalfBlurPx: Float = -1f
    private var cachedHalfBlurEffect: RenderEffect? = null

    var isAvailable: Boolean = false
        private set

    var isKeyboardBatchAvailable: Boolean = false
        private set

    init {
        val result = runCatching {
            blurredPassShader = RuntimeShader(LiquidGlassShaders.BLURRED_PASS_AGSL)
            sharpAndLightingShader = RuntimeShader(LiquidGlassShaders.SHARP_AND_LIGHTING_PASS_AGSL)
        }
        isAvailable = result.isSuccess

        val kbdResult = runCatching {
            kbdBlurredPassShader = RuntimeShader(LiquidGlassShaders.KEYBOARD_BLURRED_PASS_AGSL)
            kbdSharpPassShader = RuntimeShader(LiquidGlassShaders.KEYBOARD_SHARP_AND_LIGHTING_PASS_AGSL)
        }
        isKeyboardBatchAvailable = kbdResult.isSuccess
    }

    private fun blurEffectFor(blurPx: Float): RenderEffect {
        val existing = cachedBlurEffect
        if (existing != null && abs(cachedBlurPx - blurPx) <= 0.25f) {
            return existing
        }
        val created = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
        cachedBlurPx = blurPx
        cachedBlurEffect = created
        return created
    }

    private fun halfBlurEffectFor(blurPx: Float): RenderEffect {
        val existing = cachedHalfBlurEffect
        if (existing != null && abs(cachedHalfBlurPx - blurPx) <= 0.25f) {
            return existing
        }
        val created = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
        cachedHalfBlurPx = blurPx
        cachedHalfBlurEffect = created
        return created
    }

    /**
     * Pass 1: chains `BLURRED_PASS_AGSL` directly after a cached GPU Gaussian blur
     * over the compact cropped surface layer.
     */
    fun createBlurredPassEffect(
        blurPx: Float,
        padPx: Float,
        widthPx: Float,
        heightPx: Float,
        backdropBounds: FloatArray,
        cornerRadiusPx: Float,
        isEllipse: Boolean,
        bevelWidthPx: Float,
        mirrorAxisPx: Float,
        params: LiquidGlassParams,
        touchLocal: Offset?,
        touchActive: Float,
    ): ComposeRenderEffect? {
        val shader = blurredPassShader ?: return null
        if (!isAvailable || blurPx <= 0.5f) return null
        return runCatching {
            applyBlurredPassUniforms(
                shader = shader,
                padPx = padPx,
                widthPx = widthPx,
                heightPx = heightPx,
                backdropBounds = backdropBounds,
                cornerRadiusPx = cornerRadiusPx,
                isEllipse = isEllipse,
                bevelWidthPx = bevelWidthPx,
                mirrorAxisPx = mirrorAxisPx,
                params = params,
                touchLocal = touchLocal,
                touchActive = touchActive,
            )
            val blurEffect = blurEffectFor(blurPx)
            val shaderEffect = RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
            RenderEffect.createChainEffect(shaderEffect, blurEffect).asComposeRenderEffect()
        }.getOrNull()
    }

    private fun applyBlurredPassUniforms(
        shader: RuntimeShader,
        padPx: Float,
        widthPx: Float,
        heightPx: Float,
        backdropBounds: FloatArray,
        cornerRadiusPx: Float,
        isEllipse: Boolean,
        bevelWidthPx: Float,
        mirrorAxisPx: Float,
        params: LiquidGlassParams,
        touchLocal: Offset?,
        touchActive: Float,
    ) {
        val (tr, tg, tb) = params.tintRgb()
        val tx = (touchLocal?.x ?: (widthPx * 0.5f)) - widthPx * 0.5f
        val ty = (touchLocal?.y ?: (heightPx * 0.5f)) - heightPx * 0.5f

        shader.setFloatUniform("uOrigin", padPx, padPx)
        shader.setFloatUniform("uSize", widthPx, heightPx)
        shader.setFloatUniform(
            "uBackdropBounds",
            backdropBounds[0], backdropBounds[1], backdropBounds[2], backdropBounds[3],
        )
        shader.setFloatUniform("uCornerRadius", cornerRadiusPx)
        shader.setFloatUniform("uShapeType", if (isEllipse) 1f else 0f)
        shader.setFloatUniform("uBevelWidth", bevelWidthPx)
        shader.setFloatUniform("uRefractionStrength", params.refractionStrength)
        shader.setFloatUniform("uIor", params.ior)
        shader.setFloatUniform("uDispersion", params.dispersion)
        shader.setFloatUniform("uMirrorStrength", params.mirrorStrength)
        shader.setFloatUniform("uMirrorAxis", mirrorAxisPx)
        shader.setFloatUniform("uTintColor", tr, tg, tb)
        shader.setFloatUniform("uTintAlpha", params.tintAlpha)
        shader.setFloatUniform("uSaturationBoost", params.saturationBoost)
        shader.setFloatUniform("uTouchPos", tx, ty)
        shader.setFloatUniform("uTouchActive", touchActive)
    }

    /**
     * Pass 2: sharp unblurred rim/mirror optics + directional edge cut highlight,
     * opposite-side shadow, and Fresnel hairline.
     */
    fun createSharpAndLightingEffect(
        hasBlurPass: Boolean,
        widthPx: Float,
        heightPx: Float,
        backdropBounds: FloatArray,
        cornerRadiusPx: Float,
        isEllipse: Boolean,
        bevelWidthPx: Float,
        mirrorAxisPx: Float,
        highlightWidthPx: Float,
        params: LiquidGlassParams,
        touchLocal: Offset?,
        touchActive: Float,
    ): ComposeRenderEffect? {
        val shader = sharpAndLightingShader ?: return null
        if (!isAvailable) return null
        return runCatching {
            val (lx, ly) = params.lightDirectionXY()
            val (tr, tg, tb) = params.tintRgb()
            val tx = (touchLocal?.x ?: (widthPx * 0.5f)) - widthPx * 0.5f
            val ty = (touchLocal?.y ?: (heightPx * 0.5f)) - heightPx * 0.5f

            shader.setFloatUniform("uOrigin", 0f, 0f)
            shader.setFloatUniform("uSize", widthPx, heightPx)
            shader.setFloatUniform(
                "uBackdropBounds",
                backdropBounds[0], backdropBounds[1], backdropBounds[2], backdropBounds[3],
            )
            shader.setFloatUniform("uCornerRadius", cornerRadiusPx)
            shader.setFloatUniform("uShapeType", if (isEllipse) 1f else 0f)
            shader.setFloatUniform("uBevelWidth", bevelWidthPx)
            shader.setFloatUniform("uRefractionStrength", params.refractionStrength)
            shader.setFloatUniform("uIor", params.ior)
            shader.setFloatUniform("uDispersion", params.dispersion)
            shader.setFloatUniform("uMirrorStrength", params.mirrorStrength)
            shader.setFloatUniform("uMirrorAxis", mirrorAxisPx)
            shader.setFloatUniform("uShowMirrorAxis", if (params.showMirrorAxisOverlay) 1f else 0f)
            shader.setFloatUniform("uHasBlurPass", if (hasBlurPass) 1f else 0f)
            shader.setFloatUniform("uRimCrispness", params.rimCrispness)
            shader.setFloatUniform("uLightDir", lx, ly)
            shader.setFloatUniform("uHighlightIntensity", params.highlightIntensity)
            shader.setFloatUniform("uHighlightWidth", highlightWidthPx)
            shader.setFloatUniform("uShadowIntensity", params.shadowIntensity)
            shader.setFloatUniform("uFresnelIntensity", params.fresnelIntensity)
            shader.setFloatUniform("uTintColor", tr, tg, tb)
            shader.setFloatUniform("uTintAlpha", params.tintAlpha)
            shader.setFloatUniform("uSaturationBoost", params.saturationBoost)
            shader.setFloatUniform("uTouchPos", tx, ty)
            shader.setFloatUniform("uTouchActive", touchActive)

            RenderEffect.createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()
        }.getOrNull()
    }

    /**
     * Batched Keyboard Pass 1: ONE downsampled (0.5×) Gaussian blur chained into ONE
     * `KEYBOARD_BLURRED_PASS_AGSL` pass covering ALL floating keys + collapse chevron.
     */
    fun createKeyboardBlurredPassEffect(
        blurPxHalf: Float,
        padPxHalf: Float,
        backdropBounds: FloatArray,
        keyCount: Int,
        keyRectsHalf: FloatArray,
        keyTints: FloatArray,
        keyRadiiHalf: FloatArray,
        bevelWidthPxHalf: Float,
        mirrorAxisPxHalf: Float,
        params: LiquidGlassParams,
    ): ComposeRenderEffect? {
        val shader = kbdBlurredPassShader ?: return null
        if (!isKeyboardBatchAvailable || blurPxHalf <= 0.5f || keyCount <= 0) return null
        return runCatching {
            val (tr, tg, tb) = params.tintRgb()
            shader.setFloatUniform("uOrigin", padPxHalf, padPxHalf)
            shader.setFloatUniform(
                "uBackdropBounds",
                backdropBounds[0], backdropBounds[1], backdropBounds[2], backdropBounds[3],
            )
            shader.setFloatUniform("uKeyCount", keyCount.toFloat())
            shader.setFloatUniform("uKeyRects", keyRectsHalf)
            shader.setFloatUniform("uKeyTints", keyTints)
            shader.setFloatUniform("uKeyRadii", keyRadiiHalf)
            shader.setFloatUniform("uBevelWidth", bevelWidthPxHalf)
            shader.setFloatUniform("uRefractionStrength", params.refractionStrength)
            shader.setFloatUniform("uIor", params.ior)
            shader.setFloatUniform("uDispersion", params.dispersion)
            shader.setFloatUniform("uMirrorStrength", params.mirrorStrength)
            shader.setFloatUniform("uMirrorAxis", mirrorAxisPxHalf)
            shader.setFloatUniform("uTintColor", tr, tg, tb)
            shader.setFloatUniform("uTintAlpha", params.tintAlpha)
            shader.setFloatUniform("uSaturationBoost", params.saturationBoost)

            val blurEffect = halfBlurEffectFor(blurPxHalf)
            val shaderEffect = RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
            RenderEffect.createChainEffect(shaderEffect, blurEffect).asComposeRenderEffect()
        }.getOrNull()
    }

    /**
     * Batched Keyboard Pass 2: ONE full-res (1.0×) sharp rim/lighting pass (ZERO Gaussian blur)
     * covering ALL floating keys + collapse chevron, returning transparent `half4(0)` in gaps.
     */
    fun createKeyboardSharpPassEffect(
        hasBlurPass: Boolean,
        backdropBounds: FloatArray,
        keyCount: Int,
        keyRects: FloatArray,
        keyTints: FloatArray,
        keyRadii: FloatArray,
        bevelWidthPx: Float,
        mirrorAxisPx: Float,
        highlightWidthPx: Float,
        params: LiquidGlassParams,
    ): ComposeRenderEffect? {
        val shader = kbdSharpPassShader ?: return null
        if (!isKeyboardBatchAvailable || keyCount <= 0) return null
        return runCatching {
            val (lx, ly) = params.lightDirectionXY()
            val (tr, tg, tb) = params.tintRgb()
            shader.setFloatUniform("uOrigin", 0f, 0f)
            shader.setFloatUniform(
                "uBackdropBounds",
                backdropBounds[0], backdropBounds[1], backdropBounds[2], backdropBounds[3],
            )
            shader.setFloatUniform("uKeyCount", keyCount.toFloat())
            shader.setFloatUniform("uKeyRects", keyRects)
            shader.setFloatUniform("uKeyTints", keyTints)
            shader.setFloatUniform("uKeyRadii", keyRadii)
            shader.setFloatUniform("uBevelWidth", bevelWidthPx)
            shader.setFloatUniform("uRefractionStrength", params.refractionStrength)
            shader.setFloatUniform("uIor", params.ior)
            shader.setFloatUniform("uDispersion", params.dispersion)
            shader.setFloatUniform("uMirrorStrength", params.mirrorStrength)
            shader.setFloatUniform("uMirrorAxis", mirrorAxisPx)
            shader.setFloatUniform("uHasBlurPass", if (hasBlurPass) 1f else 0f)
            shader.setFloatUniform("uRimCrispness", params.rimCrispness)
            shader.setFloatUniform("uLightDir", lx, ly)
            shader.setFloatUniform("uHighlightIntensity", params.highlightIntensity)
            shader.setFloatUniform("uHighlightWidth", highlightWidthPx)
            shader.setFloatUniform("uShadowIntensity", params.shadowIntensity)
            shader.setFloatUniform("uFresnelIntensity", params.fresnelIntensity)
            shader.setFloatUniform("uTintColor", tr, tg, tb)
            shader.setFloatUniform("uTintAlpha", params.tintAlpha)
            shader.setFloatUniform("uSaturationBoost", params.saturationBoost)

            RenderEffect.createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()
        }.getOrNull()
    }
}
