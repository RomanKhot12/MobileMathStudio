package com.example.mobilemathstudio.math.graph

import com.example.mobilemathstudio.math.CompiledExpression
import com.example.mobilemathstudio.math.InequalityDomainSolver
import com.example.mobilemathstudio.math.MathEngine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

data class GraphPoint(val x: Double, val y: Double) {
    val finite: Boolean get() = x.isFinite() && y.isFinite()
}

/**
 * A plotted trace. Non-finite points split [points] into separate contours, so a
 * renderer must `moveTo` after every gap instead of bridging it.
 * [sourceIndex] is the index of the input line the trace came from (colour key).
 * [dashed] draws the trace with a dashed stroke (strict inequality boundary `<` / `>`).
 * [fillPolygons] holds closed world-space polygons shaded softly for inequality regions.
 */
data class GraphSeries(
    val label: String,
    val points: List<GraphPoint>,
    val markers: List<GraphMarker> = emptyList(),
    val sourceIndex: Int = 0,
    val dashed: Boolean = false,
    val fillPolygons: List<List<GraphPoint>> = emptyList(),
)

/**
 * Samples y = f(x), x = g(y), implicit curves F(x, y) = 0, systems with inequality
 * domain clipping (`{ x = y ; x > 3 }`), standalone inequality half-planes (`y > x + 1`,
 * `x ≤ 2`), points and lines inside a window.
 *
 * Pure and thread-safe: meant to be called off the main thread.
 */
object FunctionGraph {

    private const val MAX_DEPTH = 4
    private const val REGION_GRID = 56

    fun sample(
        expressions: List<String>,
        xMin: Double = -10.0,
        xMax: Double = 10.0,
        steps: Int = 360,
        yMin: Double = xMin,
        yMax: Double = xMax,
    ): List<GraphSeries> {
        if (!(xMax > xMin) || steps < 2) return emptyList()
        val window = Window(xMin, xMax, yMin, if (yMax > yMin) yMax else yMin + (xMax - xMin))

        // Preserve system grouping: each element of `expressions` is one cell/block,
        // whose newline-separated lines belong to the same system `{ ... }`.
        // Global sequential `lineIndex` preserves per-line `sourceIndex` colour mapping.
        val groups = ArrayList<List<IndexedLine>>(expressions.size)
        var globalIndex = 0
        for (entry in expressions) {
            val lines = entry.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { line -> IndexedLine(globalIndex++, line) }
            if (lines.isNotEmpty()) groups += lines
        }
        if (groups.isEmpty()) return emptyList()

        val series = ArrayList<GraphSeries>()
        val looseXConsts = ArrayList<Pair<Int, Atom.XConst>>()
        val looseYConsts = ArrayList<Pair<Int, Atom.YConst>>()

        for (group in groups) {
            val equationLines = ArrayList<IndexedLine>()
            val inequalityLines = ArrayList<Pair<IndexedLine, List<InequalityDomainSolver.Relation>>>()
            for (line in group) {
                val compact = MathEngine.normalizeForCheck(line.raw)
                val rels = InequalityDomainSolver.splitRelations(compact)
                if (rels.isNotEmpty()) {
                    inequalityLines += line to rels
                } else {
                    equationLines += line
                }
            }

            when {
                // Case 1: System combining curve/equation(s) with inequality domain constraint(s).
                // Inequalities clip the curve(s) in this system rather than shading half-planes.
                equationLines.isNotEmpty() && inequalityLines.isNotEmpty() -> {
                    val clip = compileSystemClip(inequalityLines.flatMap { it.second })
                    if (!clip.isEmpty) {
                        for (eqLine in equationLines) {
                            series += sampleClippedEquation(eqLine.raw, clip, window, steps, eqLine.index)
                        }
                    }
                }

                // Case 2: Standalone inequality (or system of inequalities only) → shaded region + boundary.
                equationLines.isEmpty() && inequalityLines.isNotEmpty() -> {
                    series += sampleInequalityGroup(inequalityLines, window, steps)
                }

                // Case 3: Pure equations/points/constants (preserve classic pairing across loose constants).
                else -> {
                    for (eqLine in equationLines) {
                        when (val atom = classifyAtom(eqLine.raw)) {
                            is Atom.Point -> series += pointSeries(atom.label, atom.x, atom.y, eqLine.index)
                            is Atom.XConst -> looseXConsts += eqLine.index to atom
                            is Atom.YConst -> looseYConsts += eqLine.index to atom
                            is Atom.Curve -> series += sampleOne(atom.raw, null, window, steps, eqLine.index)
                        }
                    }
                }
            }
        }

        if (looseXConsts.size == 1 && looseYConsts.size == 1) {
            val (xi, x) = looseXConsts.removeAt(0)
            val (_, y) = looseYConsts.removeAt(0)
            series += pointSeries("${x.label}, ${y.label}", x.x, y.y, xi)
        }
        looseXConsts.forEach { (index, atom) ->
            series += vertical(atom.label, atom.x, window, index)
        }
        looseYConsts.forEach { (index, atom) ->
            series += horizontal(atom.label, atom.y, window.xMin, window.xMax, index)
        }
        return series
    }

    fun sampleIn(expressions: List<String>, viewport: GraphViewport, steps: Int = 360): List<GraphSeries> =
        sample(expressions, viewport.xMin, viewport.xMax, steps, viewport.yMin, viewport.yMax)

    private data class IndexedLine(val index: Int, val raw: String)

    private class Window(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double) {
        val xSpan get() = xMax - xMin
        val ySpan get() = yMax - yMin
    }

    private fun pointSeries(label: String, x: Double, y: Double, index: Int) = GraphSeries(
        label = label,
        points = listOf(GraphPoint(x, y)),
        markers = listOf(GraphMarker(x, y, GraphMarkerStyle.Filled)),
        sourceIndex = index,
    )

    // ── System inequality compilation & clipping ─────────────────────────────

