package com.example.mobilemathstudio.ui.graph

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.mobilemathstudio.CrashGuard
import com.example.mobilemathstudio.math.graph.FunctionGraph
import com.example.mobilemathstudio.math.graph.GraphMarkerStyle
import com.example.mobilemathstudio.math.graph.GraphPoint
import com.example.mobilemathstudio.math.graph.GraphSeries
import com.example.mobilemathstudio.math.graph.GraphViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

val GraphSeriesPalette = listOf(
    Color(0xFF29B6F6),
    Color(0xFFFFB74D),
    Color(0xFF81C784),
    Color(0xFFE57373),
    Color(0xFFBA68C8),
    Color(0xFF4DB6AC),
)

val GraphLinkYellow = Color(0xFFFFC107)

/** Base samples across the sampled window (visible span + one span each side). */
private const val SAMPLE_STEPS = 900

/**
 * Curves sampled for [window]. Pan / zoom frames just re-project these points;
 * sampling runs again (off the main thread) only when the view leaves the window
 * or the zoom level drifts far enough to need a different resolution.
 */
private class SampledGraph(
    val expressions: List<String>,
    val window: GraphViewport,
    val visibleXSpan: Double,
    val visibleYSpan: Double,
    val series: List<GraphSeries>,
) {
    fun covers(expr: List<String>, vp: GraphViewport): Boolean {
        if (expr != expressions) return false
        if (vp.xMin < window.xMin || vp.xMax > window.xMax) return false
        if (vp.yMin < window.yMin || vp.yMax > window.yMax) return false
        val zx = vp.xSpan / visibleXSpan
        val zy = vp.ySpan / visibleYSpan
        return zx in 0.55..1.8 && zy in 0.55..1.8
    }
}

private fun GraphViewport.overscanned(): GraphViewport =
    GraphViewport(xMin - xSpan, xMax + xSpan, yMin - ySpan, yMax + ySpan)

/**
 * Live 2D plot: grid + numbered axes + traces sampled for the current window.
 */
@Composable
fun GraphPlotCanvas(
    expressions: List<String>,
    colors: List<Color>,
    viewport: GraphViewport,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val labelPx = with(density) { 10.dp.toPx() }
    val tickPx = with(density) { 4.dp.toPx() }
    val gridTargetPx = with(density) { 56.dp.toPx() }
    val strokePx = with(density) { 1.7.dp.toPx() }
    val markerPx = with(density) { 3.4.dp.toPx() }
    val labelPaint = remember(dark, labelPx) {
        Paint().apply {
            isAntiAlias = true
            textSize = labelPx
            typeface = Typeface.SANS_SERIF
            color = (if (dark) Color(0xFFCFCFCF) else Color(0xFF333333)).toArgb()
        }
    }
    val curveStroke = remember(strokePx) {
        Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val dashedCurveStroke = remember(strokePx) {
        Stroke(
            width = strokePx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(strokePx * 4.5f, strokePx * 3.2f)),
        )
    }
    val markerStroke = remember(strokePx) { Stroke(width = strokePx * 0.8f) }
    val path = remember { Path() }

    var sampled by remember { mutableStateOf<SampledGraph?>(null) }
    val liveExpressions by rememberUpdatedState(expressions)
    val liveViewport by rememberUpdatedState(viewport)
    LaunchedEffect(Unit) {
        snapshotFlow { liveExpressions to liveViewport }.collectLatest { (expr, vp) ->
            if (sampled?.covers(expr, vp) == true) return@collectLatest
            val window = vp.overscanned()
            val series = withContext(Dispatchers.Default) {
                CrashGuard.run(emptyList<GraphSeries>()) {
                    FunctionGraph.sampleIn(expr, window, SAMPLE_STEPS)
                }
            }
            sampled = SampledGraph(expr, window, vp.xSpan, vp.ySpan, series)
        }
    }

    val minorGrid = if (dark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f)
    val majorGrid = if (dark) Color.White.copy(alpha = 0.13f) else Color.Black.copy(alpha = 0.13f)
    val axis = if (dark) Color.White.copy(alpha = 0.72f) else Color.Black.copy(alpha = 0.72f)
    val bg = if (dark) Color(0xFF121212) else Color(0xFFF7F7F7)
    // Stale curves (old formula) are dropped instead of drawn against the new labels.
    val current = sampled?.takeIf { it.expressions == expressions }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(bg),
    ) {
        clipRect {
            drawGridAxesAndLabels(viewport, minorGrid, majorGrid, axis, tickPx, gridTargetPx, labelPaint)
            current?.series?.forEach { item ->
                val i = item.sourceIndex
                val color = colors.getOrElse(i) { GraphSeriesPalette[i % GraphSeriesPalette.size] }
                if (item.fillPolygons.isNotEmpty()) {
                    val fillColor = color.copy(alpha = if (dark) 0.20f else 0.16f)
                    drawFillPolygons(item.fillPolygons, viewport, fillColor, path)
                }
                drawSeries(
                    points = item.points,
                    viewport = viewport,
                    color = color,
                    path = path,
                    stroke = if (item.dashed) dashedCurveStroke else curveStroke,
                    dotRadius = markerPx,
                )
                drawMarkers(item, viewport, color, bg, markerPx, markerStroke)
            }
        }
    }
}

