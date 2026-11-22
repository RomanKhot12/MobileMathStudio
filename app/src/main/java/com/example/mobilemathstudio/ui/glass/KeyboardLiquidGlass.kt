package com.example.mobilemathstudio.ui.glass

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.mobilemathstudio.performance.GlassQuality
import com.example.mobilemathstudio.performance.LocalGlassQuality
import com.example.mobilemathstudio.ui.theme.WinLightTextPrimary
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Geometry + translucent functional group color filter for one floating keyboard button
 * (or the floating circular collapse chevron) rendered by the batched keyboard Liquid Glass pass.
 */
internal data class FloatingGlassKeyEntry(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerRadiusPx: Float,
    val glassTint: Color,
    val glassTintAlpha: Float,
    val fallbackFillTop: Color,
    val fallbackFillBottom: Color,
    val fallbackRimTop: Color,
    val fallbackRimBottom: Color,
    val contentAlpha: Float = 1f,
)

/**
 * Tracks live key rectangles in keyboard-host coordinates without triggering recomposition.
 * Only bumps [geometryVersion] when a key's bounds, radius, or tint actually change (>0.5px),
 * keeping typing and canvas pan/zoom at zero CPU/uniform overhead.
 */
@Stable
internal class KeyboardGlassRegistry {
    private val entries = LinkedHashMap<String, FloatingGlassKeyEntry>(48)

    var geometryVersion by mutableIntStateOf(0)
        private set

    fun update(
        id: String,
        hostCoords: LayoutCoordinates?,
        childCoords: LayoutCoordinates,
        cornerRadiusPx: Float,
        glassTint: Color,
        glassTintAlpha: Float,
        fallbackFillTop: Color,
        fallbackFillBottom: Color,
        fallbackRimTop: Color,
        fallbackRimBottom: Color,
        contentAlpha: Float = 1f,
    ) {
        val host = hostCoords ?: return
        if (!host.isAttached || !childCoords.isAttached) return
        val w = childCoords.size.width.toFloat()
        val h = childCoords.size.height.toFloat()
        if (w < 2f || h < 2f) return
        val tl = host.localPositionOf(childCoords, Offset.Zero)
        val entry = FloatingGlassKeyEntry(
            left = tl.x,
            top = tl.y,
            right = tl.x + w,
            bottom = tl.y + h,
            cornerRadiusPx = cornerRadiusPx.coerceIn(0f, min(w, h) * 0.5f),
            glassTint = glassTint,
            glassTintAlpha = glassTintAlpha,
            fallbackFillTop = fallbackFillTop,
            fallbackFillBottom = fallbackFillBottom,
            fallbackRimTop = fallbackRimTop,
            fallbackRimBottom = fallbackRimBottom,
            contentAlpha = contentAlpha,
        )
        val prev = entries[id]
        if (prev != null &&
            abs(prev.left - entry.left) <= 0.5f &&
            abs(prev.top - entry.top) <= 0.5f &&
            abs(prev.right - entry.right) <= 0.5f &&
            abs(prev.bottom - entry.bottom) <= 0.5f &&
            abs(prev.cornerRadiusPx - entry.cornerRadiusPx) <= 0.5f &&
            prev.glassTint == entry.glassTint &&
            abs(prev.glassTintAlpha - entry.glassTintAlpha) <= 0.005f &&
            prev.fallbackFillTop == entry.fallbackFillTop &&
            abs(prev.contentAlpha - entry.contentAlpha) <= 0.01f
        ) {
            return
        }
        entries[id] = entry
        geometryVersion++
    }

    fun remove(id: String) {
        if (entries.remove(id) != null) {
            geometryVersion++
        }
    }

    fun snapshot(): List<FloatingGlassKeyEntry> = entries.values.toList()
}

/**
 * Zero-allocation primitive cache for the batched keyboard Liquid Glass renderer.
 * Rebuilds the clip path and `RenderEffect` pair ONLY when keyboard geometry, origin,
 * layer, or optical params change — NEVER during stationary frames, canvas pan/zoom, or typing.
 */
private class CachedKeyboardGlassBatch {
    var initialized: Boolean = false
    var widthInt: Int = -1
    var heightInt: Int = -1
    var originXInt: Int = Int.MIN_VALUE
    var originYInt: Int = Int.MIN_VALUE
    var rootW: Int = -1
    var rootH: Int = -1
    var densityBits: Int = 0
    var geometryVersion: Int = -1
    var params: LiquidGlassParams? = null