    private class CompiledConstraint(
        val relation: InequalityDomainSolver.Relation,
        private val diffExpr: CompiledExpression,
        val strict: Boolean,
        val isGreater: Boolean, // true for > / >= on (left - right), false for < / <=
    ) {
        private val vars = HashMap<String, Double>(4)

        fun diff(x: Double, y: Double): Double {
            vars["x"] = x
            vars["X"] = x
            vars["y"] = y
            vars["Y"] = y
            return diffExpr.evaluateOrNaN(vars)
        }

        fun holds(x: Double, y: Double, eps: Double = 1e-9): Boolean {
            val d = diff(x, y)
            if (!d.isFinite()) return false
            return when {
                isGreater && strict -> d > eps
                isGreater && !strict -> d >= -eps
                !isGreater && strict -> d < -eps
                else -> d <= eps
            }
        }

        /** True when `(x, y)` satisfies this constraint OR sits on its boundary (for marker checks). */
        fun holdsClosed(x: Double, y: Double, eps: Double = 1e-4): Boolean {
            val d = diff(x, y)
            if (!d.isFinite()) return false
            return if (isGreater) d >= -eps else d <= eps
        }

        fun isOnBoundary(x: Double, y: Double, tol: Double): Boolean {
            val d = diff(x, y)
            return d.isFinite() && abs(d) <= tol
        }
    }

    private class SystemClip(
        val xDomain: GraphDomain?,
        val yDomain: GraphDomain?,
        val constraints: List<CompiledConstraint>,
    ) {
        val isEmpty: Boolean
            get() = (xDomain?.isEmpty() == true) || (yDomain?.isEmpty() == true)

        fun holdsAll(x: Double, y: Double): Boolean = constraints.all { it.holds(x, y) }

        /**
         * Determines whether a boundary point `(x, y)` should be drawn Filled or Hollow:
         * if ANY active constraint whose boundary passes through `(x, y)` is strict, return Hollow;
         * if any constraint is strictly violated at `(x, y)`, return null (point is outside domain).
         */
        fun markerStyleAt(x: Double, y: Double, tol: Double): GraphMarkerStyle? {
            if (!x.isFinite() || !y.isFinite()) return null
            for (c in constraints) {
                if (!c.holdsClosed(x, y, max(tol * 2.0, 1e-4))) return null
            }
            val strictBoundary = constraints.any { it.strict && it.isOnBoundary(x, y, max(tol * 4.0, 1e-3)) }
            return if (strictBoundary) GraphMarkerStyle.Hollow else GraphMarkerStyle.Filled
        }
    }

    private fun compileConstraint(rel: InequalityDomainSolver.Relation): CompiledConstraint? {
        val diff = CompiledExpression.compileOrNull("(${rel.left})-(${rel.right})") ?: return null
        val strict = rel.op == ">" || rel.op == "<"
        val isGreater = rel.op == ">" || rel.op == ">=" || rel.op == "≥"
        return CompiledConstraint(rel, diff, strict, isGreater)
    }

    private fun compileSystemClip(relations: List<InequalityDomainSolver.Relation>): SystemClip {
        var xDom: GraphDomain? = null
        var yDom: GraphDomain? = null
        val compiled = ArrayList<CompiledConstraint>(relations.size)
        for (rel in relations) {
            compileConstraint(rel)?.let { compiled += it }
            extractLinearAxisBound(rel, "x")?.let { bound ->
                xDom = xDom?.intersect(bound) ?: bound
            }
            extractLinearAxisBound(rel, "y")?.let { bound ->
                yDom = yDom?.intersect(bound) ?: bound
            }
        }
        return SystemClip(xDom, yDom, compiled)
    }

    /**
     * If `rel` is a pure linear inequality in [axisVar] alone (`a*v + b > 0`, e.g. `x > 3`,
     * `3 < x`, `2x - 6 >= 0`, `-y <= 4`), extracts its exact [GraphDomain] bound.
     */
    private fun extractLinearAxisBound(
        rel: InequalityDomainSolver.Relation,
        axisVar: String,
    ): GraphDomain? {
        val unknowns = unknownsOf("(${rel.left})+(${rel.right})")
        if (unknowns != setOf(axisVar)) return null
        val diff = CompiledExpression.compileOrNull("(${rel.left})-(${rel.right})") ?: return null
        val samples = listOf(-2.0, 0.0, 1.0, 3.5)
        val vals = samples.map { v ->
            val y = diff.evaluateOrNaN(mapOf(axisVar to v, axisVar.uppercase() to v))
            if (!y.isFinite()) return null
            v to y
        }
        val b = vals[1].second
        val a = vals[2].second - b
        if (abs(a) < 1e-12) return null
        if (vals.any { (v, y) -> abs(a * v + b - y) > 1e-6 }) return null
        val root = -b / a
        if (!root.isFinite()) return null
        val inclusive = rel.op == ">=" || rel.op == "≥" || rel.op == "<=" || rel.op == "≤"
        val origGreater = rel.op == ">" || rel.op == ">=" || rel.op == "≥"
        val isLowerBound = if (a > 0) origGreater else !origGreater
        return if (isLowerBound) {
            GraphDomain(xMin = root, minInclusive = inclusive)
        } else {
            GraphDomain(xMax = root, maxInclusive = inclusive)
        }
    }

    private fun sampleClippedEquation(
        raw: String,
        clip: SystemClip,
        window: Window,
        steps: Int,
        index: Int,
    ): List<GraphSeries> {
        val atom = classifyAtom(raw)
        return when (atom) {
            is Atom.Point -> {
                if (clip.holdsAll(atom.x, atom.y)) listOf(pointSeries(atom.label, atom.x, atom.y, index))
                else emptyList()
            }
            is Atom.XConst -> {
                // Vertical line x = c clipped by Y-bounds and general constraints.
                listOfNotNull(sampleExplicitXOfY(atom.label, atom.x.toString(), clip, window, steps, index))
            }
            is Atom.YConst -> {
                // Horizontal line y = c clipped by X-bounds and general constraints.
                sampleOne(atom.label, clip, window, steps, index)
            }
            is Atom.Curve -> sampleOne(atom.raw, clip, window, steps, index)
        }
    }

    // ── Standalone inequality & inequality-system shading ────────────────────