private fun DrawScope.drawGridAxesAndLabels(
    viewport: GraphViewport,
    minorGrid: Color,
    majorGrid: Color,
    axis: Color,
    tickPx: Float,
    targetPx: Float,
    labelPaint: Paint,
) {
    if (size.width <= 1f || size.height <= 1f) return
    val xSpan = viewport.xSpan
    val ySpan = viewport.ySpan
    fun sx(x: Double) = ((x - viewport.xMin) / xSpan * size.width).toFloat()
    fun sy(y: Double) = ((viewport.yMax - y) / ySpan * size.height).toFloat()
    // One major line every ~targetPx, whatever the zoom level.
    val xStep = niceStep(xSpan * targetPx / size.width)
    val yStep = niceStep(ySpan * targetPx / size.height)
    val xMinor = xStep / minorDivisions(xStep)
    val yMinor = yStep / minorDivisions(yStep)
    val native = drawContext.canvas.nativeCanvas

    forEachTick(viewport.xMin, viewport.xMax, xMinor) { x ->
        val px = sx(x)
        drawLine(minorGrid, Offset(px, 0f), Offset(px, size.height), strokeWidth = 1f)
    }
    forEachTick(viewport.yMin, viewport.yMax, yMinor) { y ->
        val py = sy(y)
        drawLine(minorGrid, Offset(0f, py), Offset(size.width, py), strokeWidth = 1f)
    }
    forEachTick(viewport.xMin, viewport.xMax, xStep) { x ->
        val px = sx(x)
        drawLine(majorGrid, Offset(px, 0f), Offset(px, size.height), strokeWidth = 1.4f)
    }
    forEachTick(viewport.yMin, viewport.yMax, yStep) { y ->
        val py = sy(y)
        drawLine(majorGrid, Offset(0f, py), Offset(size.width, py), strokeWidth = 1.4f)
    }

    val axisX = sx(0.0).coerceIn(0f, size.width)
    val axisY = sy(0.0).coerceIn(0f, size.height)
    drawLine(axis, Offset(axisX, 0f), Offset(axisX, size.height), strokeWidth = 2f)
    drawLine(axis, Offset(0f, axisY), Offset(size.width, axisY), strokeWidth = 2f)

    val textSize = labelPaint.textSize
    forEachTick(viewport.xMin, viewport.xMax, xStep) { x ->
        if (abs(x) < xStep * 0.25) return@forEachTick
        val px = sx(x)
        drawLine(axis, Offset(px, axisY - tickPx), Offset(px, axisY + tickPx), strokeWidth = 1.6f)
        val label = tickLabel(x, xStep)
        val tw = labelPaint.measureText(label)
        val tx = (px - tw / 2f).coerceIn(2f, (size.width - tw - 2f).coerceAtLeast(2f))
        val below = axisY + tickPx + textSize + 2f
        val ty = if (below <= size.height - 4f) below else axisY - tickPx - 4f
        native.drawText(label, tx, ty.coerceIn(textSize + 2f, size.height - 4f), labelPaint)
    }
    forEachTick(viewport.yMin, viewport.yMax, yStep) { y ->
        if (abs(y) < yStep * 0.25) return@forEachTick
        val py = sy(y)
        drawLine(axis, Offset(axisX - tickPx, py), Offset(axisX + tickPx, py), strokeWidth = 1.6f)
        val label = tickLabel(y, yStep)
        val tw = labelPaint.measureText(label)
        val left = axisX - tickPx - tw - 4f
        val tx = if (left >= 2f) left else axisX + tickPx + 4f
        val ty = (py + textSize / 3f).coerceIn(textSize, size.height - 4f)
        native.drawText(label, tx.coerceAtMost(size.width - tw - 2f), ty, labelPaint)
    }
    native.drawText("0", (axisX + 6f).coerceAtMost(size.width - 12f), (axisY - 6f).coerceAtLeast(12f), labelPaint)
}

private inline fun forEachTick(from: Double, to: Double, step: Double, block: (Double) -> Unit) {
    if (!(step > 0.0) || !step.isFinite()) return
    val first = ceil(from / step).toLong()
    val last = floor(to / step).toLong()
    if (last - first > 400) return
    for (k in first..last) block(k * step)
}