    var blurPassEffect: ComposeRenderEffect? = null
    var sharpPassEffect: ComposeRenderEffect? = null
    var api31HalfBlurPx: Float = -1f
    var api31HalfBlurEffect: ComposeRenderEffect? = null

    var cachedPathVersion: Int = -1
    val keysClipPath: Path = Path()
    var cachedEntries: List<FloatingGlassKeyEntry> = emptyList()

    val keyRects: FloatArray = FloatArray(LiquidGlassShaders.MAX_KEYBOARD_KEYS * 4)
    val keyRectsHalf: FloatArray = FloatArray(LiquidGlassShaders.MAX_KEYBOARD_KEYS * 4)
    val keyTints: FloatArray = FloatArray(LiquidGlassShaders.MAX_KEYBOARD_KEYS * 4)
    val keyRadii: FloatArray = FloatArray(LiquidGlassShaders.MAX_KEYBOARD_KEYS)
    val keyRadiiHalf: FloatArray = FloatArray(LiquidGlassShaders.MAX_KEYBOARD_KEYS)
    val reusableBounds: FloatArray = FloatArray(4)

    fun ensurePath(registry: KeyboardGlassRegistry) {
        val v = registry.geometryVersion
        if (cachedPathVersion == v) return
        cachedPathVersion = v
        val list = registry.snapshot()
        cachedEntries = list
        keysClipPath.reset()
        for (i in list.indices) {
            val k = list[i]
            keysClipPath.addRoundRect(
                RoundRect(
                    left = k.left,
                    top = k.top,
                    right = k.right,
                    bottom = k.bottom,
                    cornerRadius = CornerRadius(k.cornerRadiusPx, k.cornerRadiusPx),
                ),
            )
        }
    }

    fun matchesEffects(
        wInt: Int,
        hInt: Int,
        oxInt: Int,
        oyInt: Int,
        rW: Int,
        rH: Int,
        densBits: Int,
        geomVer: Int,
        p: LiquidGlassParams,
    ): Boolean = initialized &&
        widthInt == wInt &&
        heightInt == hInt &&
        originXInt == oxInt &&
        originYInt == oyInt &&
        rootW == rW &&
        rootH == rH &&
        densityBits == densBits &&
        geometryVersion == geomVer &&
        params == p

    fun markEffects(
        wInt: Int,
        hInt: Int,
        oxInt: Int,
        oyInt: Int,
        rW: Int,
        rH: Int,
        densBits: Int,
        geomVer: Int,
        p: LiquidGlassParams,
    ) {
        initialized = true
        widthInt = wInt
        heightInt = hInt
        originXInt = oxInt
        originYInt = oyInt
        rootW = rW
        rootH = rH
        densityBits = densBits
        geometryVersion = geomVer
        params = p
    }
}

private const val KEYBOARD_BLUR_DOWNSAMPLE = 0.5f

/**
 * Renders ALL floating keyboard keys + collapse chevron as genuine physical Liquid Glass
 * directly over the transparent canvas using **ONLY 2 `GraphicsLayer`s TOTAL** (1 downsampled
 * `0.5×` Gaussian blur + batched AGSL refraction/dispersion/mirror pass, and 1 full-res sharp
 * rim/highlight/Fresnel/shadow pass) instead of 38×2 = 76 separate offscreen render passes!
 *
 * Performance & fallback tiers:
 * - `FULL_AGSL` (`liquidGlassEnabled == true`, `GlassQuality.Full`, API 33+): Full 2-pass batched
 *   AGSL Liquid Glass (`0.94 dp` rim, `51%` edge highlight, `15.9 dp` mirror axis, `59%` RGB
 *   dispersion, `8.4 dp` downsampled blur, 5 functional group tints) with 100% transparent gaps.
 * - `BLUR_FALLBACK` (`liquidGlassEnabled == true`, `GlassQuality.Blur` or API 31–32): Single
 *   `0.5×` downsampled blurred backdrop clipped to the floating keycap path + crisp 2D glass rim.
 * - `SCRIM_FALLBACK` (`liquidGlassEnabled == false` or `GlassQuality.Scrim` or API < 31): Zero
 *   `RenderEffect` / zero backdrop recording — clean floating matte/translucent keycaps over
 *   100% transparent canvas gaps.
 */
