package com.example.mobilemathstudio.math

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Recursive-descent expression engine.
 * Supports implicit multiplication: 9x, 2(x+1), (x+1)(x-1).
 */
object MathEngine {

    fun solve(raw: String): SolveOutcome {
        return try {
            if (CasSolver.looksLikeOde(raw)) return SymjaEngine.solve(raw)
            InequalityDomainSolver.solve(raw)?.let { return it }
            CasSolver.solve(raw)?.let { return it }
            RadicalSymbolic.solve(raw)?.let { return it }
            TrigSymbolic.solve(raw)?.let { return it }
            SymjaEngine.solve(raw)
        } catch (_: Throwable) {
            runCatching { solveLocal(raw) }.getOrElse {
                SolveOutcome.Error("Could not evaluate", 0, 0)
            }
        }
    }

    /** Solve a system of equations or 1-variable inequalities (2+). Never throws to UI. */
    fun solveSystem(equations: List<String>): SolveOutcome {
        val cleaned = equations.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.size < 2) {
            return SolveOutcome.Error("System needs at least 2 equations", 0, 0)
        }
        return try {
            InequalityDomainSolver.solveSystemInequalities(cleaned)?.let { return it }
            SymjaEngine.solveSystem(cleaned)
        } catch (_: Throwable) {
            SolveOutcome.Error("Could not solve system", 0, 0)
        }
    }

    /** Original local recursive-descent path (fallback). */
    fun solveLocal(raw: String): SolveOutcome {
        val source = raw.trim()
        if (source.isBlank()) {
            return SolveOutcome.Error("Empty expression", 0, 0)
        }
        return try {
            when {
                source.contains('=') -> solveEquation(source)
                looksLike(source, "∫", "integral") -> integrate(source)
                looksLike(source, "d/dx", "diff") -> differentiate(source)
                looksLike(source, "lim") -> evaluateLim(source)
                looksLike(source, "Σ", "sum") -> evaluateSum(source)
                else -> {
                    val unknowns = collectUnknowns(source)
                    when {
                        unknowns.isEmpty() -> {
                            val text = formatNumber(evaluate(source, emptyMap()))
                            SolveOutcome.Value(exact = text, decimal = text)
                        }
                        unknowns.size == 1 -> {
                            SolveOutcome.Info("Expression contains unknown ${unknowns.first()}. Add \"=\" to solve.")
                        }
                        else -> SolveOutcome.Info("Too many unknowns: ${unknowns.joinToString(", ")}")
                    }
                }
            }
        } catch (e: ParseException) {
            val idx = e.index.coerceIn(0, source.length)
            val end = (idx + e.length).coerceIn(idx, source.length)
            SolveOutcome.Error(e.message ?: "Syntax error", idx, end)
        } catch (e: ArithmeticException) {
            SolveOutcome.Error(e.message ?: "Math error", 0, source.length)
        } catch (e: Exception) {
            SolveOutcome.Error(e.message ?: "Evaluation error", 0, source.length)
        }
    }

    private fun looksLike(source: String, vararg names: String): Boolean =
        names.any { source.startsWith("$it(") || (it.length == 1 && source.startsWith(it) && source.getOrNull(1) == '(') }

    private fun solveEquation(source: String): SolveOutcome {
        CasSolver.solve(source)?.let { return it }
        val eq = source.indexOf('=')
        if (source.indexOf('=', eq + 1) >= 0) {
            return SolveOutcome.Error("Multiple '=' not supported", eq, eq + 1)
        }
        val left = source.take(eq).trim()
        val right = source.substring(eq + 1).trim()
        if (left.isEmpty() || right.isEmpty()) {
            return SolveOutcome.Error("Incomplete equation", eq, eq + 1)
        }
        val combined = "($left)-($right)"
        val unknowns = collectUnknowns(combined)
        return when {
            unknowns.isEmpty() -> {
                val l = evaluate(left, emptyMap())
                val r = evaluate(right, emptyMap())
                if (abs(l - r) < 1e-9) SolveOutcome.Value(exact = "True", decimal = "true")
                else SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
            }
            unknowns.size > 1 -> SolveOutcome.Info("Too many unknowns: ${unknowns.joinToString(", ")}")
            else -> solveForVariable(combined, unknowns.first())
        }
    }

    private fun solveForVariable(zeroExpr: String, variable: String): SolveOutcome {
        val y0 = evaluate(zeroExpr, mapOf(variable to 0.0))
        val y1 = evaluate(zeroExpr, mapOf(variable to 1.0))
        val y2 = evaluate(zeroExpr, mapOf(variable to 2.0))
        val c = y0
        val a = (y2 - 2 * y1 + y0) / 2.0
        val b = y1 - y0 - a
        val isPoly = (0..4).all { k ->
            val x = k.toDouble()
            abs(a * x * x + b * x + c - evaluate(zeroExpr, mapOf(variable to x))) < 1e-6
        }
        if (isPoly) {
            if (abs(a) < 1e-12 && abs(b) < 1e-12) {
                return if (abs(c) < 1e-9) InequalityDomainSolver.domainForIdentity(variable, zeroExpr)
                else SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
            }
            if (abs(a) < 1e-12) {
                val text = "$variable = ${formatNumber(-c / b)}"
                return SolveOutcome.Solutions(exactLines = listOf(text), decimalLines = listOf(text))
            }
            val disc = b * b - 4 * a * c
            return when {
                disc < -1e-9 -> SolveOutcome.Info(SolveMessages.NO_REAL)
                abs(disc) <= 1e-9 -> {
                    val text = "$variable = ${formatNumber(-b / (2 * a))}"
                    SolveOutcome.Solutions(exactLines = listOf(text), decimalLines = listOf(text))
                }
                else -> {
                    val s = sqrt(disc)
                    val lines = listOf(
                        "$variable = ${formatNumber((-b + s) / (2 * a))}",
                        "$variable = ${formatNumber((-b - s) / (2 * a))}",
                    )
                    SolveOutcome.Solutions(exactLines = lines, decimalLines = lines)
                }
            }
        }
        val roots = mutableListOf<Double>()
        for (seed in listOf(-10.0, -2.0, -1.0, 0.0, 1.0, 2.0, 10.0)) {
            var x = seed
            repeat(40) {
                val y = evaluate(zeroExpr, mapOf(variable to x))
                val yp = (evaluate(zeroExpr, mapOf(variable to x + 1e-6)) - y) / 1e-6
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
        return if (roots.isEmpty()) SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        else {
            val lines = roots.sorted().map { "$variable = ${formatNumber(it)}" }
            SolveOutcome.Solutions(exactLines = lines, decimalLines = lines)
        }
    }

    private fun integrate(source: String): SolveOutcome {
        val inner = extractCallBody(source, listOf("∫", "integral"))
            ?: return SolveOutcome.Error("Invalid integral", 0, source.length)
        val extras = collectUnknowns(inner).filter { it != "x" }
        if (extras.isNotEmpty()) {
            return SolveOutcome.Info("Too many unknowns: ${extras.joinToString(", ")}")
        }
        return polyTransform(inner, integrate = true)
    }

    private fun differentiate(source: String): SolveOutcome {
        val inner = extractCallBody(source, listOf("d/dx", "diff"))
            ?: return SolveOutcome.Error("Invalid derivative", 0, source.length)
        return polyTransform(inner, integrate = false)
    }

    private fun polyTransform(inner: String, integrate: Boolean): SolveOutcome {
        val y0 = evaluate(inner, mapOf("x" to 0.0))
        val y1 = evaluate(inner, mapOf("x" to 1.0))
        val y2 = evaluate(inner, mapOf("x" to 2.0))
        val c = y0
        val a = (y2 - 2 * y1 + y0) / 2.0
        val b = y1 - y0 - a
        val isPoly = (0..4).all { k ->
            val x = k.toDouble()
            abs(a * x * x + b * x + c - evaluate(inner, mapOf("x" to x))) < 1e-6
        }
        if (!isPoly) {
            return SolveOutcome.Info(if (integrate) "Integral form not supported yet" else "Derivative form not supported yet")
        }
        if (integrate) {
            val parts = mutableListOf<String>()
            if (abs(a) > 1e-12) parts += "${formatNumber(a / 3)}*x^3"
            if (abs(b) > 1e-12) parts += "${formatNumber(b / 2)}*x^2"
            if (abs(c) > 1e-12) parts += "${formatNumber(c)}*x"
            val body = if (parts.isEmpty()) "C" else parts.joinToString(" + ") + " + C"
            return SolveOutcome.Value(exact = body, decimal = body)
        }
        val parts = mutableListOf<String>()
        if (abs(2 * a) > 1e-12) parts += "${formatNumber(2 * a)}*x"
        if (abs(b) > 1e-12) parts += formatNumber(b)
        val body = if (parts.isEmpty()) "0" else parts.joinToString(" + ")
        return SolveOutcome.Value(exact = body, decimal = body)
    }

    /** lim(expr, a) — evaluate expr at x = a (continuous polynomial / smooth). */
    private fun evaluateLim(source: String): SolveOutcome {
        val inner = extractCallBody(source, listOf("lim"))
            ?: return SolveOutcome.Error("Use lim(expr, value)", 0, source.length)
        val args = splitTopLevelArgs(inner)
        if (args.size != 2) {
            return SolveOutcome.Error("Use lim(expr, value)", 0, source.length)
        }
        val at = evaluate(args[1], emptyMap())
        val unknowns = collectUnknowns(args[0])
        if (unknowns.size > 1) {
            return SolveOutcome.Info("Too many unknowns: ${unknowns.joinToString(", ")}")
        }
        val variable = unknowns.firstOrNull() ?: "x"
        val value = evaluate(args[0], mapOf(variable to at))
        val text = formatNumber(value)
        return SolveOutcome.Value(exact = text, decimal = text)
    }

    /** Σ(expr, n) — sum expr for k = 1..n (integer n ≥ 0). */
    private fun evaluateSum(source: String): SolveOutcome {
        val inner = extractCallBody(source, listOf("Σ", "sum"))
            ?: return SolveOutcome.Error("Use Σ(expr, n)", 0, source.length)
        val args = splitTopLevelArgs(inner)
        if (args.size != 2) {
            return SolveOutcome.Error("Use Σ(expr, n)", 0, source.length)
        }
        val nVal = evaluate(args[1], emptyMap())
        if (abs(nVal - nVal.toLong()) > 1e-9 || nVal < 0 || nVal > 10_000) {
            return SolveOutcome.Error("n must be an integer 0…10000", 0, source.length)
        }
        val n = nVal.toLong()
        var total = 0.0
        for (k in 1..n) {
            total += evaluate(args[0], mapOf("k" to k.toDouble(), "i" to k.toDouble()))
        }
        val text = formatNumber(total)
        return SolveOutcome.Value(exact = text, decimal = text)
    }

    private fun splitTopLevelArgs(inner: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in inner.indices) {
            when (inner[i]) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                ',' -> if (depth == 0) {
                    out += inner.substring(start, i).trim()
                    start = i + 1
                }
            }
        }
        out += inner.substring(start).trim()
        return out.filter { it.isNotEmpty() }
    }

    private fun extractCallBody(source: String, names: List<String>): String? {
        for (name in names) {
            val prefix = "$name("
            if (source.startsWith(prefix) && source.endsWith(")")) {
                return source.substring(prefix.length, source.length - 1)
            }
        }
        return null
    }

    fun collectUnknowns(source: String): Set<String> {
        val unknowns = linkedSetOf<String>()
        var i = 0
        while (i < source.length) {
            // Skip d/dx keyword
            if (source.startsWith("d/dx", i)) {
                i += 4
                continue
            }
            val c = source[i]
            if (c.isLetter()) {
                var j = i + 1
                while (j < source.length && source[j].isLetterOrDigit()) j++
                val name = source.substring(i, j)
                val key = name.lowercase()
                if (key !in CONSTANTS && key !in FUNCTIONS) unknowns += name
                i = j
            } else {
                i++
            }
        }
        return unknowns
    }

    fun evaluate(source: String, vars: Map<String, Double>): Double =
        CompiledExpression.compile(source).evaluate(vars)

    /**
     * Always a dot separator: a decimal comma would collide with the comma that
     * separates several solutions on one line ("x = 3,5" vs "x = 3, 5").
     */
    fun formatNumber(value: Double): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
        val asLong = value.toLong()
        if (abs(value - asLong) < 1e-10 && abs(value) < 1e15) return asLong.toString()
        var s = String.format(java.util.Locale.ROOT, "%.10f", value).trimEnd('0').trimEnd('.')
        if (s == "-0") s = "0"
        return s
    }

    /** Lightweight normalizer used by ODZ checkers. */
    fun normalizeForCheck(raw: String): String = raw
        .replace("÷", "/")
        .replace("×", "*")
        .replace("·", "*")
        .replace("−", "-")
        .replace(" ", "")
        .replace("\n", "")

    private val CONSTANTS = setOf("pi", "e", "infinity")
    private val FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "asin", "acos", "atan",
        "log", "ln", "sqrt", "abs", "exp", "lim", "sum",
    )

}