private fun DrawScope.drawFillPolygons(
    polygons: List<List<GraphPoint>>,
    viewport: GraphViewport,
    color: Color,
    path: Path,
) {
    if (polygons.isEmpty()) return
    val w = size.width
    val h = size.height
    val xMin = viewport.xMin
    val yMax = viewport.yMax
    val kx = w / viewport.xSpan
    val ky = h / viewport.ySpan
    val loX = -4f * w
    val hiX = 5f * w
    val loY = -4f * h
    val hiY = 5f * h
    for (poly in polygons) {
        if (poly.size < 3) continue
        path.reset()
        var started = false
        for (p in poly) {
            if (!p.finite) continue
            val px = ((p.x - xMin) * kx).toFloat().coerceIn(loX, hiX)
            val py = ((yMax - p.y) * ky).toFloat().coerceIn(loY, hiY)
            if (!started) {
                path.moveTo(px, py)
                started = true
            } else {
                path.lineTo(px, py)
            }
        }
        if (started) {
            path.close()
            drawPath(path, color, style = Fill)
        }
    }
}

private fun DrawScope.drawSeries(
    points: List<GraphPoint>,
    viewport: GraphViewport,
    color: Color,
    path: Path,
    stroke: Stroke,
    dotRadius: Float,
) {
    if (points.isEmpty()) return
    val w = size.width
    val h = size.height
    val xMin = viewport.xMin
    val yMax = viewport.yMax
    val kx = w / viewport.xSpan
    val ky = h / viewport.ySpan
    // Keep coordinates in a range Skia rasterises precisely; the clip hides the rest.
    val lo = -4f * h
    val hi = 5f * h
    path.reset()
    var penDown = false
    var runLength = 0
    var lastX = 0f
    var lastY = 0f
    fun flushDot() {
        if (runLength == 1 && lastX in 0f..w && lastY in 0f..h) {
            drawCircle(color, dotRadius, Offset(lastX, lastY), style = Fill)
        }
    }
    for (p in points) {
        if (!p.finite) {
            flushDot()
            penDown = false
            runLength = 0
            continue
        }
        val px = ((p.x - xMin) * kx).toFloat()
        val py = ((yMax - p.y) * ky).toFloat().coerceIn(lo, hi)
        if (penDown) {
            path.lineTo(px, py)
        } else {
            path.moveTo(px, py)
            penDown = true
        }
        runLength++
        lastX = px
        lastY = py
    }
    flushDot()
    drawPath(path, color, style = stroke)
}

private fun DrawScope.drawMarkers(
    series: GraphSeries,
    viewport: GraphViewport,
    color: Color,
    background: Color,
    radius: Float,
    ring: Stroke,
) {
    for (marker in series.markers) {
        if (!marker.x.isFinite() || !marker.y.isFinite()) continue
        if (marker.x < viewport.xMin || marker.x > viewport.xMax) continue
        if (marker.y < viewport.yMin || marker.y > viewport.yMax) continue
        val c = Offset(
            ((marker.x - viewport.xMin) / viewport.xSpan * size.width).toFloat(),
            ((viewport.yMax - marker.y) / viewport.ySpan * size.height).toFloat(),
        )
        when (marker.style) {
            GraphMarkerStyle.Filled -> drawCircle(color, radius * 1.15f, c, style = Fill)
            GraphMarkerStyle.Hollow -> {
                drawCircle(background, radius, c, style = Fill)
                drawCircle(color, radius, c, style = ring)
            }
        }
    }
}

private fun tickLabel(value: Double, step: Double): String {
    if (abs(value) < step * 0.05) return "0"
    if (abs(value) >= 1e6 || step < 1e-4) {
        return String.format(java.util.Locale.US, "%.2g", value).replace("e+0", "e").replace("e+", "e")
    }
    val decimals = (-floor(log10(step))).toInt().coerceIn(0, 6)
    var s = String.format(java.util.Locale.US, "%.${decimals}f", value)
    if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
    if (s == "-0") s = "0"
    return s
}

/** Smallest 1·10ⁿ, 2·10ⁿ or 5·10ⁿ that is ≥ [raw]. */
private fun niceStep(raw: Double): Double {
    if (raw <= 0.0 || !raw.isFinite()) return 1.0
    val base = 10.0.pow(floor(log10(raw)))
    val err = raw / base
    val nice = when {
        err <= 1.0 -> 1.0
        err <= 2.0 -> 2.0
        err <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * base
}

private fun minorDivisions(step: Double): Int {
    val mantissa = step / 10.0.pow(floor(log10(step)))
    return if (abs(mantissa - 2.0) < 1e-6) 4 else 5
}
