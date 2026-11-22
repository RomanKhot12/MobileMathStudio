package com.example.mobilemathstudio.ui.glass

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.mobilemathstudio.performance.GlassQuality
import com.example.mobilemathstudio.performance.LocalGlassQuality
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * True mathematical oval/ellipse Compose Shape matching the shader's `sdEllipseApprox` SDF.
 */
val TrueOvalShape: Shape = GenericShape { size, _ ->
    addOval(Rect(Offset.Zero, size))
}

/**
 * Returns the Compose clip [Shape] matching [shapeKind] and [cornerRadiusDp].
 */
fun glassClipShape(shapeKind: GlassShapeKind, cornerRadiusDp: Float): Shape = when (shapeKind) {
    GlassShapeKind.PILL -> RoundedCornerShape(percent = 50)
    GlassShapeKind.ROUNDED_CARD -> RoundedCornerShape(cornerRadiusDp.dp)
    GlassShapeKind.OVAL -> TrueOvalShape
    GlassShapeKind.CIRCLE -> TrueOvalShape
}

/**
 * Zero-allocation primitive cache for per-surface `RenderEffect` instances.
 * Rebuilds effects and updates `RuntimeShader` uniforms ONLY when dimensions, origin, density,
 * shape, or optical params actually change — NEVER on stationary or panning canvas frames!
 */
private class CachedSurfaceEffects {
    var initialized: Boolean = false
    var widthInt: Int = -1
    var heightInt: Int = -1
    var originXInt: Int = Int.MIN_VALUE
    var originYInt: Int = Int.MIN_VALUE
    var rootW: Int = -1
    var rootH: Int = -1
    var cornerPxBits: Int = 0
    var densityBits: Int = 0
    var isEllipse: Boolean = false
    var params: LiquidGlassParams? = null

    var blurPassEffect: ComposeRenderEffect? = null
    var sharpPassEffect: ComposeRenderEffect? = null
    var api31BlurPx: Float = -1f
    var api31BlurEffect: ComposeRenderEffect? = null
    val reusableBounds: FloatArray = FloatArray(4)

    fun matches(
        wInt: Int,
        hInt: Int,
        oxInt: Int,
        oyInt: Int,
        rW: Int,
        rH: Int,
        cornerBits: Int,
        densBits: Int,
        ellipse: Boolean,
        p: LiquidGlassParams,
    ): Boolean = initialized &&
        widthInt == wInt &&
        heightInt == hInt &&
        originXInt == oxInt &&
        originYInt == oyInt &&
        rootW == rW &&
        rootH == rH &&
        cornerPxBits == cornerBits &&
        densityBits == densBits &&
        isEllipse == ellipse &&
        params == p

    fun mark(
        wInt: Int,
        hInt: Int,
        oxInt: Int,
        oyInt: Int,
        rW: Int,
        rH: Int,
        cornerBits: Int,
        densBits: Int,
        ellipse: Boolean,
        p: LiquidGlassParams,
    ) {
        initialized = true
        widthInt = wInt
        heightInt = hInt
        originXInt = oxInt
        originYInt = oyInt
        rootW = rW
        rootH = rH
        cornerPxBits = cornerBits
        densityBits = densBits
        isEllipse = ellipse
        params = p
    }
}

/**
 * Physical Liquid Glass surface composable powered by the 2-pass AGSL optical engine.
 *
 * Strict quality & performance contract:
 * - Full AGSL (`RuntimeShader` refraction, dispersion, mirror axis, directional rim lighting)
 *   runs **ONLY** when [LocalGlassQuality] is [GlassQuality.Full] AND Android 13+ (API 33+)
 *   supports `RuntimeShader`.
 * - Blurs ONLY the compact `(w + 2*pad) × (h + 2*pad)` cropped surface region (not the entire
 *   screen!) to preserve 60 FPS on mid-range GPUs such as Mali-G57.
 * - Zero heap allocations and zero `RuntimeShader` uniform updates in `onDraw` while stationary
 *   or panning the canvas.
 * - In [GlassQuality.Blur] (Balanced), renders cropped native hardware blur + Canvas rim.
 * - In [GlassQuality.Scrim] (Liquid Glass disabled / Power Saver) or API < 31, renders pure
 *   fast Compose gradients with zero `RenderEffect` and zero offscreen `GraphicsLayer` recording.
 */