@Composable
internal fun Modifier.floatingKeyboardLiquidGlass(
    backdropState: LiquidGlassBackdropState?,
    registry: KeyboardGlassRegistry,
    params: LiquidGlassParams,
    glassEnabled: Boolean,
    hostCoordsProvider: () -> LayoutCoordinates?,
    originYOffsetPxProvider: () -> Float,
): Modifier {
    val quality = LocalGlassQuality.current
    val cleanParams = remember(params) { params.sanitized() }

    val api33Renderer = remember(backdropState, quality, glassEnabled) {
        if (
            glassEnabled &&
            backdropState != null &&
            quality == GlassQuality.Full &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            backdropState.obtainApi33Renderer() as? LiquidGlassApi33Renderer
        } else {
            null
        }
    }
    val batchShaderAvailable = api33Renderer?.isKeyboardBatchAvailable == true
    val effectiveTier = remember(quality, batchShaderAvailable, glassEnabled, backdropState) {
        if (!glassEnabled || backdropState == null) {
            PhysicalGlassTier.SCRIM_FALLBACK
        } else {
            resolvePhysicalGlassTier(
                glassQuality = quality,
                sdkInt = Build.VERSION.SDK_INT,
                shaderAvailable = batchShaderAvailable,
            )
        }
    }

    val kbdBlurLayer = rememberGraphicsLayer()
    val kbdSharpLayer = rememberGraphicsLayer()
    val batchCache = remember { CachedKeyboardGlassBatch() }

    return this.drawBehind {
        val geomVersion = registry.geometryVersion
        batchCache.ensurePath(registry)
        val keys = batchCache.cachedEntries
        if (keys.isEmpty()) return@drawBehind

        if (effectiveTier != PhysicalGlassTier.SCRIM_FALLBACK && backdropState != null) {
            backdropState.observeForRedraw()
        }

        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return@drawBehind

        // 1. Subtle directional floating shadow under every keycap so keys visually hover over the canvas.
        drawFloatingKeyShadows(keys, cleanParams)

        val wInt = w.roundToInt().coerceAtLeast(1)
        val hInt = h.roundToInt().coerceAtLeast(1)
        val blurPx = cleanParams.blurRadiusDp * density
        val bevelWidthPx = cleanParams.bevelWidthDp * density
        val mirrorAxisPx = cleanParams.mirrorAxisDp * density
        val highlightWidthPx = cleanParams.highlightWidthDp * density

        val liveOrigin = backdropState?.localOriginInBackdrop(hostCoordsProvider())?.let {
            val yOffset = Snapshot.withoutReadObservation { originYOffsetPxProvider() }
            Offset(it.x, it.y - yOffset)
        }
        val rootSize = backdropState?.rootSizePx ?: IntSize.Zero
        val canSampleBackdrop = backdropState != null &&
            liveOrigin != null &&
            rootSize.width > 1 &&
            rootSize.height > 1

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

            val blurPxHalf = blurPx * KEYBOARD_BLUR_DOWNSAMPLE
            val padHalf = if (hasBlurPass) ceil(blurPxHalf * 1.5f).toInt().coerceIn(3, 48) else 0
            val padHalfF = padHalf.toFloat()
            val wHalf = ceil(w * KEYBOARD_BLUR_DOWNSAMPLE).toInt().coerceAtLeast(1)
            val hHalf = ceil(h * KEYBOARD_BLUR_DOWNSAMPLE).toInt().coerceAtLeast(1)
            val paddedWHalf = wHalf + padHalf * 2
            val paddedHHalf = hHalf + padHalf * 2
            val densityBits = density.toBits()

            // Rebuild uniform arrays & RenderEffects ONLY when geometry, origin, or params change.
            if (!batchCache.matchesEffects(
                    wInt, hInt, oxInt, oyInt,
                    rootSize.width, rootSize.height,
                    densityBits, geomVersion, cleanParams,
                )
            ) {
                val count = min(keys.size, LiquidGlassShaders.MAX_KEYBOARD_KEYS)
                val rects = batchCache.keyRects
                val rectsHalf = batchCache.keyRectsHalf
                val tints = batchCache.keyTints
                val radii = batchCache.keyRadii
                val radiiHalf = batchCache.keyRadiiHalf
                for (i in 0 until count) {
                    val k = keys[i]
                    val base = i * 4
                    rects[base] = k.left
                    rects[base + 1] = k.top
                    rects[base + 2] = k.right
                    rects[base + 3] = k.bottom

                    rectsHalf[base] = k.left * KEYBOARD_BLUR_DOWNSAMPLE
                    rectsHalf[base + 1] = k.top * KEYBOARD_BLUR_DOWNSAMPLE
                    rectsHalf[base + 2] = k.right * KEYBOARD_BLUR_DOWNSAMPLE
                    rectsHalf[base + 3] = k.bottom * KEYBOARD_BLUR_DOWNSAMPLE

                    tints[base] = k.glassTint.red
                    tints[base + 1] = k.glassTint.green
                    tints[base + 2] = k.glassTint.blue
                    tints[base + 3] = k.glassTintAlpha

                    radii[i] = k.cornerRadiusPx
                    radiiHalf[i] = k.cornerRadiusPx * KEYBOARD_BLUR_DOWNSAMPLE
                }

                val boundsBuf = batchCache.reusableBounds
                batchCache.blurPassEffect = if (hasBlurPass) {
                    fillClampedBackdropBounds(
                        out = boundsBuf,
                        originX = ox * KEYBOARD_BLUR_DOWNSAMPLE,
                        originY = oy * KEYBOARD_BLUR_DOWNSAMPLE,
                        padPx = padHalfF,
                        layerW = paddedWHalf.toFloat(),
                        layerH = paddedHHalf.toFloat(),
                        rootW = rootW * KEYBOARD_BLUR_DOWNSAMPLE,
                        rootH = rootH * KEYBOARD_BLUR_DOWNSAMPLE,
                    )
                    api33Renderer.createKeyboardBlurredPassEffect(
                        blurPxHalf = blurPxHalf,
                        padPxHalf = padHalfF,
                        backdropBounds = boundsBuf,
                        keyCount = count,
                        keyRectsHalf = rectsHalf,
                        keyTints = tints,
                        keyRadiiHalf = radiiHalf,
                        bevelWidthPxHalf = bevelWidthPx * KEYBOARD_BLUR_DOWNSAMPLE,
                        mirrorAxisPxHalf = mirrorAxisPx * KEYBOARD_BLUR_DOWNSAMPLE,
                        params = cleanParams,
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
                batchCache.sharpPassEffect = api33Renderer.createKeyboardSharpPassEffect(
                    hasBlurPass = hasBlurPass,
                    backdropBounds = boundsBuf,
                    keyCount = count,
                    keyRects = rects,
                    keyTints = tints,
                    keyRadii = radii,
                    bevelWidthPx = bevelWidthPx,
                    mirrorAxisPx = mirrorAxisPx,
                    highlightWidthPx = highlightWidthPx,
                    params = cleanParams,
                )

                batchCache.markEffects(
                    wInt, hInt, oxInt, oyInt,
                    rootSize.width, rootSize.height,
                    densityBits, geomVersion, cleanParams,
                )
            }

            // === PASS 1: Single 0.5× downsampled Gaussian blur + batched Snell refraction/dispersion/mirror ===
            val blurEffect = batchCache.blurPassEffect
            if (hasBlurPass && blurEffect != null) {
                kbdBlurLayer.record(size = IntSize(paddedWHalf, paddedHHalf)) {
                    drawRect(color = backdropState!!.opaqueGroundColor)
                    translate(left = padHalfF, top = padHalfF) {
                        scale(scaleX = KEYBOARD_BLUR_DOWNSAMPLE, scaleY = KEYBOARD_BLUR_DOWNSAMPLE, pivot = Offset.Zero) {
                            translate(left = -ox, top = -oy) {
                                drawLayer(backdropState.backdropLayer)
                            }
                        }
                    }
                }
                if (kbdBlurLayer.renderEffect !== blurEffect) {
                    kbdBlurLayer.renderEffect = blurEffect
                }
                val invScale = 1f / KEYBOARD_BLUR_DOWNSAMPLE
                clipPath(batchCache.keysClipPath) {
                    scale(scaleX = invScale, scaleY = invScale, pivot = Offset.Zero) {
                        translate(left = -padHalfF, top = -padHalfF) {
                            drawLayer(kbdBlurLayer)
                        }
                    }
                }
            }

            // === PASS 2: Single 1.0× sharp rim, 0.94dp / 51% edge highlight, 67% Fresnel & 57% shadow ===
            val sharpEffect = batchCache.sharpPassEffect
            if (sharpEffect != null) {
                kbdSharpLayer.record(size = IntSize(wInt, hInt)) {
                    drawRect(color = backdropState!!.opaqueGroundColor)
                    translate(left = -ox, top = -oy) {
                        drawLayer(backdropState.backdropLayer)
                    }
                }
                if (kbdSharpLayer.renderEffect !== sharpEffect) {
                    kbdSharpLayer.renderEffect = sharpEffect
                }
                drawLayer(kbdSharpLayer)
            }
        } else if (
            effectiveTier == PhysicalGlassTier.BLUR_FALLBACK &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            canSampleBackdrop
        ) {
            // === TIER 2 (Balanced / API 31–32): Single 0.5× downsampled hardware blur clipped to keys ===
            val ox = liveOrigin!!.x
            val oy = liveOrigin.y
            val blurPxHalf = blurPx * KEYBOARD_BLUR_DOWNSAMPLE
            val padHalf = if (blurPxHalf > 0.5f) ceil(blurPxHalf * 1.4f).toInt().coerceIn(3, 40) else 0
            val padHalfF = padHalf.toFloat()
            val wHalf = ceil(w * KEYBOARD_BLUR_DOWNSAMPLE).toInt().coerceAtLeast(1)
            val hHalf = ceil(h * KEYBOARD_BLUR_DOWNSAMPLE).toInt().coerceAtLeast(1)
            val paddedWHalf = wHalf + padHalf * 2
            val paddedHHalf = hHalf + padHalf * 2

            if (abs(batchCache.api31HalfBlurPx - blurPxHalf) > 0.25f) {
                batchCache.api31HalfBlurEffect = LiquidGlassApi31Fallback.createBlurOnlyEffect(blurPxHalf)
                batchCache.api31HalfBlurPx = blurPxHalf
            }
            kbdBlurLayer.record(size = IntSize(paddedWHalf, paddedHHalf)) {
                drawRect(color = backdropState!!.opaqueGroundColor)
                translate(left = padHalfF, top = padHalfF) {
                    scale(scaleX = KEYBOARD_BLUR_DOWNSAMPLE, scaleY = KEYBOARD_BLUR_DOWNSAMPLE, pivot = Offset.Zero) {
                        translate(left = -ox, top = -oy) {
                            drawLayer(backdropState.backdropLayer)
                        }
                    }
                }
            }
            if (kbdBlurLayer.renderEffect !== batchCache.api31HalfBlurEffect) {
                kbdBlurLayer.renderEffect = batchCache.api31HalfBlurEffect
            }
            val invScale = 1f / KEYBOARD_BLUR_DOWNSAMPLE
            clipPath(batchCache.keysClipPath) {
                scale(scaleX = invScale, scaleY = invScale, pivot = Offset.Zero) {
                    translate(left = -padHalfF, top = -padHalfF) {
                        drawLayer(kbdBlurLayer)
                    }
                }
            }
            drawFloatingKeysGlassFallback(keys, cleanParams, hasBackdropBlur = true)
        } else {
            // === TIER 1 (Liquid Glass OFF / Power Saver / API < 31): Zero RenderEffect, still floating ===
            drawFloatingKeysGlassFallback(keys, cleanParams, hasBackdropBlur = false)
        }
    }
}

private fun DrawScope.drawFloatingKeyShadows(
    keys: List<FloatingGlassKeyEntry>,
    params: LiquidGlassParams,
) {
    val strength = params.shadowIntensity.coerceIn(0f, 1f)
    if (strength <= 0.01f) return
    val (lx, ly) = params.lightDirectionXY()
    val ox = -lx * 2.2f * density
    val oy = -ly * 2.4f * density + 1.2f * density
    val shadowColor = Color.Black.copy(
        alpha = (if (params.darkGlass) 0.24f else 0.11f) * strength,
    )
    val spread = 1.2f * density
    for (i in keys.indices) {
        val k = keys[i]
        val w = (k.right - k.left).coerceAtLeast(1f)
        val h = (k.bottom - k.top).coerceAtLeast(1f)
        val r = (k.cornerRadiusPx + spread * 0.5f).coerceAtMost(min(w, h) * 0.5f)
        drawRoundRect(
            color = shadowColor,
            topLeft = Offset(k.left + ox - spread * 0.5f, k.top + oy - spread * 0.5f),
            size = Size(w + spread, h + spread),
            cornerRadius = CornerRadius(r, r),
        )
    }
}

private fun DrawScope.drawFloatingKeysGlassFallback(
    keys: List<FloatingGlassKeyEntry>,
    params: LiquidGlassParams,
    hasBackdropBlur: Boolean,
) {
    val (lx, ly) = params.lightDirectionXY()
    val dark = params.darkGlass
    val facetStrokePx = params.highlightWidthDp.dp.toPx().coerceAtLeast(1f)
    val facetHalf = facetStrokePx * 0.5f
    val facetStroke = Stroke(width = facetStrokePx)
    val hiAlpha = (params.highlightIntensity * (if (dark) 0.86f else 0.96f)).coerceIn(0f, 0.92f)
    val fresnelAlpha = (params.fresnelIntensity * (if (dark) 0.22f else 0.16f)).coerceIn(0f, 0.36f)
    val shadowAlpha = (params.shadowIntensity * (if (dark) 0.58f else 0.32f)).coerceIn(0f, 0.58f)

    for (i in keys.indices) {
        val k = keys[i]
        val w = (k.right - k.left).coerceAtLeast(1f)
        val h = (k.bottom - k.top).coerceAtLeast(1f)
        val tl = Offset(k.left, k.top)
        val sz = Size(w, h)
        val cr = CornerRadius(k.cornerRadiusPx, k.cornerRadiusPx)

        if (hasBackdropBlur) {
            drawRoundRect(
                color = k.glassTint.copy(alpha = k.glassTintAlpha.coerceIn(0.08f, 0.86f)),
                topLeft = tl,
                size = sz,
                cornerRadius = cr,
            )
            val cx = k.left + w * 0.5f
            val cy = k.top + h * 0.5f
            val diag = hypot(w.toDouble(), h.toDouble()).toFloat().coerceAtLeast(1f)
            val lightStart = Offset(cx + lx * diag * 0.52f, cy + ly * diag * 0.52f)
            val shadowEnd = Offset(cx - lx * diag * 0.52f, cy - ly * diag * 0.52f)
            val facetBrush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.00f to Color.White.copy(alpha = hiAlpha),
                    0.28f to Color.White.copy(alpha = hiAlpha * 0.44f),
                    0.55f to (if (dark) Color.White else WinLightTextPrimary).copy(alpha = fresnelAlpha),
                    0.82f to WinLightTextPrimary.copy(alpha = shadowAlpha * 0.55f),
                    1.00f to WinLightTextPrimary.copy(alpha = shadowAlpha),
                ),
                start = lightStart,
                end = shadowEnd,
            )
            drawRoundRect(
                brush = facetBrush,
                topLeft = Offset(k.left + facetHalf, k.top + facetHalf),
                size = Size((w - facetStrokePx).coerceAtLeast(1f), (h - facetStrokePx).coerceAtLeast(1f)),
                cornerRadius = CornerRadius((k.cornerRadiusPx - facetHalf).coerceAtLeast(0f)),
                style = facetStroke,
            )
        } else {
            // Flat / Scrim mode: clean floating matte keycaps over transparent canvas gaps.
            val bodyBrush = Brush.verticalGradient(
                colors = listOf(k.fallbackFillTop, k.fallbackFillBottom),
                startY = k.top,
                endY = k.bottom,
            )
            drawRoundRect(
                brush = bodyBrush,
                topLeft = tl,
                size = sz,
                cornerRadius = cr,
            )
            val rimBrush = Brush.verticalGradient(
                colors = listOf(k.fallbackRimTop, k.fallbackRimBottom),
                startY = k.top,
                endY = k.bottom,
            )
            val strokePx = 1.dp.toPx()
            val inset = strokePx * 0.5f
            drawRoundRect(
                brush = rimBrush,
                topLeft = Offset(k.left + inset, k.top + inset),
                size = Size((w - strokePx).coerceAtLeast(1f), (h - strokePx).coerceAtLeast(1f)),
                cornerRadius = CornerRadius((k.cornerRadiusPx - inset).coerceAtLeast(0f)),
                style = Stroke(width = strokePx),
            )
        }
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
