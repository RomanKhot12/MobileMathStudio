package com.example.mobilemathstudio.ui.glass

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mobilemathstudio.performance.GlassQuality
import com.example.mobilemathstudio.performance.LocalGlassQuality
import com.example.mobilemathstudio.ui.theme.LocalLiquidGlassEnabled
import com.example.mobilemathstudio.ui.theme.WinLightCardBottom
import com.example.mobilemathstudio.ui.theme.WinLightCardTop
import com.example.mobilemathstudio.ui.theme.WinLightTextPrimary

/**
 * Translucent Liquid Glass backdrop for canvas formula, system and graph cards.
 *
 * - Light theme uses softly tinted cool-slate `#F4F7FB` -> `#ECF0F6` cards with low transparency
 *   so cards float gently above the `#E8ECF2` canvas instead of merging into glaring white.
 * - When Liquid Glass is disabled in Settings (`LocalLiquidGlassEnabled == false`) or in Power
 *   Saver (`GlassQuality.Scrim`), completely disables `RenderEffect.createBlurEffect` and renders
 *   fast cached Compose gradients only.
 */
@Composable
fun CardLiquidGlassBackdrop(
    dark: Boolean,
    accent: Color,
    tintColor: Color,
    tintAlpha: Float,
    borderColor: Color,
    borderWidth: Dp,
    cornerRadius: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val quality = LocalGlassQuality.current
    val glassEnabled = LocalLiquidGlassEnabled.current
    val useHardwareBlur = glassEnabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        quality != GlassQuality.Scrim
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val blurPx = with(LocalDensity.current) { 10.dp.toPx().coerceIn(4f, 24f) }
    val blurEffect = remember(useHardwareBlur, blurPx) {
        if (useHardwareBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP).asComposeRenderEffect()
        } else {
            null
        }
    }

    Box(modifier = modifier) {
        if (blurEffect != null) {
            // Layer 1: hardware-blurred frosted light caustics inside the glass slab.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        renderEffect = blurEffect
                        clip = true
                        this.shape = shape
                    }
                    .drawWithCache {
                        val w = size.width
                        val h = size.height
                        val frostBase = if (dark) {
                            Color(0xFF1E2028).copy(alpha = 0.44f)
                        } else {
                            WinLightCardTop.copy(alpha = 0.72f)
                        }
                        val topSpec = if (dark) {
                            Color.White.copy(alpha = 0.16f)
                        } else {
                            Color(0xFFF7FAFE).copy(alpha = 0.48f)
                        }
                        val refracted = if (tintAlpha > 0f) {
                            tintColor.copy(alpha = (tintAlpha * 0.85f).coerceIn(0.08f, 0.26f))
                        } else if (dark) {
                            accent.copy(alpha = 0.11f)
                        } else {
                            accent.copy(alpha = 0.06f)
                        }
                        val spotRadius = (maxOf(w, h) * 0.58f).coerceAtLeast(1f)
                        val topBrush = Brush.radialGradient(
                            colors = listOf(topSpec, Color.Transparent),
                            center = Offset(w * 0.18f, h * 0.14f),
                            radius = spotRadius,
                        )
                        val bottomBrush = Brush.radialGradient(
                            colors = listOf(refracted, Color.Transparent),
                            center = Offset(w * 0.86f, h * 0.90f),
                            radius = spotRadius,
                        )
                        onDrawBehind {
                            if (size.width <= 1f || size.height <= 1f) return@onDrawBehind
                            drawRect(frostBase)
                            drawRect(topBrush)
                            drawRect(bottomBrush)
                        }
                    },
            )
        }

        // Layer 2: softly tinted card body, optional glass sheen, inner bevel contour, outer rim.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val cornerPx = cornerRadius.toPx().coerceAtMost(minOf(w, h) / 2f).coerceAtLeast(0f)
                    val outerCorner = CornerRadius(cornerPx, cornerPx)

                    // Light cards use soft #F4F7FB with low transparency so they stand out cleanly
                    // from the #E8ECF2 canvas without turning into pure white patches.
                    val baseTopAlpha = if (blurEffect != null) {
                        if (dark) 0.54f else 0.84f
                    } else {
                        if (dark) 0.76f else 0.94f
                    }
                    val baseBottomAlpha = if (blurEffect != null) {
                        if (dark) 0.48f else 0.76f
                    } else {
                        if (dark) 0.70f else 0.90f
                    }
                    val baseBrush = Brush.verticalGradient(
                        colors = if (dark) {
                            listOf(
                                Color(0xFF23252E).copy(alpha = baseTopAlpha),
                                Color(0xFF14151B).copy(alpha = baseBottomAlpha),
                            )
                        } else {
                            listOf(
                                WinLightCardTop.copy(alpha = baseTopAlpha),
                                WinLightCardBottom.copy(alpha = baseBottomAlpha),
                            )
                        },
                        startY = 0f,
                        endY = h.coerceAtLeast(1f),
                    )

                    val sheenBrush = if (glassEnabled) {
                        Brush.linearGradient(
                            colors = if (dark) {
                                listOf(
                                    Color.White.copy(alpha = 0.11f),
                                    Color.White.copy(alpha = 0.03f),
                                    Color.Transparent,
                                )
                            } else {
                                listOf(
                                    Color(0xFFF9FBFE).copy(alpha = 0.28f),
                                    Color(0xFFF9FBFE).copy(alpha = 0.08f),
                                    Color.Transparent,
                                )
                            },
                            start = Offset.Zero,
                            end = Offset(w * 0.75f, h * 0.85f),
                        )
                    } else {
                        null
                    }

                    val tintBrush = if (tintAlpha > 0f) {
                        Brush.verticalGradient(
                            colors = listOf(
                                tintColor.copy(alpha = (tintAlpha * 1.15f).coerceIn(0f, 0.30f)),
                                tintColor.copy(alpha = (tintAlpha * 0.55f).coerceIn(0f, 0.20f)),
                            ),
                            startY = 0f,
                            endY = h.coerceAtLeast(1f),
                        )
                    } else {
                        null
                    }

                    val hasActiveBorder = borderWidth > 0.dp && borderColor.alpha > 0f
                    val outerStrokePx = (if (hasActiveBorder) borderWidth else 1.dp).toPx()
                    val outerHalf = outerStrokePx / 2f
                    val outerStroke = Stroke(width = outerStrokePx)
                    val outerBrush = if (hasActiveBorder) {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (dark) 0.50f else 0.52f),
                                borderColor,
                                borderColor.copy(alpha = (borderColor.alpha * 0.78f).coerceIn(0f, 1f)),
                            ),
                            startY = 0f,
                            endY = h.coerceAtLeast(1f),
                        )
                    } else if (dark) {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.26f),
                                Color.White.copy(alpha = 0.09f),
                                Color.White.copy(alpha = 0.04f),
                            ),
                            startY = 0f,
                            endY = h.coerceAtLeast(1f),
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                WinLightTextPrimary.copy(alpha = 0.13f),
                                WinLightTextPrimary.copy(alpha = 0.08f),
                                WinLightTextPrimary.copy(alpha = 0.06f),
                            ),
                            startY = 0f,
                            endY = h.coerceAtLeast(1f),
                        )
                    }

                    val innerStrokePx = 1.dp.toPx()
                    val innerInset = outerStrokePx + innerStrokePx * 0.65f
                    val innerStroke = Stroke(width = innerStrokePx)
                    val innerBrush = if (glassEnabled) {
                        Brush.linearGradient(
                            colors = if (dark) {
                                listOf(
                                    Color.White.copy(alpha = if (hasActiveBorder) 0.30f else 0.22f),
                                    (if (hasActiveBorder) borderColor else Color.White).copy(alpha = 0.08f),
                                    Color.White.copy(alpha = 0.02f),
                                )
                            } else {
                                listOf(
                                    Color(0xFFF8FBFF).copy(alpha = 0.72f),
                                    Color(0xFFF8FBFF).copy(alpha = 0.24f),
                                    WinLightTextPrimary.copy(alpha = 0.05f),
                                )
                            },
                            start = Offset.Zero,
                            end = Offset(w.coerceAtLeast(1f), h.coerceAtLeast(1f)),
                        )
                    } else {
                        null
                    }

                    onDrawBehind {
                        if (size.width <= 1f || size.height <= 1f) return@onDrawBehind
                        drawRoundRect(brush = baseBrush, cornerRadius = outerCorner)
                        if (sheenBrush != null) {
                            drawRoundRect(brush = sheenBrush, cornerRadius = outerCorner)
                        }
                        if (tintBrush != null) {
                            drawRoundRect(brush = tintBrush, cornerRadius = outerCorner)
                        }

                        if (innerBrush != null) {
                            val innerW = size.width - innerInset * 2f
                            val innerH = size.height - innerInset * 2f
                            if (innerW > 2f && innerH > 2f) {
                                val ic = (cornerPx - innerInset).coerceAtLeast(0f)
                                drawRoundRect(
                                    brush = innerBrush,
                                    topLeft = Offset(innerInset, innerInset),
                                    size = Size(innerW, innerH),
                                    cornerRadius = CornerRadius(ic, ic),
                                    style = innerStroke,
                                )
                            }
                        }

                        val outerW = size.width - outerStrokePx
                        val outerH = size.height - outerStrokePx
                        if (outerW > 1f && outerH > 1f) {
                            val oc = (cornerPx - outerHalf).coerceAtLeast(0f)
                            drawRoundRect(
                                brush = outerBrush,
                                topLeft = Offset(outerHalf, outerHalf),
                                size = Size(outerW, outerH),
                                cornerRadius = CornerRadius(oc, oc),
                                style = outerStroke,
                            )
                        }
                    }
                },
        )
    }
}