    private fun sampleInequalityGroup(
        inequalityLines: List<Pair<IndexedLine, List<InequalityDomainSolver.Relation>>>,
        window: Window,
        steps: Int,
    ): List<GraphSeries> {
        val allPairs = inequalityLines.flatMap { (line, rels) -> rels.map { line to it } }
        val allCompiled = allPairs.mapNotNull { (_, rel) -> compileConstraint(rel) }
        if (allCompiled.isEmpty()) return emptyList()

        // Fast exact polygon path for a single standalone inequality (e.g. `y > x + 1`, `x ≤ 2`, `y ≤ x^2`).
        if (allPairs.size == 1) {
            val (line, rel) = allPairs.single()
            sampleSingleInequality(line.raw, rel, window, steps, line.index)?.let { return listOf(it) }
        }

        // General multi-inequality region (or chained `-2 < x ≤ 3`):
        // 1) Shaded intersection region of ALL inequalities in this group.
        val regionPolys = buildGridRegionPolygons(allCompiled, window)
        val out = ArrayList<GraphSeries>(allPairs.size + 1)
        val firstLine = inequalityLines.first().first
        if (regionPolys.isNotEmpty()) {
            out += GraphSeries(
                label = firstLine.raw,
                points = emptyList(),
                markers = emptyList(),
                sourceIndex = firstLine.index,
                dashed = false,
                fillPolygons = regionPolys,
            )
        }
        // 2) Each boundary curve (dashed for `<`/`>`, solid for `≤`/`≥`), clipped to the closed other inequalities.
        for ((line, rel) in allPairs) {
            val strict = rel.op == ">" || rel.op == "<"
            val eqRaw = "${rel.left}=${rel.right}"
            val boundarySeries = sampleOne(eqRaw, null, window, steps, line.index)
            for (b in boundarySeries) {
                out += b.copy(label = line.raw, dashed = strict)
            }
        }
        return out
    }

    private fun sampleSingleInequality(
        raw: String,
        rel: InequalityDomainSolver.Relation,
        window: Window,
        steps: Int,
        index: Int,
    ): GraphSeries? {
        val strict = rel.op == ">" || rel.op == "<"
        val isGreater = rel.op == ">" || rel.op == ">=" || rel.op == "≥"
        val lUnk = unknownsOf(rel.left)
        val rUnk = unknownsOf(rel.right)
        val padY = max(window.ySpan, 1.0)
        val padX = max(window.xSpan, 1.0)

        // 1) Explicit y > f(x) / y < f(x) / f(x) < y / f(x) > y
        val explicitY: Pair<String, Boolean>? = when {
            isSoloVar(rel.left, "y") && "y" !in rUnk -> rel.right to isGreater
            isSoloVar(rel.right, "y") && "y" !in lUnk -> rel.left to !isGreater
            else -> null
        }
        if (explicitY != null) {
            val (rhsBody, shadeAbove) = explicitY
            val boundary = sampleExplicit(raw, rhsBody, window.xMin, window.xMax, steps, null, null, window, index)
                ?: return null
            val capY = if (shadeAbove) window.yMax + padY else window.yMin - padY
            val polys = buildPolygonsFromExplicitY(boundary.points, capY)
            return boundary.copy(dashed = strict, fillPolygons = polys)
        }

        // 2) Pure linear X half-plane: `x ≤ 2`, `x > -1`, `2x < 6`, `3 ≥ x`
        extractLinearAxisBound(rel, "x")?.let { dom ->
            val xVal = dom.xMin ?: dom.xMax ?: return@let
            val shadeRight = dom.xMin != null
            val yLo = window.yMin - padY
            val yHi = window.yMax + padY
            val xEdge = if (shadeRight) window.xMax + padX else window.xMin - padX
            val poly = listOf(
                GraphPoint(xVal, yLo),
                GraphPoint(xEdge, yLo),
                GraphPoint(xEdge, yHi),
                GraphPoint(xVal, yHi),
            )
            return GraphSeries(
                label = raw,
                points = listOf(GraphPoint(xVal, yLo), GraphPoint(xVal, yHi)),
                markers = emptyList(),
                sourceIndex = index,
                dashed = strict,
                fillPolygons = listOf(poly),
            )
        }

        // 3) Pure linear Y half-plane: `y ≥ 2`, `2y < 6`
        extractLinearAxisBound(rel, "y")?.let { dom ->
            val yVal = dom.xMin ?: dom.xMax ?: return@let
            val shadeAbove = dom.xMin != null
            val xLo = window.xMin - padX
            val xHi = window.xMax + padX
            val yEdge = if (shadeAbove) window.yMax + padY else window.yMin - padY
            val poly = listOf(
                GraphPoint(xLo, yVal),
                GraphPoint(xHi, yVal),
                GraphPoint(xHi, yEdge),
                GraphPoint(xLo, yEdge),
            )
            return GraphSeries(
                label = raw,
                points = listOf(GraphPoint(xLo, yVal), GraphPoint(xHi, yVal)),
                markers = emptyList(),
                sourceIndex = index,
                dashed = strict,
                fillPolygons = listOf(poly),
            )
        }

        // 4) General 2D inequality (e.g. `x^2 + y^2 < 4`, `x + y ≥ 2`)
        val constraint = compileConstraint(rel) ?: return null
        val boundary = sampleImplicit(raw, "(${rel.left})-(${rel.right})", window.xMin, window.xMax, steps, null, null, window, index)
        val polys = buildGridRegionPolygons(listOf(constraint), window)
        return GraphSeries(
            label = raw,
            points = boundary?.points.orEmpty(),
            markers = emptyList(),
            sourceIndex = index,
            dashed = strict,
            fillPolygons = polys,
        )
    }

    private fun buildPolygonsFromExplicitY(
        points: List<GraphPoint>,
        capY: Double,
    ): List<List<GraphPoint>> {
        val polys = ArrayList<List<GraphPoint>>()
        val run = ArrayList<GraphPoint>()
        fun flush() {
            if (run.size >= 2) {
                val poly = ArrayList<GraphPoint>(run.size + 2)
                poly += run
                poly += GraphPoint(run.last().x, capY)
                poly += GraphPoint(run.first().x, capY)
                polys += poly
            }
            run.clear()
        }
        for (p in points) {
            if (p.finite) run += p else flush()
        }
        flush()
        return polys
    }

