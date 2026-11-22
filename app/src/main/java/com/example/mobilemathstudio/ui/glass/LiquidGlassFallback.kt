package com.example.mobilemathstudio.ui.glass

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.hypot
import kotlin.math.min

/**
 * Draws a tight, clean, floating directional shadow beneath the flat glass pane.
 * Cast opposite the light direction (138° upper-left light -> bottom-right shadow).
 */
internal fun DrawScope.drawDirectionalGlassShadow(
    params: LiquidGlassParams,
    cornerRadiusPx: Float,
    isEllipse: Boolean,
) {
    val strength = params.shadowIntensity.coerceIn(0f, 1f)
    if (strength <= 0.01f) return

    val (lx, ly) = params.lightDirectionXY()
    val shadowDirX = -lx
    val shadowDirY = -ly
    val baseOffset = 5.0f * density * (0.4f + 0.6f * strength)
    // Light theme needs a slightly gentler shadow so surfaces stay airy and notebook-clean.
    val alphaScale = if (params.darkGlass) 0.068f else 0.044f

    val layers = 4
    for (i in layers downTo 1) {
        val t = i.toFloat() / layers.toFloat()
        val spread = (t * 9.0f * density) * (0.45f + 0.55f * strength)
        val ox = shadowDirX * baseOffset * (0.35f + 0.65f * t)
        val oy = shadowDirY * baseOffset * (0.35f + 0.65f * t) + 1.4f * density * t
        val alpha = (alphaScale * strength * (1f - t * 0.72f)).coerceIn(0f, 0.20f)
        val color = Color.Black.copy(alpha = alpha)

        val topLeft = Offset(ox - spread * 0.30f, oy - spread * 0.30f)
        val shadowSize = Size(
            width = (size.width + spread * 0.60f).coerceAtLeast(1f),
            height = (size.height + spread * 0.60f).coerceAtLeast(1f),
        )

        if (isEllipse) {
            drawOval(color = color, topLeft = topLeft, size = shadowSize)
        } else {
            val r = (cornerRadiusPx + spread * 0.30f)
                .coerceAtMost(min(shadowSize.width, shadowSize.height) * 0.5f)
            drawRoundRect(
                color = color,
                topLeft = topLeft,
                size = shadowSize,
                cornerRadius = CornerRadius(r, r),
            )
        }
    }
}

/**
 * Graceful fallback glass overlay used in Balanced (`GlassQuality.Blur`), Power Saver
 * (`GlassQuality.Scrim`), or on devices without AGSL `RuntimeShader` support.
 */
internal fun DrawScope.drawGracefulFallbackOverlay(
    params: LiquidGlassParams,
    cornerRadiusPx: Float,
    isEllipse: Boolean,
    hasNativeBackdropBlur: Boolean,
    touchActive: Float,
) {
    val (lx, ly) = params.lightDirectionXY()
    val w = size.width
    val h = size.height
    val cx = w * 0.5f
    val cy = h * 0.5f
    val diag = hypot(w.toDouble(), h.toDouble()).toFloat().coerceAtLeast(1f)

    val lightStart = Offset(cx + lx * diag * 0.55f, cy + ly * diag * 0.55f)
    val shadowEnd = Offset(cx - lx * diag * 0.55f, cy - ly * diag * 0.55f)

    // 1. Calm flat frosted surface (tinted for legibility without blinding glare in light theme).
    val baseTint = if (params.darkGlass) Color(0xFF1A1D24) else Color(0xFFEAEFF5)
    val fillAlpha = when {
        hasNativeBackdropBlur && params.darkGlass -> (params.tintAlpha + 0.38f + touchActive * 0.08f).coerceIn(0.28f, 0.72f)
        hasNativeBackdropBlur -> (params.tintAlpha + 0.56f + touchActive * 0.06f).coerceIn(0.45f, 0.78f)
        params.darkGlass -> (params.tintAlpha + 0.78f + touchActive * 0.08f).coerceIn(0.68f, 0.90f)
        else -> (params.tintAlpha + 0.84f + touchActive * 0.06f).coerceIn(0.76f, 0.94f)
    }
    val flatColor = baseTint.copy(alpha = fillAlpha)

    if (isEllipse) {
        drawOval(color = flatColor)
    } else {
        drawRoundRect(
            color = flatColor,
            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
        )
    }

    // 2. Crisp directional highlight cut & opposite-side edge shadow
    val borderWidth = (params.highlightWidthDp * density * 0.55f).coerceIn(1.0f * density, 2.2f * density)
    val lightHighlightPeak = if (params.darkGlass) 0.72f else 0.88f
    val darkShadowPeak = if (params.darkGlass) 0.56f else 0.24f
    val rimBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0.00f to Color.White.copy(alpha = (lightHighlightPeak * params.highlightIntensity + 0.10f * params.fresnelIntensity).coerceIn(0f, 0.95f)),
            0.28f to Color.White.copy(alpha = (0.34f * params.highlightIntensity + 0.14f * params.fresnelIntensity).coerceIn(0f, 0.65f)),
            0.55f to (if (params.darkGlass) Color.White else Color(0xFF181A1F)).copy(alpha = 0.08f),
            0.80f to Color(0xFF181A1F).copy(alpha = (0.20f * params.shadowIntensity).coerceIn(0f, 0.38f)),
            1.00f to Color(0xFF181A1F).copy(alpha = (darkShadowPeak * params.shadowIntensity).coerceIn(0f, 0.55f)),
        ),
        start = lightStart,
        end = shadowEnd,
    )

    val halfStroke = borderWidth * 0.5f
    val strokeSize = Size((w - borderWidth).coerceAtLeast(1f), (h - borderWidth).coerceAtLeast(1f))
    if (isEllipse) {
        drawOval(
            brush = rimBrush,
            topLeft = Offset(halfStroke, halfStroke),
            size = strokeSize,
            style = Stroke(width = borderWidth),
        )
    } else {
        val sr = (cornerRadiusPx - halfStroke).coerceAtLeast(0f)
        drawRoundRect(
            brush = rimBrush,
            topLeft = Offset(halfStroke, halfStroke),
            size = strokeSize,
            cornerRadius = CornerRadius(sr, sr),
            style = Stroke(width = borderWidth),
        )
    }

    // 3. Optional mirror axis debug guide (disabled in production presets)
    if (params.showMirrorAxisOverlay && params.mirrorStrength > 0.01f) {
        val axisPx = (params.mirrorAxisDp * density).coerceIn(2f, min(w, h) * 0.42f)
        val axisSize = Size((w - axisPx * 2f).coerceAtLeast(1f), (h - axisPx * 2f).coerceAtLeast(1f))
        val axisColor = Color(0xE6FF2E47)
        if (isEllipse) {
            drawOval(
                color = axisColor,
                topLeft = Offset(axisPx, axisPx),
                size = axisSize,
                style = Stroke(width = 1.4f * density),
            )
        } else {
            val ar = (cornerRadiusPx - axisPx).coerceAtLeast(0f)
            drawRoundRect(
                color = axisColor,
                topLeft = Offset(axisPx, axisPx),
                size = axisSize,
                cornerRadius = CornerRadius(ar, ar),
                style = Stroke(width = 1.4f * density),
            )
        }
    }
}