@Composable
fun LiquidGlassSurface(
    backdropState: LiquidGlassBackdropState,
    params: LiquidGlassParams,
    modifier: Modifier = Modifier,
    overrideShape: GlassShapeKind? = null,
    overrideCornerRadiusDp: Float? = null,
    drawOuterShadow: Boolean = true,
    originYOffsetPxProvider: () -> Float = { 0f },
    glassTintOverlay: (DrawScope.() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val quality = LocalGlassQuality.current
    val cleanParams = remember(params) { params.sanitized() }
    val activeShapeKind = overrideShape ?: cleanParams.shapeKind
    val activeCornerDp = overrideCornerRadiusDp ?: cleanParams.cornerRadiusDp
    val isEllipse = activeShapeKind == GlassShapeKind.OVAL || activeShapeKind == GlassShapeKind.CIRCLE
    val clipShape = remember(activeShapeKind, activeCornerDp) {
        glassClipShape(activeShapeKind, activeCornerDp)
    }

    // Lazily acquire shared API 33 renderer ONLY when Full quality is active on API 33+.
    val api33Renderer = remember(backdropState, quality) {
        if (quality == GlassQuality.Full && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backdropState.obtainApi33Renderer() as? LiquidGlassApi33Renderer
        } else {
            null
        }
    }
    val shaderAvailable = api33Renderer?.isAvailable == true
    val effectiveTier = remember(quality, shaderAvailable) {
        resolvePhysicalGlassTier(
            glassQuality = quality,
            sdkInt = Build.VERSION.SDK_INT,
            shaderAvailable = shaderAvailable,
        )
    }

    val blurPassLayer = rememberGraphicsLayer()
    val sharpPassLayer = rememberGraphicsLayer()
    val effectCache = remember { CachedSurfaceEffects() }

    var myCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var backdropOrigin by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                myCoordinates = coords
                val measured = backdropState.localOriginInBackdrop(coords)
                if (measured != null) {
                    val yOffset = Snapshot.withoutReadObservation { originYOffsetPxProvider() }
                    val settled = Offset(measured.x, measured.y - yOffset)
                    if (backdropOrigin == null ||
                        abs(backdropOrigin!!.x - settled.x) > 0.5f ||
                        abs(backdropOrigin!!.y - settled.y) > 0.5f
                    ) {
                        backdropOrigin = settled
                    }
                }
            }
            .then(
                if (drawOuterShadow) {
                    Modifier.drawBehind {
                        val cornerPx = resolveCornerRadiusPx(
                            shapeKind = activeShapeKind,
                            cornerRadiusDp = activeCornerDp,
                            width = size.width,
                            height = size.height,
                            density = density,
                        )
                        drawDirectionalGlassShadow(
                            params = cleanParams,
                            cornerRadiusPx = cornerPx,
                            isEllipse = isEllipse,
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clip(clipShape)
            .drawWithContent {
                if (effectiveTier != PhysicalGlassTier.SCRIM_FALLBACK) {
                    backdropState.observeForRedraw()
                }

                val w = size.width
                val h = size.height
                if (w < 2f || h < 2f) {
                    drawContent()
                    return@drawWithContent
                }

                val wInt = w.roundToInt().coerceAtLeast(1)
                val hInt = h.roundToInt().coerceAtLeast(1)
                val cornerPx = resolveCornerRadiusPx(
                    shapeKind = activeShapeKind,
                    cornerRadiusDp = activeCornerDp,
                    width = w,
                    height = h,
                    density = density,
                )
                val blurPx = cleanParams.blurRadiusDp * density
                val bevelWidthPx = cleanParams.bevelWidthDp * density
                val mirrorAxisPx = cleanParams.mirrorAxisDp * density
                val highlightWidthPx = cleanParams.highlightWidthDp * density

                val liveOrigin = backdropState.localOriginInBackdrop(myCoordinates)
                    ?.let {
                        val yOffset = Snapshot.withoutReadObservation { originYOffsetPxProvider() }
                        Offset(it.x, it.y - yOffset)
                    }
                    ?: backdropOrigin
                @Suppress("UNUSED_EXPRESSION")
                backdropOrigin
                val rootSize = backdropState.rootSizePx
                val canSampleBackdrop = liveOrigin != null && rootSize.width > 1 && rootSize.height > 1

                if (
                    effectiveTier == PhysicalGlassTier.FULL_AGSL &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    api33Renderer != null &&
                    canSampleBackdrop
                ) {
                    val ox = liveOrigin!!.x
                    val oy = liveOrigin.y
                    val oxInt = ox.roundToInt()
                    val oyInt = oy.roundToInt()
                    val rootW = rootSize.width.toFloat()
                    val rootH = rootSize.height.toFloat()
                    val hasBlurPass = blurPx > 0.5f

                    val pad = if (hasBlurPass) ceil(blurPx * 1.5f).toInt().coerceIn(4, 96) else 0
                    val padF = pad.toFloat()
                    val paddedW = wInt + pad * 2
                    val paddedH = hInt + pad * 2
                    val cornerBits = cornerPx.toBits()
                    val densityBits = density.toBits()

                    // Rebuild RenderEffects & write uniforms ONLY when geometry, origin, or params change.
                    if (!effectCache.matches(
                            wInt, hInt, oxInt, oyInt,
                            rootSize.width, rootSize.height,
                            cornerBits, densityBits, isEllipse, cleanParams,
                        )
                    ) {
                        val boundsBuf = effectCache.reusableBounds
                        effectCache.blurPassEffect = if (hasBlurPass) {
                            fillClampedBackdropBounds(
                                out = boundsBuf,
                                originX = ox,
                                originY = oy,
                                padPx = padF,
                                layerW = paddedW.toFloat(),
                                layerH = paddedH.toFloat(),
                                rootW = rootW,
                                rootH = rootH,
                            )
                            api33Renderer.createBlurredPassEffect(
                                blurPx = blurPx,
                                padPx = padF,
                                widthPx = w,
                                heightPx = h,
                                backdropBounds = boundsBuf,
                                cornerRadiusPx = cornerPx,
                                isEllipse = isEllipse,
                                bevelWidthPx = bevelWidthPx,
                                mirrorAxisPx = mirrorAxisPx,
                                params = cleanParams,
                                touchLocal = null,
                                touchActive = 0f,
                            )
                        } else {
                            null
                        }

                        fillClampedBackdropBounds(
                            out = boundsBuf,
                            originX = ox,
                            originY = oy,
                            padPx = 0f,
                            layerW = w,
                            layerH = h,
                            rootW = rootW,
                            rootH = rootH,
                        )
                        effectCache.sharpPassEffect = api33Renderer.createSharpAndLightingEffect(
                            hasBlurPass = hasBlurPass,
                            widthPx = w,
                            heightPx = h,
                            backdropBounds = boundsBuf,
                            cornerRadiusPx = cornerPx,
                            isEllipse = isEllipse,
                            bevelWidthPx = bevelWidthPx,
                            mirrorAxisPx = mirrorAxisPx,
                            highlightWidthPx = highlightWidthPx,
                            params = cleanParams,
                            touchLocal = null,
                            touchActive = 0f,
                        )
                        effectCache.mark(
                            wInt, hInt, oxInt, oyInt,
                            rootSize.width, rootSize.height,
                            cornerBits, densityBits, isEllipse, cleanParams,
                        )
                    }

                    // === PASS 1: Cropped hardware blur + refraction + mirror ===
                    val blurEffect = effectCache.blurPassEffect
                    if (hasBlurPass && blurEffect != null) {
                        blurPassLayer.record(size = IntSize(paddedW, paddedH)) {
                            drawRect(color = backdropState.opaqueGroundColor)
                            translate(left = -(ox - padF), top = -(oy - padF)) {
                                drawLayer(backdropState.backdropLayer)
                            }
                        }
                        if (blurPassLayer.renderEffect !== blurEffect) {
                            blurPassLayer.renderEffect = blurEffect
                        }
                        translate(left = -padF, top = -padF) {
                            drawLayer(blurPassLayer)
                        }
                    }

                    // Smoky tint film placed between Pass 1 and Pass 2 so specular rim & Fresnel stay luminous.
                    glassTintOverlay?.invoke(this)

                    // === PASS 2: Crisp optical rim, mirror fold, directional highlight, shadow & Fresnel ===
                    val sharpEffect = effectCache.sharpPassEffect
                    if (sharpEffect != null) {
                        sharpPassLayer.record(size = IntSize(wInt, hInt)) {
                            drawRect(color = backdropState.opaqueGroundColor)
                            translate(left = -ox, top = -oy) {
                                drawLayer(backdropState.backdropLayer)
                            }
                        }
                        if (sharpPassLayer.renderEffect !== sharpEffect) {
                            sharpPassLayer.renderEffect = sharpEffect
                        }
                        drawLayer(sharpPassLayer)
                    }
                } else if (
                    effectiveTier == PhysicalGlassTier.BLUR_FALLBACK &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    canSampleBackdrop
                ) {
                    // === TIER 2 FALLBACK (Balanced / API 31–32): Cropped GPU blur + Canvas rim ===
                    val ox = liveOrigin!!.x
                    val oy = liveOrigin.y
                    val pad = if (blurPx > 0.5f) ceil(blurPx * 1.4f).toInt().coerceIn(4, 72) else 0
                    val padF = pad.toFloat()
                    val paddedW = wInt + pad * 2
                    val paddedH = hInt + pad * 2

                    if (abs(effectCache.api31BlurPx - blurPx) > 0.25f) {
                        effectCache.api31BlurEffect = LiquidGlassApi31Fallback.createBlurOnlyEffect(blurPx)
                        effectCache.api31BlurPx = blurPx
                    }
                    blurPassLayer.record(size = IntSize(paddedW, paddedH)) {
                        drawRect(color = backdropState.opaqueGroundColor)
                        translate(left = -(ox - padF), top = -(oy - padF)) {
                            drawLayer(backdropState.backdropLayer)
                        }
                    }
                    if (blurPassLayer.renderEffect !== effectCache.api31BlurEffect) {
                        blurPassLayer.renderEffect = effectCache.api31BlurEffect
                    }
                    translate(left = -padF, top = -padF) {
                        drawLayer(blurPassLayer)
                    }
                    glassTintOverlay?.invoke(this)
                    drawGracefulFallbackOverlay(
                        params = cleanParams,
                        cornerRadiusPx = cornerPx,
                        isEllipse = isEllipse,
                        hasNativeBackdropBlur = true,
                        touchActive = 0f,
                    )
                } else {
                    // === TIER 1 FALLBACK (Liquid Glass OFF / Power Saver / API < 31): Zero RenderEffect ===
                    drawGracefulFallbackOverlay(
                        params = cleanParams,
                        cornerRadiusPx = cornerPx,
                        isEllipse = isEllipse,
                        hasNativeBackdropBlur = false,
                        touchActive = 0f,
                    )
                }

                // Foreground icons rendered crisply on top.
                drawContent()
            },
        contentAlignment = contentAlignment,
        content = content,
    )
}

internal fun resolveCornerRadiusPx(
    shapeKind: GlassShapeKind,
    cornerRadiusDp: Float,
    width: Float,
    height: Float,
    density: Float,
): Float {
    val maxR = min(width, height) * 0.5f
    return when (shapeKind) {
        GlassShapeKind.PILL -> maxR
        GlassShapeKind.CIRCLE -> maxR
        GlassShapeKind.OVAL -> maxR
        GlassShapeKind.ROUNDED_CARD -> (cornerRadiusDp * density).coerceIn(0f, maxR)
    }
}

private fun fillClampedBackdropBounds(
    out: FloatArray,
    originX: Float,
    originY: Float,
    padPx: Float,
    layerW: Float,
    layerH: Float,
    rootW: Float,
    rootH: Float,
) {
    val minX = max(0f, padPx - originX) + 0.5f
    val minY = max(0f, padPx - originY) + 0.5f
    val maxX = min(layerW, padPx - originX + rootW) - 0.5f
    val maxY = min(layerH, padPx - originY + rootH) - 0.5f
    out[0] = minX
    out[1] = minY
    out[2] = max(minX, maxX)
    out[3] = max(minY, maxY)
}

/**
 * Computes safe clamped sampling bounds `(minX, minY, maxX, maxY)` inside a glass `GraphicsLayer`
 * so shader samples never read out-of-screen flat padding near screen edges.
 */
internal fun computeClampedBackdropBounds(
    originX: Float,
    originY: Float,
    padPx: Float,
    layerW: Float,
    layerH: Float,
    rootW: Float,
    rootH: Float,
): FloatArray {
    val out = FloatArray(4)
    fillClampedBackdropBounds(out, originX, originY, padPx, layerW, layerH, rootW, rootH)
    return out
}