    /**
     * Builds compact horizontal-span rectangles covering every cell of a `REGION_GRID × REGION_GRID`
     * grid where all [constraints] hold. Merging spans per row keeps draw cost tiny (~60 rects).
     */
    private fun buildGridRegionPolygons(
        constraints: List<CompiledConstraint>,
        window: Window,
    ): List<List<GraphPoint>> {
        val nx = REGION_GRID
        val ny = REGION_GRID
        val hx = window.xSpan / nx
        val hy = window.ySpan / ny
        val out = ArrayList<List<GraphPoint>>(ny * 2)
        for (j in 0 until ny) {
            val y0 = window.yMin + hy * j
            val y1 = y0 + hy
            val yc = (y0 + y1) * 0.5
            var spanStart = -1
            for (i in 0..nx) {
                val inside = i < nx && constraints.all { it.holds(window.xMin + hx * (i + 0.5), yc) }
                if (inside && spanStart < 0) {
                    spanStart = i
                } else if (!inside && spanStart >= 0) {
                    val x0 = window.xMin + hx * spanStart
                    val x1 = window.xMin + hx * i
                    out += listOf(
                        GraphPoint(x0, y0),
                        GraphPoint(x1, y0),
                        GraphPoint(x1, y1),
                        GraphPoint(x0, y1),
                    )
                    spanStart = -1
                }
            }
        }
        return out
    }

    // ── Classification & sampling dispatch ───────────────────────────────────

    private fun classifyAtom(raw: String): Atom {
        val (bodyRaw, domain) = GraphDomainParser.split(raw)
        val n = MathEngine.normalizeForCheck(bodyRaw.ifBlank { raw })
        if ('=' !in n) {
            parenPoint.find(n)?.let { m ->
                val x = m.groupValues[1].toDoubleOrNull() ?: return@let
                val y = m.groupValues[2].toDoubleOrNull() ?: return@let
                return Atom.Point(x, y, raw)
            }
        }
        xyPair.matchEntire(n)?.let { m ->
            val x = constantValue(m.groupValues[1]) ?: return@let
            val y = constantValue(m.groupValues[2]) ?: return@let
            return Atom.Point(x, y, raw)
        }
        yxPair.matchEntire(n)?.let { m ->
            val y = constantValue(m.groupValues[1]) ?: return@let
            val x = constantValue(m.groupValues[2]) ?: return@let
            return Atom.Point(x, y, raw)
        }
        val (left, right) = splitEq(n) ?: return Atom.Curve(raw)
        val constantAtom: Atom? = when {
            isSoloVar(left, "x") && unknownsOf(right).isEmpty() ->
                constantValue(right)?.let { Atom.XConst(it, raw) }
            isSoloVar(left, "y") && unknownsOf(right).isEmpty() ->
                constantValue(right)?.let { Atom.YConst(it, raw) }
            isSoloVar(right, "x") && unknownsOf(left).isEmpty() ->
                constantValue(left)?.let { Atom.XConst(it, raw) }
            isSoloVar(right, "y") && unknownsOf(left).isEmpty() ->
                constantValue(left)?.let { Atom.YConst(it, raw) }
            else -> null
        }
        if (constantAtom != null) {
            // Explicit domain → a segment, not an infinite axis-aligned line.
            if (domain != null) return Atom.Curve(raw)
            return constantAtom
        }
        return Atom.Curve(raw)
    }

    private fun constantValue(expr: String): Double? {
        if (unknownsOf(expr).isNotEmpty()) return null
        val v = CompiledExpression.compileOrNull(expr)?.evaluateOrNaN(emptyMap()) ?: return null
        return v.takeIf { it.isFinite() }
    }

    private sealed class Atom {
        data class Point(val x: Double, val y: Double, val label: String) : Atom()
        data class XConst(val x: Double, val label: String) : Atom()
        data class YConst(val y: Double, val label: String) : Atom()
        data class Curve(val raw: String) : Atom()
    }

    private val parenPoint = Regex(
        """\(([+-]?(?:\d+\.?\d*|\.\d+))[,;]([+-]?(?:\d+\.?\d*|\.\d+))\)""",
    )
    private val xyPair = Regex("""^x=(.+?)[,;]?y=(.+)$""", RegexOption.IGNORE_CASE)
    private val yxPair = Regex("""^y=(.+?)[,;]?x=(.+)$""", RegexOption.IGNORE_CASE)

    private fun sampleOne(
        raw: String,
        clip: SystemClip?,
        window: Window,
        steps: Int,
        index: Int,
    ): List<GraphSeries> {
        val (bodyRaw, inlineDomain) = GraphDomainParser.split(raw)
        val expr = MathEngine.normalizeForCheck(bodyRaw.ifBlank { raw })
        if (expr.isBlank()) return emptyList()

        val mergedXDomain: GraphDomain? = when {
            inlineDomain != null && clip?.xDomain != null -> inlineDomain.intersect(clip.xDomain)
            inlineDomain != null -> inlineDomain
            else -> clip?.xDomain
        }
        if (mergedXDomain?.isEmpty() == true) return emptyList()

        val (lo, hi) = (mergedXDomain ?: GraphDomain()).clip(window.xMin, window.xMax)
        if (hi < lo - 1e-12) return emptyList()

        val eq = splitEq(expr)
        if (eq != null) {
            val (left, right) = eq
            val lUnk = unknownsOf(left)
            val rUnk = unknownsOf(right)
            return when {
                isSoloVar(left, "y") && "y" !in rUnk ->
                    if (hi <= lo) emptyList()
                    else listOfNotNull(sampleExplicit(raw, right, lo, hi, steps, mergedXDomain, clip, window, index))
                isSoloVar(right, "y") && "y" !in lUnk ->
                    if (hi <= lo) emptyList()
                    else listOfNotNull(sampleExplicit(raw, left, lo, hi, steps, mergedXDomain, clip, window, index))
                isSoloVar(left, "x") && rUnk.isEmpty() ->
                    if (clip != null) {
                        listOfNotNull(sampleExplicitXOfY(raw, right, clip, window, steps, index))
                    } else {
                        listOfNotNull(constantValue(right)?.let { vertical(raw, it, window, index) })
                    }
                isSoloVar(right, "x") && lUnk.isEmpty() ->
                    if (clip != null) {
                        listOfNotNull(sampleExplicitXOfY(raw, left, clip, window, steps, index))
                    } else {
                        listOfNotNull(constantValue(left)?.let { vertical(raw, it, window, index) })
                    }
                isSoloVar(left, "x") && "x" !in rUnk && "y" in rUnk ->
                    listOfNotNull(sampleExplicitXOfY(raw, right, clip, window, steps, index))
                isSoloVar(right, "x") && "x" !in lUnk && "y" in lUnk ->
                    listOfNotNull(sampleExplicitXOfY(raw, left, clip, window, steps, index))
                else ->
                    if (hi <= lo) emptyList()
                    else listOfNotNull(sampleImplicit(raw, "($left)-($right)", lo, hi, steps, mergedXDomain, clip, window, index))
            }
        }
        if (hi <= lo) return emptyList()
        val unknowns = unknownsOf(expr)
        return when {
            unknowns.isEmpty() -> listOfNotNull(sampleExplicit(raw, expr, lo, hi, steps, mergedXDomain, clip, window, index))
            "y" in unknowns -> listOfNotNull(sampleImplicit(raw, expr, lo, hi, steps, mergedXDomain, clip, window, index))
            else -> listOfNotNull(sampleExplicit(raw, expr, lo, hi, steps, mergedXDomain, clip, window, index))
        }
    }

