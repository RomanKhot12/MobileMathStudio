package com.example.mobilemathstudio.math.graph

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class GraphViewport(
    val xMin: Double = -10.0,
    val xMax: Double = 10.0,
    val yMin: Double = -10.0,
    val yMax: Double = 10.0,
) {
    val xSpan: Double get() = (xMax - xMin).takeIf { it > 1e-12 } ?: 1.0
    val ySpan: Double get() = (yMax - yMin).takeIf { it > 1e-12 } ?: 1.0

    fun pan(dxMath: Double, dyMath: Double): GraphViewport = copy(
        xMin = xMin + dxMath,
        xMax = xMax + dxMath,
        yMin = yMin + dyMath,
        yMax = yMax + dyMath,
    )

    /** [factor] > 1 zooms in around the math-space pivot. */
    fun zoom(factor: Double, pivotX: Double, pivotY: Double): GraphViewport {
        val f = factor.coerceIn(1.04 / MAX_ZOOM, MAX_ZOOM)
        return copy(
            xMin = pivotX - (pivotX - xMin) / f,
            xMax = pivotX + (xMax - pivotX) / f,
            yMin = pivotY - (pivotY - yMin) / f,
            yMax = pivotY + (yMax - pivotY) / f,
        ).sanitized()
    }

    fun sanitized(): GraphViewport {
        var left = if (xMin.isFinite()) xMin else -10.0
        var right = if (xMax.isFinite()) xMax else 10.0
        var bottom = if (yMin.isFinite()) yMin else -10.0
        var top = if (yMax.isFinite()) yMax else 10.0
        if (right <= left) {
            left = -10.0
            right = 10.0
        }
        if (top <= bottom) {
            bottom = -10.0
            top = 10.0
        }
        val xSpanNow = right - left
        val ySpanNow = top - bottom
        if (xSpanNow < MIN_SPAN) {
            val c = (left + right) / 2
            left = c - MIN_SPAN / 2
            right = c + MIN_SPAN / 2
        }
        if (ySpanNow < MIN_SPAN) {
            val c = (bottom + top) / 2
            bottom = c - MIN_SPAN / 2
            top = c + MIN_SPAN / 2
        }
        if (xSpanNow > MAX_SPAN) {
            val c = (left + right) / 2
            left = c - MAX_SPAN / 2
            right = c + MAX_SPAN / 2
        }
        if (ySpanNow > MAX_SPAN) {
            val c = (bottom + top) / 2
            bottom = c - MAX_SPAN / 2
            top = c + MAX_SPAN / 2
        }
        return copy(xMin = left, xMax = right, yMin = bottom, yMax = top)
    }

    fun xOf(px: Float, width: Float): Double {
        if (width <= 0f) return (xMin + xMax) / 2
        return xMin + px / width * xSpan
    }

    fun yOf(py: Float, height: Float): Double {
        if (height <= 0f) return (yMin + yMax) / 2
        return yMax - py / height * ySpan
    }

    companion object {
        private const val MIN_SPAN = 1e-4
        private const val MAX_SPAN = 1e6
        private const val MAX_ZOOM = 12.0
    }
}

data class GraphDomain(
    val xMin: Double? = null,
    val xMax: Double? = null,
    val minInclusive: Boolean = true,
    val maxInclusive: Boolean = true,
) {
    fun contains(x: Double, eps: Double = 1e-9): Boolean {
        xMin?.let { min ->
            if (minInclusive) {
                if (x < min - eps) return false
            } else if (x <= min + eps) return false
        }
        xMax?.let { max ->
            if (maxInclusive) {
                if (x > max + eps) return false
            } else if (x >= max - eps) return false
        }
        return true
    }

    fun clip(xMinView: Double, xMaxView: Double): Pair<Double, Double> {
        val a = max(xMinView, xMin ?: xMinView)
        val b = min(xMaxView, xMax ?: xMaxView)
        return a to b
    }

    fun isEmpty(eps: Double = 1e-12): Boolean {
        val lo = xMin ?: return false
        val hi = xMax ?: return false
        if (lo > hi + eps) return true
        if (abs(lo - hi) <= eps && !(minInclusive && maxInclusive)) return true
        return false
    }

    /** Returns the intersection of two intervals (strict boundary wins on equal bounds). */
    fun intersect(other: GraphDomain): GraphDomain {
        val (lo, loInc) = when {
            xMin == null -> other.xMin to other.minInclusive
            other.xMin == null -> xMin to minInclusive
            other.xMin > xMin + 1e-12 -> other.xMin to other.minInclusive
            xMin > other.xMin + 1e-12 -> xMin to minInclusive
            else -> xMin to (minInclusive && other.minInclusive)
        }
        val (hi, hiInc) = when {
            xMax == null -> other.xMax to other.maxInclusive
            other.xMax == null -> xMax to maxInclusive
            other.xMax < xMax - 1e-12 -> other.xMax to other.maxInclusive
            xMax < other.xMax - 1e-12 -> xMax to maxInclusive
            else -> xMax to (maxInclusive && other.maxInclusive)
        }
        return GraphDomain(xMin = lo, xMax = hi, minInclusive = loInc, maxInclusive = hiInc)
    }
}

