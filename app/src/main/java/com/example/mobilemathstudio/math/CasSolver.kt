package com.example.mobilemathstudio.math

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * School-level CAS for one-variable equations. Proves identities and
 * contradictions, clears denominators, enforces ODZ, and solves polynomial,
 * radical, absolute-value, exponential and logarithmic equations without
 * depending on Symja's Reduce/Solve output.
 *
 * Returns null only when the equation is well-formed but this solver cannot
 * classify it — the caller may then try Symja.
 */
object CasSolver {
    private val SAMPLE_X = listOf(
        -6.0, -4.0, -3.0, -2.0, -1.0, -0.5, 0.0, 0.3, 0.7,
        1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, PI, 10.0,
    )

    fun looksLikeOde(source: String): Boolean {
        val s = source.replace(" ", "")
        return s.contains("y'") || s.contains("y''") || s.contains("y'''") ||
            s.contains("dy/dx", ignoreCase = true) ||
            s.contains("d^2y/dx^2", ignoreCase = true) ||
            s.contains("d2y/dx2", ignoreCase = true) ||
            Regex("""d\^\d+y/dx\^\d+""", RegexOption.IGNORE_CASE).containsMatchIn(s) ||
            s.startsWith("DSolve", ignoreCase = true)
    }

    fun solve(raw: String): SolveOutcome? {
        val source = raw.trim()
        if (source.isBlank()) return SolveOutcome.Error("Empty expression", 0, 0)
        if (looksLikeOde(source)) return null
        val compact = compactExpr(source)
        val sides = splitTopLevel(compact, '=')
        if (sides.size < 2) return null
        if (sides.size > 2) {
            val eq = source.indexOf('=')
            return SolveOutcome.Error("Multiple '=' not supported", eq.coerceAtLeast(0), 1)
        }
        val left = sides[0]
        val right = sides[1]
        if (left.isEmpty() || right.isEmpty()) {
            val eq = source.indexOf('=')
            return SolveOutcome.Error("Incomplete equation", eq.coerceAtLeast(0), 1)
        }

        if (hasLiteralZeroDivision(left) || hasLiteralZeroDivision(right)) {
            return SolveOutcome.Error(SolveMessages.DIV_ZERO, 0, source.length)
        }

        val zero = "($left)-($right)"
        val unknowns = MathEngine.collectUnknowns("$left+$right").toList()
        if (unknowns.isEmpty()) {
            val l = safeEval(left, emptyMap())
            val r = safeEval(right, emptyMap())
            return when {
                l == null || r == null -> SolveOutcome.Error(SolveMessages.DIV_ZERO, 0, source.length)
                abs(l - r) < 1e-9 -> SolveOutcome.Value(exact = "True", decimal = "true")
                else -> SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
            }
        }
        if (unknowns.size > 1) return null
        val variable = unknowns.single()

        InequalityDomainSolver.solveEquationIntervalOrIdentity(left, right, variable)?.let { return it }
        identityOrContradiction(zero, variable, left, right)?.let { return it }
        TrigSymbolic.solve(source)?.let { return it }
        solveAbs(left, right, variable)?.let { return it }
        RadicalSymbolic.solve(source)?.let { return it }
        solveLog(left, right, variable)?.let { return it }
        solveExp(left, right, variable)?.let { return it }
        solveRational(left, right, variable)?.let { return it }
        solvePolynomial(zero, variable, left, right)?.let { return it }

        val newton = newtonRoots(zero, variable)
            .filter { satisfiesEquation(left, right, variable, it) }
        if (newton.isNotEmpty()) return solutionsOf(variable, newton)
        return null
    }

    /** Real roots of `zeroExpr = 0` with no ODZ filter — used by radical ODZ. */
    fun numericRoots(zeroExpr: String, variable: String): List<Double> {
        val poly = fitPolynomial(zeroExpr, variable)
        val fromPoly = poly?.let { solvePolyCoeffs(it) }?.filter { it.isFinite() }.orEmpty()
        if (fromPoly.isNotEmpty()) return unique(fromPoly)
        return unique(newtonRoots(zeroExpr, variable))
    }

    internal fun compactExpr(raw: String): String = MathEngine.normalizeForCheck(raw)
        .replace("sqrt", "√", ignoreCase = true)