    // ── Explicit y = f(x) ────────────────────────────────────────────────────

    private class Explicit(
        private val compiled: CompiledExpression,
        private val domain: GraphDomain?,
        private val clip: SystemClip?,
        private val yMin: Double,
        private val yMax: Double,
    ) {
        private val vars = HashMap<String, Double>(4)
        val ySpan = yMax - yMin
        val tol = ySpan * 0.0008
        val bigJump = ySpan * 0.1
        val out = ArrayList<GraphPoint>(1024)
        val edges = ArrayList<Edge>()

        fun rawF(x: Double): Double {
            vars["x"] = x
            vars["X"] = x
            return compiled.evaluateOrNaN(vars)
        }

        fun f(x: Double): Double {
            if (domain != null && !domain.contains(x)) return Double.NaN
            val y = rawF(x)
            if (!y.isFinite()) return Double.NaN
            if (clip != null && !clip.holdsAll(x, y)) return Double.NaN
            return y
        }

        /** Appends the curve over (x0, x1]; the point at x0 is already in [out]. */
        fun refine(x0: Double, y0: Double, x1: Double, y1: Double, depth: Int) {
            val f0 = y0.isFinite()
            val f1 = y1.isFinite()
            if (!f0 && !f1) {
                if (depth > 0) {
                    val xm = (x0 + x1) / 2
                    val ym = f(xm)
                    if (ym.isFinite()) {
                        refine(x0, y0, xm, ym, depth - 1)
                        refine(xm, ym, x1, y1, depth - 1)
                        return
                    }
                }
                out += GraphPoint(x1, Double.NaN)
                return
            }
            if (f0 != f1) {
                if (depth > 0) {
                    val xm = (x0 + x1) / 2
                    val ym = f(xm)
                    refine(x0, y0, xm, ym, depth - 1)
                    refine(xm, ym, x1, y1, depth - 1)
                    return
                }
                closeToEdge(x0, y0, x1, y1)
                return
            }
            val dy = abs(y1 - y0)
            if (depth == 0) {
                if (dy > bigJump && isJump(x0, y0, x1, y1)) out += GraphPoint((x0 + x1) / 2, Double.NaN)
                out += GraphPoint(x1, y1)
                return
            }
            val xm = (x0 + x1) / 2
            val ym = f(xm)
            if (ym.isFinite()) {
                val flat = abs(ym - (y0 + y1) / 2) <= tol && dy <= bigJump
                val farAbove = y0 > yMax + ySpan && y1 > yMax + ySpan && ym > yMax + ySpan
                val farBelow = y0 < yMin - ySpan && y1 < yMin - ySpan && ym < yMin - ySpan
                if (flat || farAbove || farBelow) {
                    out += GraphPoint(x1, y1)
                    return
                }
            }
            refine(x0, y0, xm, ym, depth - 1)
            refine(xm, ym, x1, y1, depth - 1)
        }

        private fun closeToEdge(x0: Double, y0: Double, x1: Double, y1: Double) {
            var a = x0
            var b = x1
            var good = if (y0.isFinite()) x0 else x1
            var goodY = if (y0.isFinite()) y0 else y1
            val leftDefined = y0.isFinite()
            repeat(48) {
                val m = (a + b) / 2
                if (m <= a || m >= b) return@repeat
                val ym = f(m)
                if (ym.isFinite()) {
                    good = m
                    goodY = ym
                    if (leftDefined) a = m else b = m
                } else {
                    if (leftDefined) b = m else a = m
                }
            }
            if (leftDefined) {
                out += GraphPoint(good, goodY)
                out += GraphPoint(x1, Double.NaN)
                edges += Edge(good, goodY, undefinedToRight = true)
            } else {
                out += GraphPoint(good, Double.NaN)
                out += GraphPoint(good, goodY)
                if (x1 != good) out += GraphPoint(x1, y1)
                edges += Edge(good, goodY, undefinedToRight = false)
            }
        }

        private fun isJump(x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
            var a = x0
            var fa = y0
            var b = x1
            var fb = y1
            val initial = abs(fb - fa)
            repeat(30) {
                val m = (a + b) / 2
                if (m <= a || m >= b) return@repeat
                val fm = f(m)
                if (!fm.isFinite()) return true
                if (abs(fm - fa) >= abs(fb - fm)) {
                    b = m
                    fb = fm
                } else {
                    a = m
                    fa = fm
                }
            }
            val remaining = abs(fb - fa)
            return remaining > initial * 0.25 && remaining > tol
        }

        fun edgeMarker(edge: Edge, xSpan: Double): GraphMarker? {
            val dir = if (edge.undefinedToRight) -1.0 else 1.0
            val candidates = buildList {
                domain?.xMin?.let { add(it) }
                domain?.xMax?.let { add(it) }
                add(round(edge.x * 1e6) / 1e6)
                add(round(edge.x * 1e3) / 1e3)
            }.filter { abs(it - edge.x) <= xSpan * 1e-6 + 1e-9 }

            val snapX = candidates.firstOrNull() ?: edge.x
            // Check system inequality strictness when the edge comes from a system constraint.
            if (clip != null) {
                val rawAtSnap = rawF(snapX)
                val limitY = if (rawAtSnap.isFinite() && abs(rawAtSnap - edge.y) <= max(tol * 2.0, abs(edge.y) * 1e-3 + 1e-3)) {
                    rawAtSnap
                } else {
                    edge.y
                }
                val clipStyle = clip.markerStyleAt(snapX, limitY, tol)
                if (clipStyle != null) {
                    val domainAllows = domain == null || domain.contains(snapX)
                    val style = if (clipStyle == GraphMarkerStyle.Filled && domainAllows && rawAtSnap.isFinite()) {
                        GraphMarkerStyle.Filled
                    } else {
                        GraphMarkerStyle.Hollow
                    }
                    return GraphMarker(snapX, limitY, style)
                }
            }

            for (c in candidates) {
                val fc = f(c)
                if (fc.isFinite() && abs(fc - edge.y) <= max(tol, abs(edge.y) * 1e-3)) {
                    return GraphMarker(c, fc, GraphMarkerStyle.Filled)
                }
            }
            val near = f(edge.x + dir * xSpan * 1e-8)
            val far = f(edge.x + dir * xSpan * 1e-5)
            if (!near.isFinite() || !far.isFinite()) return null
            val limitExists = abs(near - far) <= max(tol, 1e-3 * max(1.0, abs(near)))
            if (!limitExists) return null
            return GraphMarker(snapX, edge.y, GraphMarkerStyle.Hollow)
        }
    }