enum class GraphMarkerStyle { Filled, Hollow }

data class GraphMarker(
    val x: Double,
    val y: Double,
    val style: GraphMarkerStyle,
)

/**
 * Pulls `[a, b]`, `(a, b)`, `x∈[a,b]` and `a≤x≤b` off an expression so the
 * remaining body can be sampled as y = f(x).
 */
object GraphDomainParser {

    fun split(raw: String): Pair<String, GraphDomain?> {
        val source = raw.trim()
        if (source.isEmpty()) return source to null
        membership.find(source)?.let { m ->
            return source.removeRange(m.range).trim().trimEnd(',').trim() to
                domainFromBrackets(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
        }
        intervalAtEnd.find(source)?.let { m ->
            return source.removeRange(m.range).trim().trimEnd(',').trim() to
                domainFromBrackets(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
        }
        chain.find(source)?.let { m ->
            val minInc = m.groupValues[2] in INCLUSIVE
            val maxInc = m.groupValues[3] in INCLUSIVE
            val a = m.groupValues[1].toDoubleOrNull()
            val b = m.groupValues[4].toDoubleOrNull()
            if (a != null && b != null) {
                return source.removeRange(m.range).trim().trimEnd(',').trim() to
                    GraphDomain(xMin = a, xMax = b, minInclusive = minInc, maxInclusive = maxInc)
            }
        }
        val lower = oneSidedLower.find(source)
        val upper = oneSidedUpper.find(source)
        if (lower != null || upper != null) {
            var body = source
            var min: Double? = null
            var max: Double? = null
            var minInc = true
            var maxInc = true
            if (lower != null) {
                minInc = lower.groupValues[1] in INCLUSIVE
                min = lower.groupValues[2].toDoubleOrNull()
                body = body.removeRange(lower.range)
            }
            val upper2 = oneSidedUpper.find(body)
            if (upper2 != null) {
                maxInc = upper2.groupValues[1] in INCLUSIVE
                max = upper2.groupValues[2].toDoubleOrNull()
                body = body.removeRange(upper2.range)
            }
            body = body.replace(Regex("""[,;]\s*$"""), "").trim()
            if (min != null || max != null) {
                return body to GraphDomain(xMin = min, xMax = max, minInclusive = minInc, maxInclusive = maxInc)
            }
        }
        return source to null
    }

    private fun domainFromBrackets(open: String, aRaw: String, bRaw: String, close: String): GraphDomain? {
        val a = aRaw.toDoubleOrNull() ?: return null
        val b = bRaw.toDoubleOrNull() ?: return null
        return GraphDomain(
            xMin = min(a, b),
            xMax = max(a, b),
            minInclusive = open == "[",
            maxInclusive = close == "]",
        )
    }

    private val INCLUSIVE = setOf("<=", "≤", ">=", "≥")

    private val intervalAtEnd = Regex(
        """[,;]?\s*([\[(])\s*([-+0-9.]+)\s*[,;]\s*([-+0-9.]+)\s*([\])])\s*$""",
    )
    private val membership = Regex(
        """[,;]?\s*(?:при|for|where)?\s*x\s*(?:∈|in)\s*([\[(])\s*([-+0-9.]+)\s*[,;]\s*([-+0-9.]+)\s*([\])])\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val chain = Regex(
        """[,;]?\s*([-+0-9.]+)\s*(<=|<|≤)\s*x\s*(<=|<|≤)\s*([-+0-9.]+)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val oneSidedLower = Regex(
        """[,;]?\s*x\s*(>=|>|≥)\s*([-+0-9.]+)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val oneSidedUpper = Regex(
        """[,;]?\s*x\s*(<=|<|≤)\s*([-+0-9.]+)\s*$""",
        RegexOption.IGNORE_CASE,
    )
}