    internal fun splitTopLevel(input: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var bars = 0
        var start = 0
        for (i in input.indices) {
            when (val c = input[i]) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                '|' -> bars = 1 - bars
                sep -> if (depth == 0 && bars == 0) {
                    out += input.substring(start, i)
                    start = i + 1
                }
            }
        }
        out += input.substring(start)
        return out
    }

    internal fun formatExact(value: Double): String {
        if (!value.isFinite()) return MathEngine.formatNumber(value)
        val asLong = kotlin.math.round(value)
        if (abs(value - asLong) < 1e-9 && abs(value) < 1e15) return asLong.toLong().toString()
        for (den in 1..64) {
            val num = kotlin.math.round(value * den)
            if (abs(value * den - num) < 1e-8) {
                val g = gcd(abs(num.toLong()), den.toLong()).coerceAtLeast(1L)
                val n = (num.toLong() / g)
                val d = den.toLong() / g
                return if (d == 1L) n.toString() else "$n/$d"
            }
        }
        return MathEngine.formatNumber(value)
    }

    private fun solutionsOf(variable: String, roots: List<Double>): SolveOutcome {
        val ordered = unique(roots)
        if (ordered.isEmpty()) return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        val exact = ordered.map { "$variable = ${formatExact(it)}" }
        val dec = ordered.map { "$variable = ${MathEngine.formatNumber(it)}" }
        return SolveOutcome.Solutions(exactLines = exact, decimalLines = dec)
    }

    private fun identityOrContradiction(
        zero: String,
        variable: String,
        left: String,
        right: String,
    ): SolveOutcome? {
        val residuals = SAMPLE_X.mapNotNull { x -> safeEval(zero, mapOf(variable to x)) }
        if (residuals.size < 8) return null
        if (residuals.all { abs(it) < 1e-7 }) {
            return InequalityDomainSolver.domainForIdentity(variable, "($left)+($right)")
        }
        val mean = residuals.average()
        if (abs(mean) > 1e-6 && residuals.all { abs(it - mean) < 1e-6 }) {
            // Constant non-zero residual: x = x + 1, 0 = 1, …
            return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        }
        return null
    }

    private fun solveAbs(left: String, right: String, variable: String): SolveOutcome? {
        val inner = absInner(left) ?: return null
        val rhs = right
        val c = safeEval(rhs, emptyMap())
        val branches = listOf("($inner)-($rhs)", "($inner)+($rhs)")
        val roots = branches.flatMap { numericRoots(it, variable) }
            .filter { x ->
                val b = safeEval(rhs, mapOf(variable to x)) ?: return@filter false
                if (b < -1e-9) return@filter false
                val a = safeEval("|$inner|", mapOf(variable to x))
                    ?: safeEval("abs($inner)", mapOf(variable to x))
                    ?: return@filter false
                abs(a - b) < 1e-6
            }
        if (c != null && c < -1e-9 && roots.isEmpty()) {
            return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        }
        return if (roots.isEmpty()) SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        else solutionsOf(variable, roots)
    }

    private fun absInner(expr: String): String? {
        val t = expr.trim()
        if (t.startsWith("|") && t.endsWith("|") && t.length >= 3 && t.indexOf('|', 1) == t.lastIndex) {
            return t.substring(1, t.lastIndex)
        }
        val abs = Regex("^abs\\((.+)\\)$", RegexOption.IGNORE_CASE).matchEntire(t)
        return abs?.groupValues?.get(1)
    }

    private fun solveLog(left: String, right: String, variable: String): SolveOutcome? {
        parseFnSum(left, "ln")?.let { args ->
            return solveLogSum(args, right, variable, natural = true, left = left)
        }
        parseFnSum(left, "log")?.let { args ->
            return solveLogSum(args, right, variable, natural = false, left = left)
        }
        return null
    }

    /** `log(a)+log(b)+…` with balanced parentheses — never a greedy `log(a)+log(b)`. */
    private fun parseFnSum(expr: String, fn: String): List<String>? {
        val prefix = "$fn("
        val parts = mutableListOf<String>()
        var rest = expr
        while (true) {
            if (!rest.startsWith(prefix, ignoreCase = true)) return null
            val open = prefix.length - 1
            val close = matchingParen(rest, open) ?: return null
            parts += rest.substring(open + 1, close)
            rest = rest.substring(close + 1)
            if (rest.isEmpty()) return parts.takeIf { it.isNotEmpty() }
            if (!rest.startsWith("+")) return null
            rest = rest.substring(1)
        }
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

    private fun solveLogSum(
        args: List<String>,
        right: String,
        variable: String,
        natural: Boolean,
        left: String,
    ): SolveOutcome? {
        val c = safeEval(right, emptyMap()) ?: return null
        val product = if (natural) exp(c) else 10.0.pow(c)
        val productExpr = args.joinToString("*") { "($it)" }
        val roots = numericRoots("($productExpr)-($product)", variable).filter { x ->
            args.all { arg ->
                val u = safeEval(arg, mapOf(variable to x)) ?: return@all false
                u > 1e-15
            } && satisfiesEquation(left, right, variable, x)
        }
        if (roots.isEmpty()) return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        if (natural && args.size == 1 && args.single() == variable && roots.size == 1) {
            val exact = formatExpExact(c)
            val dec = MathEngine.formatNumber(roots.single())
            return SolveOutcome.Solutions(
                exactLines = listOf("$variable = $exact"),
                decimalLines = listOf("$variable = $dec"),
            )
        }
        return solutionsOf(variable, roots)
    }

    private fun solveExp(left: String, right: String, variable: String): SolveOutcome? {
        solveCoshLike(left, right, variable)?.let { return it }
        val pow = parsePowerEq(left) ?: return null
        val b = safeEval(right, emptyMap()) ?: return null
        val a = safeEval(pow.base, emptyMap()) ?: return null
        if (a <= 0.0 || abs(a - 1.0) < 1e-12) return null
        if (b <= 0.0) return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        val logVal = ln(b) / ln(a)
        val roots = numericRoots("(${pow.exp})-($logVal)", variable)
            .filter { x -> satisfiesEquation(left, right, variable, x) }
        return if (roots.isEmpty()) SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        else solutionsOf(variable, roots)
    }

    private data class PowerParts(val base: String, val exp: String)

    private fun parsePowerEq(left: String): PowerParts? {
        val t = left.trim()
        if (splitTopLevel(t, '+').size > 1) return null
        val caret = splitTopLevel(t, '^')
        if (caret.size == 2 && isSimplePowerExp(caret[1])) {
            return PowerParts(caret[0], caret[1])
        }
        val expFn = Regex("^exp\\((.+)\\)$", RegexOption.IGNORE_CASE).matchEntire(t)
        if (expFn != null) return PowerParts("e", expFn.groupValues[1])
        return null
    }

    /** Rejects `x^3-8` (a difference) while keeping `2^(x+1)` and `2^-x`. */
    private fun isSimplePowerExp(exp: String): Boolean {
        val t = exp.trim()
        if (t.startsWith("(") && t.endsWith(")")) return true
        val rest = t.removePrefix("-")
        return rest.isNotEmpty() && rest.all { it.isLetterOrDigit() || it == '.' || it == '_' }
    }

    private fun solveCoshLike(left: String, right: String, variable: String): SolveOutcome? {
        val n = left.replace(" ", "")
        val forms = listOf(
            "e^$variable+e^(-$variable)",
            "e^($variable)+e^(-$variable)",
            "e^($variable)+e^(-($variable))",
            "exp($variable)+exp(-$variable)",
            "exp($variable)+exp(-($variable))",
        )
        if (forms.none { n.equals(it, ignoreCase = true) }) return null
        val c = safeEval(right, emptyMap()) ?: return null
        return when {
            c < 2.0 - 1e-9 -> SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
            abs(c - 2.0) <= 1e-9 -> solutionsOf(variable, listOf(0.0))
            else -> {
                val a = acosh(c / 2.0)
                solutionsOf(variable, listOf(a, -a))
            }
        }
    }

    private fun solveRational(left: String, right: String, variable: String): SolveOutcome? {
        val (ln, ld) = numDen(left)
        val (rn, rd) = numDen(right)
        if (ld == "1" && rd == "1") return null
        if (isConstantZero(ld) || isConstantZero(rd)) {
            return SolveOutcome.Error(SolveMessages.DIV_ZERO, 0, 0)
        }
        val cleared = "($ln)*($rd)-($rn)*($ld)"
        val dens = listOf(ld, rd).filter { it != "1" }
        val roots = numericRoots(cleared, variable).filter { x ->
            dens.all { d ->
                val v = safeEval(d, mapOf(variable to x))
                v != null && abs(v) > 1e-9
            } && satisfiesEquation(left, right, variable, x)
        }
        return if (roots.isEmpty()) SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        else solutionsOf(variable, roots)
    }

    private fun numDen(expr: String): Pair<String, String> {
        val parts = splitTopLevel(expr, '/')
        return if (parts.size == 2) parts[0] to parts[1] else expr to "1"
    }

    private fun isConstantZero(expr: String): Boolean {
        val t = expr.trim()
        if (t == "0" || t == "0.0" || t == "(0)") return true
        val vals = SAMPLE_X.mapNotNull { safeEval(t, mapOf("x" to it)) }
        return vals.isNotEmpty() && vals.all { abs(it) < 1e-12 }
    }

    private fun solvePolynomial(
        zero: String,
        variable: String,
        left: String,
        right: String,
    ): SolveOutcome? {
        val coeffs = fitPolynomial(zero, variable) ?: return null
        val roots = solvePolyCoeffs(coeffs)
        val real = roots.filter { satisfiesEquation(left, right, variable, it) }
        val degree = coeffs.indexOfLast { abs(it) > 1e-10 }.coerceAtLeast(0)
        if (real.isEmpty()) {
            return if (degree >= 2) SolveOutcome.Info(SolveMessages.NO_REAL)
            else SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        }
        return solutionsOf(variable, real)
    }

    /**
     * P(x) = c0 + c1 x + c2 x^2 + c3 x^3. Lowest degree that fits every
     * sample wins, so trig/exp residuals are rejected.
     */
    private fun fitPolynomial(zeroExpr: String, variable: String): DoubleArray? {
        val pts = SAMPLE_X.mapNotNull { x ->
            val y = safeEval(zeroExpr, mapOf(variable to x)) ?: return@mapNotNull null
            x to y
        }
        if (pts.size < 6) return null
        for (deg in 0..3) {
            val coeffs = vandermonde(pts.take(deg + 1), deg) ?: continue
            if (pts.all { (x, y) -> abs(polyEval(coeffs, x) - y) < 1e-6 }) {
                if (deg == 0) return null
                return coeffs
            }
        }
        return null
    }

    private fun vandermonde(pts: List<Pair<Double, Double>>, deg: Int): DoubleArray? {
        val n = deg + 1
        if (pts.size < n) return null
        val m = Array(n) { DoubleArray(n + 1) }
        for (i in 0 until n) {
            val (x, y) = pts[i]
            var p = 1.0
            for (j in 0 until n) {
                m[i][j] = p
                p *= x
            }
            m[i][n] = y
        }
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            }
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]
            m[col] = m[pivot]
            m[pivot] = tmp
            val div = m[col][col]
            for (k in col..n) m[col][k] /= div
            for (row in 0 until n) {
                if (row == col) continue
                val factor = m[row][col]
                for (k in col..n) m[row][k] -= factor * m[col][k]
            }
        }
        return DoubleArray(n) { m[it][n] }
    }

    private fun polyEval(c: DoubleArray, x: Double): Double {
        var acc = 0.0
        var p = 1.0
        for (coeff in c) {
            acc += coeff * p
            p *= x
        }
        return acc
    }

    private fun solvePolyCoeffs(c: DoubleArray): List<Double> {
        val coeffs = c.copyOf()
        var deg = coeffs.indexOfLast { abs(it) > 1e-10 }
        if (deg < 0) return emptyList()
        return when (deg) {
            1 -> {
                val x = -coeffs[0] / coeffs[1]
                listOf(x)
            }
            2 -> quadratic(coeffs[2], coeffs[1], coeffs[0])
            3 -> cubic(coeffs[3], coeffs[2], coeffs[1], coeffs[0])
            else -> emptyList()
        }
    }

    private fun quadratic(a: Double, b: Double, c: Double): List<Double> {
        if (abs(a) < 1e-12) {
            return if (abs(b) < 1e-12) emptyList() else listOf(-c / b)
        }
        val disc = b * b - 4 * a * c
        return when {
            disc < -1e-9 -> emptyList()
            abs(disc) <= 1e-9 -> listOf(-b / (2 * a))
            else -> {
                val s = sqrt(disc)
                listOf((-b + s) / (2 * a), (-b - s) / (2 * a))
            }
        }
    }

    /** Depressed-cubic / trig form; only real roots. */
    private fun cubic(a: Double, b: Double, c: Double, d: Double): List<Double> {
        if (abs(a) < 1e-12) return quadratic(b, c, d)
        val A = b / a
        val B = c / a
        val C = d / a
        val p = B - A * A / 3.0
        val q = 2.0 * A * A * A / 27.0 - A * B / 3.0 + C
        val disc = (q / 2.0).pow(2) + (p / 3.0).pow(3)
        val shift = A / 3.0
        val roots = when {
            disc > 1e-12 -> {
                val s = sqrt(disc)
                val u = cbrt(-q / 2.0 + s)
                val v = cbrt(-q / 2.0 - s)
                listOf(u + v - shift)
            }
            abs(disc) <= 1e-12 -> {
                val u = cbrt(-q / 2.0)
                unique(listOf(2 * u - shift, -u - shift))
            }
            else -> {
                val r = sqrt(-p / 3.0)
                val inner = ((-q / 2.0) / (r * r * r)).coerceIn(-1.0, 1.0)
                val phi = acos(inner)
                listOf(
                    2 * r * cos(phi / 3.0) - shift,
                    2 * r * cos((phi + 2 * PI) / 3.0) - shift,
                    2 * r * cos((phi + 4 * PI) / 3.0) - shift,
                )
            }
        }
        return roots.filter { x -> abs(polyEval(doubleArrayOf(d, c, b, a), x)) < 1e-5 }
    }

    private fun newtonRoots(zeroExpr: String, variable: String): List<Double> {
        val seeds = listOf(
            -20.0, -10.0, -5.0, -3.0, -2.0, -1.0, -0.5,
            0.0, 0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 8.0, 10.0, 16.0, PI, E,
        )
        val roots = mutableListOf<Double>()
        for (seed in seeds) {
            var x = seed
            repeat(60) {
                val y = safeEval(zeroExpr, mapOf(variable to x)) ?: return@repeat
                val y2 = safeEval(zeroExpr, mapOf(variable to x + 1e-6)) ?: return@repeat
                val yp = (y2 - y) / 1e-6
                if (abs(yp) < 1e-12) return@repeat
                val next = x - y / yp
                if (!next.isFinite()) return@repeat
                if (abs(next - x) < 1e-10) {
                    if (roots.none { abs(it - next) < 1e-6 }) roots += next
                    return@repeat
                }
                x = next
            }
        }
        return roots.filter { x ->
            val y = safeEval(zeroExpr, mapOf(variable to x))
            y != null && abs(y) < 1e-6
        }
    }

    internal fun satisfiesEquation(left: String, right: String, variable: String, x: Double): Boolean {
        if (!x.isFinite()) return false
        val vars = mapOf(variable to x)
        val l = safeEval(left, vars) ?: return false
        val r = safeEval(right, vars) ?: return false
        return abs(l - r) < 1e-6
    }

    internal fun safeEval(expr: String, vars: Map<String, Double>): Double? = try {
        val v = MathEngine.evaluate(expr, vars)
        v.takeIf { it.isFinite() }
    } catch (_: Throwable) {
        null
    }

    private fun hasLiteralZeroDivision(expr: String): Boolean =
        Regex("""/(?:0(?:\.0+)?)(?![.\d])""").containsMatchIn(expr)

    private fun formatExpExact(c: Double): String {
        val n = kotlin.math.round(c)
        if (abs(c - n) < 1e-9) {
            return when (n.toLong()) {
                0L -> "1"
                1L -> "e"
                -1L -> "1/e"
                else -> "e^${n.toLong()}"
            }
        }
        return "e^${formatExact(c)}"
    }

    private fun unique(values: List<Double>): List<Double> {
        val out = mutableListOf<Double>()
        for (v in values.sorted()) {
            if (out.none { abs(it - v) < 1e-6 }) out += v
        }
        return out
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = a
        var y = b
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return abs(x)
    }
}
