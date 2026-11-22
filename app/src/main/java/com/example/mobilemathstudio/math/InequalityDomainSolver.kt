package com.example.mobilemathstudio.math

import kotlin.math.abs

/**
 * Real-line inequalities, chained inequalities, automatic ODZ (domain) for school
 * expressions (poles, even roots, logarithms), and interval/identity equation sets
 * (`x ∈ (-∞, +∞)`, `x ∈ (-∞, 0) ∪ (0, +∞)`, `x ∈ [0, +∞)`).
 *
 * Returns null when the input is an equation or has nothing this solver should claim.
 */
object InequalityDomainSolver {
    private val OPS = listOf(">=", "<=", "≥", "≤", ">", "<")

    fun solve(raw: String): SolveOutcome? {
        val source = raw.trim()
        if (source.isBlank() || CasSolver.looksLikeOde(source)) return null
        val compact = CasSolver.compactExpr(source)
        val relations = splitRelations(compact)
        val unknowns = MathEngine.collectUnknowns(compact).toList()
        if (relations.isEmpty()) {
            if ('=' in compact) return null
            if (unknowns.size != 1) return null
            if (!hasDomainRestrictions(compact, unknowns.single())) return null
            return domainOutcome(unknowns.single(), compact)
        }
        if (unknowns.isEmpty()) {
            return constantRelations(relations)
        }
        if (unknowns.size != 1) return null
        return inequalitiesOutcome(unknowns.single(), relations, preferIntervalNotation = false)
    }

    /**
     * Solves a system of one-variable inequalities (e.g. `{ x > 1, x ≤ 5 }`) by taking
     * the intersection of their solution sets. Returns null if any line is not an
     * inequality or if the system involves multiple variables.
     */
    fun solveSystemInequalities(lines: List<String>): SolveOutcome? {
        if (lines.isEmpty()) return null
        val allRelations = mutableListOf<Relation>()
        val allUnknowns = linkedSetOf<String>()
        for (raw in lines) {
            val compact = CasSolver.compactExpr(raw.trim())
            val rels = splitRelations(compact)
            if (rels.isEmpty()) return null
            allRelations += rels
            allUnknowns += MathEngine.collectUnknowns(compact)
        }
        if (allUnknowns.size != 1) return null
        return inequalitiesOutcome(allUnknowns.single(), allRelations, preferIntervalNotation = true)
    }

    /**
     * Computes the exact real domain (ОДЗ) of an identity equation such as `x/x = 1`,
     * `sin^2(x) + cos^2(x) = 1`, or `√(x) = √(x)` in standard interval set notation:
     * `x ∈ (-∞, +∞)`, `x ∈ (-∞, 0) ∪ (0, +∞)`, `x ∈ [0, +∞)`.
     */
    fun domainForIdentity(variable: String, expr: String): SolveOutcome {
        val compact = CasSolver.compactExpr(expr)
        val cuts = criticalPoints(compact, variable)
        val intervals = intervalsFromCuts(cuts) { x -> inDomain(compact, variable, x) }
        if (intervals.isEmpty()) return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        val text = formatIntervals(variable, intervals, preferIntervalNotation = true)
        return SolveOutcome.Solutions(exactLines = listOf(text), decimalLines = listOf(text), latex = text)
    }

    /**
     * Detects equations that hold identically on all of `ℝ`, on their ОДЗ (with holes
     * or ray bounds), or on one or more continuous intervals (e.g. `|x| = x`,
     * `|x| + |x - 2| = 2`, `√(x^2) = x`, `x / x = 1`, `sin^2(x) + cos^2(x) = 1`).
     *
     * Returns null for discrete equations (`x^2 = 4`, `sin(x) = 0`, etc.) so the
     * regular algebraic/trig solvers handle them.
     */
    fun solveEquationIntervalOrIdentity(
        left: String,
        right: String,
        variable: String,
    ): SolveOutcome? {
        val combined = "($left)+($right)"
        val cuts = unique(
            criticalPoints(left, variable) +
                criticalPoints(right, variable) +
                absCriticalPoints(left, variable) +
                absCriticalPoints(right, variable),
        )
        val bounds = buildList<Double?> {
            add(null)
            addAll(cuts)
            add(null)
        }
        val openHits = MutableList(bounds.lastIndex) { false }
        for (i in 0 until bounds.lastIndex) {
            val samples = interiorSamples(bounds[i], bounds[i + 1])
            if (samples.size < 3) continue
            val allHold = samples.all { x ->
                inDomain(combined, variable, x) &&
                    CasSolver.satisfiesEquation(left, right, variable, x)
            }
            openHits[i] = allHold
        }
        if (openHits.none { it }) return null

        val endpointOk = cuts.map { x ->
            inDomain(combined, variable, x) && CasSolver.satisfiesEquation(left, right, variable, x)
        }
        val intervals = buildIntervals(bounds, cuts, openHits, endpointOk)
        if (intervals.isEmpty()) return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        val text = formatIntervals(variable, intervals, preferIntervalNotation = true)
        return SolveOutcome.Solutions(exactLines = listOf(text), decimalLines = listOf(text), latex = text)
    }