    private class Edge(val x: Double, val y: Double, val undefinedToRight: Boolean)

    private fun sampleExplicit(
        label: String,
        body: String,
        xMin: Double,
        xMax: Double,
        steps: Int,
        domain: GraphDomain?,
        clip: SystemClip?,
        window: Window,
        index: Int,
    ): GraphSeries? {
        val compiled = CompiledExpression.compileOrNull(body) ?: return null
        val s = Explicit(compiled, domain, clip, window.yMin, window.yMax)
        val dx = (xMax - xMin) / steps
        var x0 = xMin
        var y0 = s.f(x0)
        s.out += GraphPoint(x0, y0)
        for (i in 1..steps) {
            val x1 = if (i == steps) xMax else xMin + dx * i
            val y1 = s.f(x1)
            s.refine(x0, y0, x1, y1, MAX_DEPTH)
            x0 = x1
            y0 = y1
        }
        val markers = ArrayList<GraphMarker>()
        fun add(marker: GraphMarker?) {
            if (marker == null) return
            if (markers.none { abs(it.x - marker.x) <= window.xSpan * 1e-5 && abs(it.y - marker.y) <= window.ySpan * 1e-4 + 1e-4 }) {
                markers += marker
            }
        }
        if (domain != null) {
            domain.xMin?.takeIf { it >= window.xMin && it <= window.xMax }?.let {
                add(domainBound(s, it, domain.minInclusive, fromRight = true, window.xSpan, clip))
            }
            domain.xMax?.takeIf { it >= window.xMin && it <= window.xMax }?.let {
                add(domainBound(s, it, domain.maxInclusive, fromRight = false, window.xSpan, clip))
            }
        }
        s.edges.forEach { add(s.edgeMarker(it, window.xSpan)) }
        if (s.out.none { it.finite } && markers.isEmpty()) return null
        return GraphSeries(label, s.out, markers, index)
    }

    private fun domainBound(
        s: Explicit,
        x: Double,
        inclusive: Boolean,
        fromRight: Boolean,
        xSpan: Double,
        clip: SystemClip?,
    ): GraphMarker? {
        val inside = x + (if (fromRight) 1 else -1) * xSpan * 1e-7
        val yInside = s.f(inside)
        val yExact = s.rawF(x).takeIf { it.isFinite() }
        val y = when {
            yExact != null && yInside.isFinite() && abs(yExact - yInside) <= max(s.tol * 4.0, abs(yInside) * 1e-2 + 1e-2) -> yExact
            yInside.isFinite() -> yInside
            inclusive -> s.f(x).takeIf { it.isFinite() }
            else -> null
        } ?: return null
        if (clip != null) {
            val clipStyle = clip.markerStyleAt(x, y, s.tol) ?: return null
            val style = if (inclusive && clipStyle == GraphMarkerStyle.Filled && s.f(x).isFinite()) {
                GraphMarkerStyle.Filled
            } else {
                GraphMarkerStyle.Hollow
            }
            return GraphMarker(x, y, style)
        }
        val style = if (inclusive && s.f(x).isFinite()) GraphMarkerStyle.Filled else GraphMarkerStyle.Hollow
        return GraphMarker(x, y, style)
    }

    // ── Explicit x = g(y) (e.g. `x = y^2`, clipped vertical `x = 3`) ─────────