    internal fun splitRelation(compact: String): Relation? = splitRelations(compact).singleOrNull()

    /**
     * Splits a single or chained top-level inequality (`a < x <= b`) into its list of
     * adjacent `Relation`s (`a < x`, `x <= b`). Returns empty if no top-level inequality
     * operator exists or if an `=` (not part of `<=`/`>=`) is present at top level.
     */
    internal fun splitRelations(compact: String): List<Relation> {
        val opHits = mutableListOf<Pair<Int, String>>()
        var depth = 0
        var bars = 0
        var i = 0
        while (i < compact.length) {
            when (compact[i]) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                '|' -> bars = 1 - bars
                else -> if (depth == 0 && bars == 0) {
                    val op = OPS.firstOrNull { compact.startsWith(it, i) }
                    if (op != null) {
                        opHits += i to op
                        i += op.length
                        continue
                    } else if (compact[i] == '=') {
                        return emptyList()
                    }
                }
            }
            i++
        }
        if (opHits.isEmpty()) return emptyList()
        val segments = mutableListOf<String>()
        var cursor = 0
        for ((pos, op) in opHits) {
            val part = compact.substring(cursor, pos)
            if (part.isEmpty()) return emptyList()
            segments += part
            cursor = pos + op.length
        }
        val tail = compact.substring(cursor)
        if (tail.isEmpty()) return emptyList()
        segments += tail
        return opHits.indices.map { idx ->
            Relation(segments[idx], opHits[idx].second, segments[idx + 1])
        }
    }

    private fun constantRelations(relations: List<Relation>): SolveOutcome {
        for (rel in relations) {
            val l = eval(rel.left, emptyMap())
            val r = eval(rel.right, emptyMap())
            if (l == null || r == null) {
                return SolveOutcome.Error(SolveMessages.DIV_ZERO, 0, 0)
            }
            if (!holds(l, r, rel.op)) {
                return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
            }
        }
        return SolveOutcome.Value(exact = "True", decimal = "true")
    }

    private fun inequalitiesOutcome(
        variable: String,
        relations: List<Relation>,
        preferIntervalNotation: Boolean,
    ): SolveOutcome {
        val cuts = mutableListOf<Double>()
        for (rel in relations) {
            val diff = "(${rel.left})-(${rel.right})"
            cuts += CasSolver.numericRoots(diff, variable)
            cuts += criticalPoints(rel.left, variable)
            cuts += criticalPoints(rel.right, variable)
            cuts += absCriticalPoints(rel.left, variable)
            cuts += absCriticalPoints(rel.right, variable)
        }
        val intervals = intervalsFromCuts(cuts) { x ->
            relations.all { rel ->
                val l = eval(rel.left, mapOf(variable to x)) ?: return@all false
                val r = eval(rel.right, mapOf(variable to x)) ?: return@all false
                inDomain(rel.left, variable, x) &&
                    inDomain(rel.right, variable, x) &&
                    holds(l, r, rel.op)
            }
        }
        if (intervals.isEmpty()) return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        val text = formatIntervals(variable, intervals, preferIntervalNotation)
        return SolveOutcome.Solutions(exactLines = listOf(text), decimalLines = listOf(text), latex = text)
    }

    private fun domainOutcome(variable: String, expr: String): SolveOutcome {
        val cuts = criticalPoints(expr, variable)
        val intervals = intervalsFromCuts(cuts) { x -> inDomain(expr, variable, x) }
        if (intervals.isEmpty()) return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        val text = formatIntervals(variable, intervals, preferIntervalNotation = false)
        return SolveOutcome.Solutions(exactLines = listOf(text), decimalLines = listOf(text), latex = text)
    }

    private fun intervalsFromCuts(
        cuts: List<Double>,
        predicate: (Double) -> Boolean,
    ): List<Interval> {
        val points = unique(cuts.filter { it.isFinite() })
        val bounds = buildList {
            add(null)
            addAll(points)
            add(null)
        }
        val openHits = mutableListOf<Boolean>()
        for (i in 0 until bounds.lastIndex) {
            val lo = bounds[i]
            val hi = bounds[i + 1]
            openHits += sample(lo, hi)?.let(predicate) == true
        }
        val endpointOk = points.map { predicate(it) }
        return buildIntervals(bounds, points, openHits, endpointOk)
    }

    private fun buildIntervals(
        bounds: List<Double?>,
        points: List<Double>,
        openHits: List<Boolean>,
        endpointOk: List<Boolean>,
    ): List<Interval> {
        val out = mutableListOf<Interval>()
        var i = 0
        while (i < openHits.size) {
            if (!openHits[i]) {
                i++
                continue
            }
            var j = i
            while (j + 1 < openHits.size && openHits[j + 1] && endpointOk.getOrElse(j) { false }) {
                j++
            }
            val loVal = bounds[i]
            val hiVal = bounds[j + 1]
            val loInc = loVal != null && endpointOk.getOrElse(i - 1) { false }
            val hiInc = hiVal != null && endpointOk.getOrElse(j) { false }
            out += Interval(
                lo = loVal?.let { Bound(it, loInc) },
                hi = hiVal?.let { Bound(it, hiInc) },
            )
            i = j + 1
        }
        for (k in points.indices) {
            val isolated = endpointOk[k] &&
                !openHits.getOrElse(k) { false } &&
                !openHits.getOrElse(k + 1) { false }
            if (isolated) {
                val p = points[k]
                out += Interval(Bound(p, true), Bound(p, true))
            }
        }
        return merge(out.sortedBy { it.lo?.value ?: Double.NEGATIVE_INFINITY })
    }

    private fun sample(lo: Double?, hi: Double?): Double? {
        val candidates = when {
            lo == null && hi == null -> listOf(0.0, 1.0, -1.0)
            lo == null -> listOf(hi!! - 1.0, hi - 10.0, hi - 0.01)
            hi == null -> listOf(lo + 1.0, lo + 10.0, lo + 0.01)
            else -> listOf((lo + hi) / 2.0, lo + (hi - lo) * 0.25, lo + (hi - lo) * 0.75)
        }
        return candidates.firstOrNull { it.isFinite() && (lo == null || it > lo + 1e-12) && (hi == null || it < hi - 1e-12) }
            ?: candidates.firstOrNull { it.isFinite() }
    }

    private fun interiorSamples(lo: Double?, hi: Double?): List<Double> {
        val raw = when {
            lo == null && hi == null -> listOf(-3.17, 0.41, 2.73, 5.19)
            lo == null -> listOf(hi!! - 0.37, hi - 1.83, hi - 5.29)
            hi == null -> listOf(lo + 0.37, lo + 1.83, lo + 5.29)
            else -> listOf(
                lo + (hi - lo) * 0.23,
                lo + (hi - lo) * 0.51,
                lo + (hi - lo) * 0.79,
            )
        }
        return raw.filter { x ->
            x.isFinite() && (lo == null || x > lo + 1e-9) && (hi == null || x < hi - 1e-9)
        }
    }

    private fun criticalPoints(expr: String, variable: String): List<Double> {
        val out = mutableListOf<Double>()
        for (den in denominators(expr)) {
            out += CasSolver.numericRoots(den, variable)
        }
        for (arg in sqrtArgs(expr)) {
            out += CasSolver.numericRoots(arg, variable)
        }
        for (arg in logArgs(expr)) {
            out += CasSolver.numericRoots(arg, variable)
        }
        return unique(out.filter { it.isFinite() })
    }

    private fun absCriticalPoints(expr: String, variable: String): List<Double> {
        val out = mutableListOf<Double>()
        for (arg in absArgs(expr)) {
            out += CasSolver.numericRoots(arg, variable)
        }
        return unique(out.filter { it.isFinite() })
    }

    private fun absArgs(expr: String): List<String> {
        val out = mutableListOf<String>()
        out += functionArgs(expr, listOf("abs"))
        var i = 0
        while (i < expr.length) {
            if (expr[i] == '|') {
                var j = i + 1
                var depth = 0
                while (j < expr.length) {
                    when (expr[j]) {
                        '(' -> depth++
                        ')' -> depth--
                        '|' -> if (depth == 0) break
                    }
                    j++
                }
                if (j < expr.length && expr[j] == '|') {
                    val inner = expr.substring(i + 1, j)
                    if (inner.isNotBlank()) {
                        out += inner
                        out += absArgs(inner)
                    }
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return out
    }

    private fun inDomain(expr: String, variable: String, x: Double): Boolean {
        if (!x.isFinite()) return false
        val vars = mapOf(variable to x)
        for (den in denominators(expr)) {
            val v = eval(den, vars) ?: return false
            if (abs(v) < 1e-9) return false
        }
        for (arg in sqrtArgs(expr)) {
            val v = eval(arg, vars) ?: return false
            if (v < -1e-9) return false
        }
        for (arg in logArgs(expr)) {
            val v = eval(arg, vars) ?: return false
            if (v <= 1e-12) return false
        }
        return eval(expr, vars) != null
    }

    private fun hasDomainRestrictions(expr: String, variable: String): Boolean {
        if (sqrtArgs(expr).isNotEmpty() || logArgs(expr).isNotEmpty()) return true
        return denominators(expr).any { variable in MathEngine.collectUnknowns(it) }
    }

    private fun denominators(expr: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(s: String) {
            var i = 0
            while (i < s.length) {
                if (s[i] == '/') {
                    val term = readPrimary(s, i + 1)
                    if (term != null) {
                        out += term.first
                        walk(term.first)
                        i = term.second
                        continue
                    }
                }
                if (s[i] == '(') {
                    val close = matchingParen(s, i)
                    if (close != null) {
                        walk(s.substring(i + 1, close))
                        i = close + 1
                        continue
                    }
                }
                i++
            }
        }
        walk(expr)
        return out.filter { it.isNotBlank() && it != "1" }
    }

    private fun readPrimary(s: String, start: Int): Pair<String, Int>? {
        var i = start
        if (i >= s.length) return null
        if (s[i] == '(') {
            val close = matchingParen(s, i) ?: return null
            return s.substring(i, close + 1) to close + 1
        }
        if (s[i] == '√' || s[i].isLetter()) {
            val nameEnd = if (s[i] == '√') i + 1 else {
                var j = i + 1
                while (j < s.length && s[j].isLetterOrDigit()) j++
                j
            }
            if (s.getOrNull(nameEnd) == '(') {
                val close = matchingParen(s, nameEnd) ?: return null
                return s.substring(i, close + 1) to close + 1
            }
            val atom = readAtom(s, i) ?: return null
            return atom
        }
        if (s[i] == '-' || s[i].isDigit() || s[i] == '.') {
            val from = if (s[i] == '-') i + 1 else i
            val atom = readAtom(s, from) ?: return null
            return s.substring(i, atom.second) to atom.second
        }
        return null
    }

    private fun sqrtArgs(expr: String): List<String> = functionArgs(expr, listOf("sqrt", "√"))

    private fun logArgs(expr: String): List<String> = functionArgs(expr, listOf("ln", "log"))

    private fun functionArgs(expr: String, names: List<String>): List<String> {
        val out = mutableListOf<String>()
        fun walk(s: String) {
            var i = 0
            while (i < s.length) {
                val name = names.firstOrNull { n ->
                    s.startsWith(n, i, ignoreCase = true) &&
                        (n.length == 1 || i == 0 || !s[i - 1].isLetterOrDigit())
                }
                if (name != null) {
                    val after = i + name.length
                    if (s.getOrNull(after) == '(') {
                        val close = matchingParen(s, after) ?: break
                        val inner = s.substring(after + 1, close)
                        out += inner
                        walk(inner)
                        i = close + 1
                        continue
                    }
                    if (name == "√") {
                        val atom = readAtom(s, after)
                        if (atom != null) {
                            out += atom.first
                            i = atom.second
                            continue
                        }
                    }
                }
                if (s[i] == '(') {
                    val close = matchingParen(s, i)
                    if (close != null) {
                        walk(s.substring(i + 1, close))
                        i = close + 1
                        continue
                    }
                }
                i++
            }
        }
        walk(expr)
        return out
    }

    private fun matchingParen(s: String, openIndex: Int): Int? {
        if (s.getOrNull(openIndex) != '(') return null
        var depth = 0
        for (i in openIndex until s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun readAtom(s: String, start: Int): Pair<String, Int>? {
        if (start >= s.length) return null
        var i = start
        if (s[i].isLetter()) {
            i++
            while (i < s.length && s[i].isLetterOrDigit()) i++
            return s.substring(start, i) to i
        }
        if (s[i].isDigit() || s[i] == '.') {
            i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i) to i
        }
        return null
    }

    private fun holds(left: Double, right: Double, op: String): Boolean = when (op) {
        ">" -> left > right + 1e-12
        "<" -> left < right - 1e-12
        ">=", "≥" -> left >= right - 1e-9
        "<=", "≤" -> left <= right + 1e-9
        else -> false
    }

    private fun eval(expr: String, vars: Map<String, Double>): Double? = try {
        MathEngine.evaluate(expr, vars).takeIf { it.isFinite() }
    } catch (_: Throwable) {
        null
    }

    private fun formatIntervals(
        variable: String,
        intervals: List<Interval>,
        preferIntervalNotation: Boolean,
    ): String {
        if (intervals.size == 1) {
            val only = intervals.single()
            if (only.isAllReals()) return "$variable ∈ (-∞, +∞)"
            if (!preferIntervalNotation) {
                simpleInequality(variable, only)?.let { return it }
            }
        }
        return "$variable ∈ " + intervals.joinToString(" ∪ ") { it.toMath() }
    }

    private fun simpleInequality(variable: String, interval: Interval): String? {
        val lo = interval.lo
        val hi = interval.hi
        return when {
            lo != null && hi == null ->
                "$variable ${if (lo.inclusive) "≥" else ">"} ${fmt(lo.value)}"
            lo == null && hi != null ->
                "$variable ${if (hi.inclusive) "≤" else "<"} ${fmt(hi.value)}"
            lo != null && hi != null && abs(lo.value - hi.value) < 1e-9 && lo.inclusive && hi.inclusive ->
                "$variable = ${fmt(lo.value)}"
            else -> null
        }
    }

    private fun fmt(value: Double): String = CasSolver.formatExact(value)

    private fun unique(values: List<Double>): List<Double> {
        val out = mutableListOf<Double>()
        for (v in values.sorted()) {
            if (out.none { abs(it - v) < 1e-6 }) out += v
        }
        return out
    }

    private fun merge(intervals: List<Interval>): List<Interval> {
        if (intervals.size <= 1) return intervals
        val out = mutableListOf<Interval>()
        for (next in intervals) {
            val prev = out.lastOrNull()
            if (prev != null && touches(prev, next)) {
                out[out.lastIndex] = Interval(
                    lo = prev.lo,
                    hi = next.hi,
                )
            } else {
                out += next
            }
        }
        return out
    }

    private fun touches(a: Interval, b: Interval): Boolean {
        val aHi = a.hi ?: return true
        val bLo = b.lo ?: return true
        if (abs(aHi.value - bLo.value) > 1e-6) return false
        return aHi.inclusive || bLo.inclusive
    }

    data class Relation(val left: String, val op: String, val right: String)

    private data class Bound(val value: Double, val inclusive: Boolean)

    private data class Interval(val lo: Bound?, val hi: Bound?) {
        fun isAllReals(): Boolean = lo == null && hi == null

        fun toMath(): String {
            if (lo != null && hi != null && abs(lo.value - hi.value) < 1e-9 && lo.inclusive && hi.inclusive) {
                return "{${fmt(lo.value)}}"
            }
            val left = if (lo == null) "(-∞" else "${if (lo.inclusive) "[" else "("}${fmt(lo.value)}"
            val right = if (hi == null) "+∞)" else "${fmt(hi.value)}${if (hi.inclusive) "]" else ")"}"
            return "$left, $right"
        }
    }
}