    private fun sampleExplicitXOfY(
        label: String,
        rhsOfY: String,
        clip: SystemClip?,
        window: Window,
        steps: Int,
        index: Int,
    ): GraphSeries? {
        val compiled = CompiledExpression.compileOrNull(rhsOfY) ?: return null
        val yDom = clip?.yDomain
        if (yDom?.isEmpty() == true) return null
        val (yLo, yHi) = (yDom ?: GraphDomain()).clip(window.yMin, window.yMax)
        if (yHi <= yLo) return null

        val vars = HashMap<String, Double>(4)
        val tol = window.xSpan * 0.0008
        fun rawG(y: Double): Double {
            vars["y"] = y
            vars["Y"] = y
            return compiled.evaluateOrNaN(vars)
        }
        fun g(y: Double): Double {
            if (yDom != null && !yDom.contains(y)) return Double.NaN
            val x = rawG(y)
            if (!x.isFinite()) return Double.NaN
            if (clip != null && !clip.holdsAll(x, y)) return Double.NaN
            return x
        }

        val out = ArrayList<GraphPoint>(steps + 32)
        val edges = ArrayList<Pair<Double, Double>>() // (x, y)
        val dy = (yHi - yLo) / steps
        var y0 = yLo
        var x0 = g(y0)
        out += GraphPoint(x0, y0)
        for (i in 1..steps) {
            val y1 = if (i == steps) yHi else yLo + dy * i
            val x1 = g(y1)
            val f0 = x0.isFinite()
            val f1 = x1.isFinite()
            when {
                f0 && f1 -> out += GraphPoint(x1, y1)
                !f0 && !f1 -> out += GraphPoint(Double.NaN, y1)
                else -> {
                    var a = y0
                    var b = y1
                    var goodY = if (f0) y0 else y1
                    var goodX = if (f0) x0 else x1
                    repeat(42) {
                        val m = (a + b) * 0.5
                        if (m <= a || m >= b) return@repeat
                        val xm = g(m)
                        if (xm.isFinite()) {
                            goodY = m
                            goodX = xm
                            if (f0) a = m else b = m
                        } else {
                            if (f0) b = m else a = m
                        }
                    }
                    if (f0) {
                        out += GraphPoint(goodX, goodY)
                        out += GraphPoint(Double.NaN, y1)
                    } else {
                        out += GraphPoint(Double.NaN, goodY)
                        out += GraphPoint(goodX, goodY)
                        if (y1 != goodY) out += GraphPoint(x1, y1)
                    }
                    edges += goodX to goodY
                }
            }
            y0 = y1
            x0 = x1
        }

        val markers = ArrayList<GraphMarker>()
        fun addMarker(m: GraphMarker?) {
            if (m == null) return
            if (markers.none { abs(it.x - m.x) <= window.xSpan * 1e-5 + 1e-4 && abs(it.y - m.y) <= window.ySpan * 1e-5 + 1e-4 }) {
                markers += m
            }
        }
        if (yDom != null && clip != null) {
            yDom.xMin?.takeIf { it >= window.yMin && it <= window.yMax }?.let { yBound ->
                val inside = yBound + window.ySpan * 1e-7
                val xIn = g(inside)
                val xAt = rawG(yBound)
                val x = if (xAt.isFinite() && xIn.isFinite()) xAt else xIn.takeIf { it.isFinite() } ?: xAt.takeIf { it.isFinite() && yDom.minInclusive }
                if (x != null) {
                    clip.markerStyleAt(x, yBound, tol)?.let { style ->
                        val finalStyle = if (yDom.minInclusive && style == GraphMarkerStyle.Filled) GraphMarkerStyle.Filled else GraphMarkerStyle.Hollow
                        addMarker(GraphMarker(x, yBound, finalStyle))
                    }
                }
            }
            yDom.xMax?.takeIf { it >= window.yMin && it <= window.yMax }?.let { yBound ->
                val inside = yBound - window.ySpan * 1e-7
                val xIn = g(inside)
                val xAt = rawG(yBound)
                val x = if (xAt.isFinite() && xIn.isFinite()) xAt else xIn.takeIf { it.isFinite() } ?: xAt.takeIf { it.isFinite() && yDom.maxInclusive }
                if (x != null) {
                    clip.markerStyleAt(x, yBound, tol)?.let { style ->
                        val finalStyle = if (yDom.maxInclusive && style == GraphMarkerStyle.Filled) GraphMarkerStyle.Filled else GraphMarkerStyle.Hollow
                        addMarker(GraphMarker(x, yBound, finalStyle))
                    }
                }
            }
        }
        for ((ex, ey) in edges) {
            val snapY = round(ey * 1e5) / 1e5
            val snapX = rawG(snapY).takeIf { it.isFinite() } ?: ex
            clip?.markerStyleAt(snapX, snapY, tol)?.let { style ->
                addMarker(GraphMarker(snapX, snapY, style))
            }
        }
        if (out.none { it.finite } && markers.isEmpty()) return null
        return GraphSeries(label, out, markers, index)
    }

    // ── Implicit F(x, y) = 0 via marching squares ───────────────────────────

    private fun sampleImplicit(
        label: String,
        body: String,
        xMin: Double,
        xMax: Double,
        steps: Int,
        domain: GraphDomain?,
        clip: SystemClip?,
        window: Window,
        index: Int,
    ): GraphSeries? {
        val compiled = CompiledExpression.compileOrNull(body) ?: return null
        val nx = (steps / 2).coerceIn(48, 240)
        val ny = nx
        val (yMin, yMax) = (clip?.yDomain ?: GraphDomain()).clip(window.yMin, window.yMax)
        if (yMax <= yMin) return null
        val hx = (xMax - xMin) / nx
        val hy = (yMax - yMin) / ny
        val vars = HashMap<String, Double>(4)
        fun evalRaw(x: Double, y: Double): Double {
            vars["x"] = x
            vars["X"] = x
            vars["y"] = y
            vars["Y"] = y
            return compiled.evaluateOrNaN(vars)
        }
        fun f(x: Double, y: Double): Double {
            if (domain != null && !domain.contains(x)) return Double.NaN
            if (clip != null && !clip.holdsAll(x, y)) return Double.NaN
            return evalRaw(x, y)
        }
        val cols = nx + 1
        val grid = DoubleArray(cols * (ny + 1))
        for (j in 0..ny) {
            val y = yMin + hy * j
            for (i in 0..nx) grid[j * cols + i] = f(xMin + hx * i, y)
        }
        fun v(i: Int, j: Int) = grid[j * cols + i]

        val crossings = HashMap<Int, GraphPoint?>()
        fun crossing(id: Int): GraphPoint? = crossings.getOrPut(id) {
            val k = id / 2
            val i = k % cols
            val j = k / cols
            val vertical = id % 2 == 1
            val va = v(i, j)
            val vb = if (vertical) v(i, j + 1) else v(i + 1, j)
            val t = (va / (va - vb)).coerceIn(0.0, 1.0)
            val x = xMin + hx * (i + if (vertical) 0.0 else t)
            val y = yMin + hy * (j + if (vertical) t else 0.0)
            val fp = evalRaw(x, y)
            if (!fp.isFinite() || abs(fp) > max(abs(va), abs(vb))) null else GraphPoint(x, y)
        }
        fun h(i: Int, j: Int) = 2 * (j * cols + i)
        fun vtc(i: Int, j: Int) = 2 * (j * cols + i) + 1

        val segments = ArrayList<IntArray>()
        for (j in 0 until ny) {
            for (i in 0 until nx) {
                val a = v(i, j)
                val b = v(i + 1, j)
                val c = v(i + 1, j + 1)
                val d = v(i, j + 1)
                if (!a.isFinite() || !b.isFinite() || !c.isFinite() || !d.isFinite()) continue
                val code = (if (a > 0) 1 else 0) or (if (b > 0) 2 else 0) or
                    (if (c > 0) 4 else 0) or (if (d > 0) 8 else 0)
                if (code == 0 || code == 15) continue
                val bottom = h(i, j)
                val right = vtc(i + 1, j)
                val top = h(i, j + 1)
                val left = vtc(i, j)
                when (code) {
                    1, 14 -> segments += intArrayOf(left, bottom)
                    2, 13 -> segments += intArrayOf(bottom, right)
                    3, 12 -> segments += intArrayOf(left, right)
                    4, 11 -> segments += intArrayOf(right, top)
                    6, 9 -> segments += intArrayOf(bottom, top)
                    7, 8 -> segments += intArrayOf(left, top)
                    5, 10 -> {
                        val centre = f(xMin + hx * (i + 0.5), yMin + hy * (j + 0.5))
                        val joinAc = (centre > 0) == (code == 5)
                        if (joinAc) {
                            segments += intArrayOf(left, top)
                            segments += intArrayOf(bottom, right)
                        } else {
                            segments += intArrayOf(left, bottom)
                            segments += intArrayOf(right, top)
                        }
                    }
                }
            }
        }
        val valid = segments.filter { crossing(it[0]) != null && crossing(it[1]) != null }
        if (valid.isEmpty()) return null
        val points = chainSegments(valid) { crossing(it)!! }

        // Detect exact boundary cut markers on axis-aligned system bounds (e.g. `x^2 + y^2 = 9; x > 0`).
        val markers = ArrayList<GraphMarker>()
        if (clip != null) {
            val tol = max(window.xSpan, window.ySpan) * 1e-3
            fun scanCutX(xCut: Double) {
                if (xCut < window.xMin || xCut > window.xMax) return
                val samples = 240
                val step = (yMax - yMin) / samples
                var prevY = yMin
                var prevV = evalRaw(xCut, prevY)
                for (k in 1..samples) {
                    val curY = yMin + step * k
                    val curV = evalRaw(xCut, curY)
                    if (prevV.isFinite() && curV.isFinite() && prevV * curV <= 0.0) {
                        var loY = prevY
                        var hiY = curY
                        var fLo = prevV
                        repeat(32) {
                            val mid = (loY + hiY) * 0.5
                            val fMid = evalRaw(xCut, mid)
                            if (fLo * fMid <= 0.0) hiY = mid else { loY = mid; fLo = fMid }
                        }
                        val yRoot = (loY + hiY) * 0.5
                        clip.markerStyleAt(xCut, yRoot, tol)?.let { style ->
                            if (markers.none { abs(it.x - xCut) < tol && abs(it.y - yRoot) < tol }) {
                                markers += GraphMarker(xCut, yRoot, style)
                            }
                        }
                    }
                    prevY = curY
                    prevV = curV
                }
            }
            fun scanCutY(yCut: Double) {
                if (yCut < window.yMin || yCut > window.yMax) return
                val samples = 240
                val step = (xMax - xMin) / samples
                var prevX = xMin
                var prevV = evalRaw(prevX, yCut)
                for (k in 1..samples) {
                    val curX = xMin + step * k
                    val curV = evalRaw(curX, yCut)
                    if (prevV.isFinite() && curV.isFinite() && prevV * curV <= 0.0) {
                        var loX = prevX
                        var hiX = curX
                        var fLo = prevV
                        repeat(32) {
                            val mid = (loX + hiX) * 0.5
                            val fMid = evalRaw(mid, yCut)
                            if (fLo * fMid <= 0.0) hiX = mid else { loX = mid; fLo = fMid }
                        }
                        val xRoot = (loX + hiX) * 0.5
                        clip.markerStyleAt(xRoot, yCut, tol)?.let { style ->
                            if (markers.none { abs(it.x - xRoot) < tol && abs(it.y - yCut) < tol }) {
                                markers += GraphMarker(xRoot, yCut, style)
                            }
                        }
                    }
                    prevX = curX
                    prevV = curV
                }
            }
            domain?.xMin?.let(::scanCutX)
            domain?.xMax?.let(::scanCutX)
            clip.yDomain?.xMin?.let(::scanCutY)
            clip.yDomain?.xMax?.let(::scanCutY)
        }
        return GraphSeries(label, points, markers, index)
    }

    /** Joins edge-to-edge segments into polylines separated by NaN points. */
    private fun chainSegments(segments: List<IntArray>, point: (Int) -> GraphPoint): List<GraphPoint> {
        val byEdge = HashMap<Int, MutableList<Int>>()
        segments.forEachIndexed { s, seg ->
            byEdge.getOrPut(seg[0]) { ArrayList(2) } += s
            byEdge.getOrPut(seg[1]) { ArrayList(2) } += s
        }
        val used = BooleanArray(segments.size)
        val out = ArrayList<GraphPoint>(segments.size * 2)
        fun walk(fromSeg: Int, fromEdge: Int, into: ArrayList<Int>) {
            var seg = fromSeg
            var edge = fromEdge
            while (true) {
                val next = byEdge[edge]?.firstOrNull { it != seg && !used[it] } ?: return
                used[next] = true
                val s = segments[next]
                edge = if (s[0] == edge) s[1] else s[0]
                into += edge
                seg = next
            }
        }
        for (start in segments.indices) {
            if (used[start]) continue
            used[start] = true
            val forward = arrayListOf(segments[start][1])
            walk(start, segments[start][1], forward)
            val backward = ArrayList<Int>()
            walk(start, segments[start][0], backward)
            if (out.isNotEmpty()) out += GraphPoint(Double.NaN, Double.NaN)
            for (k in backward.indices.reversed()) out += point(backward[k])
            out += point(segments[start][0])
            forward.forEach { out += point(it) }
        }
        return out
    }

    // ── Lines ───────────────────────────────────────────────────────────────

    private fun horizontal(label: String, y: Double, xMin: Double, xMax: Double, index: Int) =
        GraphSeries(label, listOf(GraphPoint(xMin, y), GraphPoint(xMax, y)), emptyList(), index)

    private fun vertical(label: String, x: Double, window: Window, index: Int): GraphSeries {
        val pad = max(window.ySpan, 1.0)
        return GraphSeries(
            label,
            listOf(GraphPoint(x, min(window.yMin, window.yMax) - pad), GraphPoint(x, window.yMax + pad)),
            emptyList(),
            index,
        )
    }

    private fun splitEq(s: String): Pair<String, String>? {
        val i = s.indexOf('=')
        if (i <= 0 || i >= s.lastIndex) return null
        val left = s.substring(0, i).trim()
        val right = s.substring(i + 1).trim()
        if (left.isEmpty() || right.isEmpty()) return null
        return left to right
    }

    private fun unknownsOf(expr: String): Set<String> =
        MathEngine.collectUnknowns(expr).map { it.lowercase() }.toSet()

    private fun isSoloVar(expr: String, name: String): Boolean =
        expr.trim().equals(name, ignoreCase = true)
}
