package com.example.mobilemathstudio.math

import org.matheclipse.core.eval.ExprEvaluator
import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IExpr
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Symja-backed symbolic engine with ODZ filtering for radical equations
 * and pretty-printed exact / decimal forms.
 */
object SymjaEngine {
    private val evaluator = AtomicReference<ExprEvaluator?>(null)

    /** One ExprEvaluator serves every caller and it is not thread-safe. */
    private val lock = Any()

    /** √ immediately followed by a bare number or identifier. */
    private val BARE_ROOT = Regex("""√(\d+(?:\.\d+)?|[A-Za-z]\w*)""")

    private fun eval(): ExprEvaluator {
        Log4jCallerFix.install()
        return evaluator.updateAndGet { existing ->
            existing ?: ExprEvaluator(/* relaxedSyntax = */ false, /* historySize = */ 64)
        }!!
    }

    val isReady: Boolean get() = evaluator.get() != null

    /**
     * Loads Symja's symbol tables (thousands of classes, seconds on a phone) so
     * the first real solve does not pay for it. Must not run on the main thread.
     */
    fun warmUp() {
        synchronized(lock) {
            runCatching { eval().evaluate("Solve(x^2==4,x)") }
        }
    }

    fun solve(raw: String): SolveOutcome = synchronized(lock) { solveLocked(raw) }

    private fun solveLocked(raw: String): SolveOutcome {
        val source = raw.trim()
        if (source.isBlank()) return SolveOutcome.Error("Empty expression", 0, 0)
        return try {
            if (looksLikeODE(source)) {
                return solveODE(source)
            }
            val symja = toSymjaInput(source)
            when {
                '=' in source -> solveEquation(source, symja)
                else -> evaluateExpression(symja)
            }
        } catch (t: Throwable) {
            // Never crash the UI — fall back to local engine (avoid recurse into Symja).
            MathEngine.solveLocal(raw)
        }
    }

    fun solveSystem(equations: List<String>): SolveOutcome = synchronized(lock) { solveSystemLocked(equations) }

    private fun solveSystemLocked(equations: List<String>): SolveOutcome {
        return try {
            val e = eval()
            val symjaEqs = equations.map { eq ->
                var s = toSymjaInput(eq)
                // toSymjaInput already wraps == ; ensure list form
                s
            }
            val vars = equations.flatMap { MathEngine.collectUnknowns(it) }.distinct().sorted()
            if (vars.isEmpty()) return SolveOutcome.Info("No unknowns in system")
            val eqList = symjaEqs.joinToString(",")
            val varList = vars.joinToString(",")
            val solved = runCatching { e.evaluate("Solve({$eqList},{$varList})") }.getOrNull()
            val reduced = runCatching { e.evaluate("Reduce(And($eqList),{$varList},Reals)") }.getOrNull()

            val sets = formatMultiVarSolutions(solved) + formatMultiVarSolutions(reduced)
            val seen = linkedSetOf<String>()
            val exactLines = mutableListOf<String>()
            val decimalLines = mutableListOf<String>()
            for (set in sets) {
                if (set.isEmpty()) continue
                // One variable per line: "x = 2" above "y = 3", never comma-joined.
                val exact = set.map { (name, value) -> "$name = ${pretty(value)}" }
                if (!seen.add(exact.joinToString("\n"))) continue
                if (exactLines.isNotEmpty()) {
                    exactLines += ""
                    decimalLines += ""
                }
                exactLines += exact
                decimalLines += set.map { (name, value) ->
                    "$name = ${numericOrNull(value) ?: pretty(value)}"
                }
            }
            if (exactLines.isNotEmpty()) {
                return SolveOutcome.Solutions(
                    exactLines = exactLines,
                    decimalLines = decimalLines.takeIf { it != exactLines },
                    latex = exactLines.filter { it.isNotBlank() }.joinToString("; "),
                )
            }

            // Symja gave up: try a plain numeric linear solve before reporting failure.
            numericLinearSolve(equations, vars)?.let { return it }
            SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        } catch (_: Throwable) {
            runCatching {
                val vars = equations.flatMap { MathEngine.collectUnknowns(it) }.distinct().sorted()
                numericLinearSolve(equations, vars)
            }.getOrNull() ?: SolveOutcome.Error("Could not solve system", 0, 0)
        }
    }

    private fun numericOrNull(value: IExpr): String? = runCatching {
        val n = eval().evaluate("N($value)") ?: return null
        if (n.isSymbol || n.isAST) return null
        formatDecimal(n)
    }.getOrNull()

    /**
     * Independent fallback for any square linear system: builds the Jacobian
     * numerically, solves it by Gaussian elimination with partial pivoting and
     * verifies the residuals — which also rejects non-linear input.
     */
    private fun numericLinearSolve(equations: List<String>, vars: List<String>): SolveOutcome? {
        if (vars.isEmpty() || equations.size < vars.size) return null
        val zeroForms = equations.mapNotNull { raw ->
            val normalized = MathEngine.normalizeForCheck(raw)
            val eq = normalized.indexOf('=')
            if (eq < 0) null else "(${normalized.take(eq)})-(${normalized.substring(eq + 1)})"
        }
        if (zeroForms.size < vars.size) return null

        fun residuals(point: Map<String, Double>): List<Double>? = runCatching {
            zeroForms.map { MathEngine.evaluate(it, point) }
        }.getOrNull()

        val origin = vars.associateWith { 0.0 }
        val base = residuals(origin) ?: return null
        val n = vars.size
        val matrix = Array(n) { DoubleArray(n + 1) }
        for (row in 0 until n) matrix[row][n] = -base[row]
        for (col in 0 until n) {
            val shifted = residuals(origin + (vars[col] to 1.0)) ?: return null
            for (row in 0 until n) matrix[row][col] = shifted[row] - base[row]
        }

        // Gaussian elimination with partial pivoting.
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (abs(matrix[row][col]) > abs(matrix[pivot][col])) pivot = row
            }
            if (abs(matrix[pivot][col]) < 1e-12) return null
            val tmp = matrix[col]
            matrix[col] = matrix[pivot]
            matrix[pivot] = tmp
            for (row in 0 until n) {
                if (row == col) continue
                val factor = matrix[row][col] / matrix[col][col]
                if (factor == 0.0) continue
                for (k in col..n) matrix[row][k] -= factor * matrix[col][k]
            }
        }

        val solution = vars.indices.associate { i -> vars[i] to matrix[i][n] / matrix[i][i] }
        if (solution.values.any { !it.isFinite() }) return null
        // Guards against silently "solving" a non-linear system.
        val check = residuals(solution) ?: return null
        val tolerance = 1e-6 * (1.0 + base.maxOf { abs(it) })
        if (check.any { abs(it) > tolerance }) return null

        val lines = vars.map { name -> "$name = ${MathEngine.formatNumber(solution.getValue(name))}" }
        return SolveOutcome.Solutions(exactLines = lines, decimalLines = lines)
    }

    private fun looksLikeODE(source: String): Boolean {
        val s = source.replace(" ", "")
        return s.contains("y'") || s.contains("y''") || s.contains("y'''") ||
            s.contains("dy/dx", ignoreCase = true) ||
            s.contains("d^2y/dx^2", ignoreCase = true) ||
            s.contains("d2y/dx2", ignoreCase = true) ||
            Regex("""d\^\d+y/dx\^\d+""", RegexOption.IGNORE_CASE).containsMatchIn(s) ||
            s.startsWith("DSolve", ignoreCase = true)
    }

    private fun solveODE(raw: String): SolveOutcome {
        val e = eval()
        val prepared = prepareODE(raw)
        val result = runCatching {
            e.evaluate(prepared)
        }.getOrNull() ?: return SolveOutcome.Error("ODE solve failed", 0, raw.length)
        val exact = pretty(result)
            .replace("C(1)", "C₁")
            .replace("C(2)", "C₂")
            .replace("C(3)", "C₃")
            .replace("C[1]", "C₁")
            .replace("C[2]", "C₂")
        return SolveOutcome.Solutions(
            exactLines = listOf(exact),
            decimalLines = null,
            latex = exact,
        )
    }

    private fun prepareODE(raw: String): String {
        if (raw.trim().startsWith("DSolve", ignoreCase = true)) {
            return toSymjaInput(raw)
        }
        var s = raw.trim()
            .replace("−", "-")
            .replace("×", "*")
            .replace("÷", "/")
            .replace("π", "Pi")
        // Higher-order primes first
        s = s.replace("y'''", "D(y(x),{x,3})")
        s = s.replace("y''", "D(y(x),{x,2})")
        s = s.replace("y'", "D(y(x),x)")
        s = s.replace(Regex("""(?i)d\^3y/dx\^3"""), "D(y(x),{x,3})")
        s = s.replace(Regex("""(?i)d\^2y/dx\^2"""), "D(y(x),{x,2})")
        s = s.replace(Regex("""(?i)d2y/dx2"""), "D(y(x),{x,2})")
        s = s.replace(Regex("""(?i)dy/dx"""), "D(y(x),x)")
        // Bare y → y(x) when not already y(
        s = s.replace(Regex("""\by\b(?!\s*\()"""), "y(x)")
        s = insertImplicitMul(s.replace(" ", ""))
        if ('=' in s) {
            val i = s.indexOf('=')
            s = "(${s.take(i)})==(${s.substring(i + 1)})"
        }
        return "DSolve($s, y(x), x)"
    }

    /** One entry per solution set: `[[x to -11/9, y to -11/9]]`. */
    private fun formatMultiVarSolutions(expr: IExpr?): List<List<Pair<String, IExpr>>> {
        if (expr == null || expr.isFalse) return emptyList()
        val out = mutableListOf<List<Pair<String, IExpr>>>()

        fun binding(node: IExpr): Pair<String, IExpr>? = when {
            node.isRule || node.isRuleDelayed -> pretty(node.first()) to node.second()
            node.isAST && node.head() == F.Equal && node.first().isSymbol ->
                pretty(node.first()) to node.second()
            else -> null
        }

        fun walk(node: IExpr) {
            when {
                // size() counts the head, so the last argument is size() - 1.
                node.isList -> {
                    val bindings = mutableListOf<Pair<String, IExpr>>()
                    var allBindings = true
                    for (i in 1 until node.size()) {
                        val child = node.getAt(i)
                        val bound = binding(child)
                        if (bound != null) {
                            bindings += bound
                        } else {
                            allBindings = false
                            walk(child)
                        }
                    }
                    if (allBindings && bindings.isNotEmpty()) out += bindings
                }
                node.isAST && node.head() == F.Or -> {
                    for (i in 1 until node.size()) walk(node.getAt(i))
                }
                node.isAST && node.head() == F.And -> {
                    val parts = mutableListOf<Pair<String, IExpr>>()
                    for (i in 1 until node.size()) {
                        binding(node.getAt(i))?.let { parts += it }
                    }
                    if (parts.isNotEmpty()) out += parts
                }
                else -> binding(node)?.let { out += listOf(it) }
            }
        }
        walk(expr)
        return out
    }

    /** Plain numbers, roots, π and fractions are handled locally without touching Symja. */
    fun toDecimal(exact: String): String? {
        localDecimal(exact)?.let { return it }
        return synchronized(lock) {
            try {
                val n = eval().evaluate("N(${toSymjaInput(exact)})")
                if (n == null || n.isAST || n.isSymbol) null
                else formatDecimal(n)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun localDecimal(exact: String): String? {
        val source = MathEngine.normalizeForCheck(exact)
        if (source.isEmpty() || MathEngine.collectUnknowns(source).isNotEmpty()) return null
        return try {
            MathEngine.formatNumber(MathEngine.evaluate(source, emptyMap()))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The value written out with [digits] significant digits — how the long-number
     * viewer gets more of π, e or a root once the reader scrolls that far.
     */
    fun highPrecision(exact: String, digits: Int): String? = synchronized(lock) {
        highPrecisionLocked(exact, digits)
    }

    private fun highPrecisionLocked(exact: String, digits: Int): String? {
        return try {
            val e = eval()
            val wanted = digits.coerceIn(2, 4000)
            val n = e.evaluate("N(${toSymjaInput(exact)}, $wanted)")
                ?: e.evaluate("N(${toSymjaInput(exact)})")
                ?: return null
            if (n.isSymbol) return null
            sanitizeNumeric(n.toString())
        } catch (_: Throwable) {
            null
        }
    }

    /** Drops CAS decorations (` backticks, *^ scientific form) so BigDecimal can parse it. */
    internal fun sanitizeNumeric(raw: String): String? {
        var s = raw.trim().replace("`", "").replace(" ", "")
        if (s.isEmpty() || s == "Null" || s == "Indeterminate") return null
        val sci = Regex("""^([+-]?\d+(?:\.\d+)?)(?:\*\^|\*10\^)([+-]?\d+)$""")
        sci.matchEntire(s)?.let { match ->
            val mantissa = match.groupValues[1]
            val exp = match.groupValues[2].toInt()
            return java.math.BigDecimal(mantissa).scaleByPowerOfTen(exp).toPlainString()
        }
        return s.takeIf { it.any { ch -> ch.isDigit() } }
    }

    private fun evaluateExpression(symja: String): SolveOutcome {
        val e = eval()
        val simplified = e.evaluate("Simplify($symja)") ?: e.evaluate(symja)
            ?: return SolveOutcome.Error("Cannot evaluate")
        val exact = pretty(simplified)
        val decimal = runCatching { formatDecimal(e.evaluate("N($symja)")!!) }.getOrNull()
        return SolveOutcome.Value(exact = exact, decimal = decimal, latex = exact)
    }

    private fun solveEquation(raw: String, symjaEq: String): SolveOutcome {
        val e = eval()
        val variable = MathEngine.collectUnknowns(raw).singleOrNull() ?: "x"
        // Prefer Reduce for domain-aware solutions; fall back to Solve.
        val reduced = runCatching {
            e.evaluate("Reduce($symjaEq, $variable, Reals)")
        }.getOrNull()
        if (reduced != null) {
            if (reduced.isTrue) {
                return InequalityDomainSolver.domainForIdentity(variable, raw)
            }
            if (reduced.isFalse) {
                val complex = runCatching { e.evaluate("Solve($symjaEq, $variable)") }.getOrNull()
                val hasComplex = extractSolutions(complex).any { !isRealSolution(it) }
                return SolveOutcome.Info(if (hasComplex) SolveMessages.NO_REAL else SolveMessages.NO_SOLUTIONS)
            }
        }
        val solved = runCatching {
            e.evaluate("Solve($symjaEq, $variable)")
        }.getOrNull()

        val candidates = extractSolutions(reduced) + extractSolutions(solved)
        val unique = linkedSetOf<String>()
        val exactLines = mutableListOf<String>()
        val decimalLines = mutableListOf<String>()

        for (cand in candidates) {
            val key = pretty(cand)
            if (!unique.add(key)) continue
            val parametric = isParametric(cand)
            if (!parametric && !isRealSolution(cand)) continue
            if (!parametric && !satisfiesOriginal(raw, cand, variable)) continue
            exactLines += "$variable = ${pretty(cand)}"
            if (!parametric) {
                runCatching {
                    decimalLines += "$variable = ${formatDecimal(e.evaluate("N($cand)")!!)}"
                }
            }
        }

        // Pattern-based trig fallback if Symja returns empty / numeric-only mess
        if (exactLines.isEmpty()) {
            TrigSymbolic.solve(raw)?.let { return it }
            RadicalSymbolic.solve(raw)?.let { return it }
            CasSolver.solve(raw)?.let { return it }
            return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        }

        // If trig pattern applies and gives cleaner symbolic form, prefer it
        TrigSymbolic.solve(raw)?.let { trig ->
            if (trig is SolveOutcome.Solutions && looksNumericHeavy(exactLines)) return trig
        }
        RadicalSymbolic.solve(raw)?.let { rad ->
            if (rad is SolveOutcome.Solutions) return rad
        }

        return SolveOutcome.Solutions(
            exactLines = exactLines,
            decimalLines = decimalLines.takeIf { it.size == exactLines.size },
            latex = exactLines.joinToString(", "),
        )
    }

    private fun isParametric(expr: IExpr): Boolean {
        val s = expr.toString()
        return "C(" in s || "C[" in s || s.contains("Period") || s.contains("Element")
    }

    private fun looksNumericHeavy(lines: List<String>): Boolean =
        lines.any { line ->
            line.contains(Regex("""\d+\.\d{3,}"""))
        }

    /**
     * Equations are solved over the reals: x² = -1 must read "no solutions"
     * rather than handing a school student ±i. Symbolic families (2πk + …)
     * cannot be evaluated numerically and are kept.
     */
    private fun isRealSolution(candidate: IExpr): Boolean = runCatching {
        val imaginary = eval().evaluate("Im(N($candidate))") ?: return true
        if (imaginary.isSymbol || imaginary.isAST) return true
        abs(imaginary.evalDouble()) < 1e-9
    }.getOrElse { true }

    private fun satisfiesOriginal(raw: String, candidate: IExpr, variable: String): Boolean {
        return try {
            val e = eval()
            val expr = toSymjaInput(raw)
            val parts = expr.split("==")
            if (parts.size != 2) return true
            val check = e.evaluate("ReplaceAll((${parts[0]})-(${parts[1]}), $variable->$candidate)")
            val num = e.evaluate("N($check)")
            val okEq = num != null && !num.isAST && abs(num.evalDouble()) < 1e-6

            // Also enforce if equation is Sqrt(a)==b then b>=0
            val rhsNonNeg = if (parts[0].trim().startsWith("Sqrt(") || raw.contains("√")) {
                val rhs = e.evaluate("ReplaceAll((${parts[1]}), $variable->$candidate)")
                val rhsN = e.evaluate("N($rhs)")
                rhsN != null && !rhsN.isAST && rhsN.evalDouble() >= -1e-9
            } else {
                true
            }
            // Log ODZ: every Log argument must be positive.
            val logOk = runCatching {
                val logs = e.evaluate("Cases($expr, Log(u_) | Log(b_, u_), {0, Infinity})")
                if (logs == null || logs.size() <= 1) true
                else {
                    var ok = true
                    for (i in 1 until logs.size()) {
                        val node = logs.getAt(i)
                        val arg = if (node.size() >= 3) node.second() else node.first()
                        val v = e.evaluate("N(ReplaceAll($arg, $variable->$candidate))")
                        if (v == null || v.isAST || v.evalDouble() <= 1e-12) ok = false
                    }
                    ok
                }
            }.getOrDefault(true)
            okEq && rhsNonNeg && logOk
        } catch (_: Throwable) {
            // If check fails open, keep candidate only when numeric residual is ok via local engine
            try {
                val xVal = candidate.evalDouble()
                val normalized = MathEngine.normalizeForCheck(raw)
                val eq = normalized.indexOf('=')
                if (eq < 0) return true
                val left = normalized.take(eq)
                val right = normalized.substring(eq + 1)
                val l = MathEngine.evaluate(left, mapOf(variable to xVal))
                val r = MathEngine.evaluate(right, mapOf(variable to xVal))
                abs(l - r) < 1e-6
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun extractSolutions(expr: IExpr?): List<IExpr> {
        if (expr == null || expr.isFalse) return emptyList()
        val out = mutableListOf<IExpr>()
        fun walk(node: IExpr) {
            when {
                // size() counts the head, so the last argument is size() - 1.
                node.isList -> {
                    for (i in 1 until node.size()) walk(node.getAt(i))
                }
                node.isRule || node.isRuleDelayed -> {
                    out += node.second()
                }
                node.isAST && node.head() == F.Or -> {
                    for (i in 1 until node.size()) walk(node.getAt(i))
                }
                node.isAST && node.head() == F.And -> {
                    for (i in 1 until node.size()) walk(node.getAt(i))
                }
                node.isAST && node.head() == F.Equal -> {
                    // x == value
                    if (node.first().isSymbol) out += node.second()
                }
                else -> Unit
            }
        }
        walk(expr)
        return out
    }

    private fun pretty(expr: IExpr): String {
        var s = expr.toString()
            // Order matters: ComplexInfinity must not become "Complex∞".
            .replace("ComplexInfinity", "∞")
            .replace("DirectedInfinity", "∞")
            .replace("Indeterminate", "undefined")
            .replace("Pi", "π")
            .replace("Infinity", "∞")
            .replace("Sqrt", "√")
            .replace("==", "=")
        // Imaginary unit: I*2 → 2i, bare I → i.
        s = s.replace(Regex("""\bI\*(\d+(?:\.\d+)?)"""), "$1i")
            .replace(Regex("""\bI\b"""), "i")
        // C(1) → k
        s = s.replace(Regex("""C\((\d+)\)"""), "k")
            .replace(Regex("""C\[(\d+)]"""), "k")
        return s
    }

    private fun formatDecimal(expr: IExpr): String {
        val d = expr.evalDouble()
        return MathEngine.formatNumber(d)
    }

    /** Convert app keyboard syntax into Symja input. */
    fun toSymjaInput(raw: String): String {
        var s = raw.trim()
            .replace("÷", "/")
            .replace("×", "*")
            .replace("·", "*")
            .replace("−", "-")
            .replace("π", "Pi")
            .replace("∞", "Infinity")
            .replace("√(", "Sqrt(")
            .replace("asin(", "ArcSin(")
            .replace("acos(", "ArcCos(")
            .replace("atan(", "ArcTan(")
            .replace("arcsin(", "ArcSin(")
            .replace("arccos(", "ArcCos(")
            .replace("arctan(", "ArcTan(")
            .replace("log(", "Log(10,")
            .replace("ln(", "Log(")
            .replace(" ", "")
        // Bare radicals (√55, √x) need explicit grouping or Symja reads "Sqrt55".
        s = BARE_ROOT.replace(s) { m -> "Sqrt(${m.groupValues[1]})" }
        s = s.replace("√", "Sqrt")
        s = rewriteTrigPowers(s)
        s = wrapAbsBars(s)
        s = rewriteEuler(s)
        // Implicit multiplication insertion for Symja parser safety
        s = insertImplicitMul(s)
        if ('=' in s) {
            val i = s.indexOf('=')
            s = "(${s.take(i)})==(${s.substring(i + 1)})"
        }
        return s
    }

    /** Rewrites `sin^2(x)` → `(sin(x))^2` so Symja parses function powers cleanly. */
    private fun rewriteTrigPowers(input: String): String {
        val pattern = Regex("""(?i)\b(sin|cos|tan|cot|ln|log)\^([+-]?\d+(?:\.\d+)?)\(""")
        var s = input
        while (true) {
            val m = pattern.find(s) ?: return s
            val open = m.range.last
            var depth = 0
            var close = -1
            for (i in open until s.length) {
                when (s[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            close = i
                            break
                        }
                    }
                }
            }
            if (close < 0) return s
            val fn = m.groupValues[1]
            val exp = m.groupValues[2]
            val arg = s.substring(open + 1, close)
            s = s.substring(0, m.range.first) + "($fn($arg))^$exp" + s.substring(close + 1)
        }
    }

    /** `|2x-5|` → `Abs(2x-5)`, including nested bars. */
    private fun wrapAbsBars(input: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            if (input[i] == '|') {
                var j = i + 1
                var depth = 0
                while (j < input.length) {
                    when (input[j]) {
                        '(' -> depth++
                        ')' -> depth--
                        '|' -> if (depth == 0) break
                    }
                    j++
                }
                if (j < input.length && input[j] == '|') {
                    sb.append("Abs(").append(wrapAbsBars(input.substring(i + 1, j))).append(")")
                    i = j + 1
                    continue
                }
            }
            sb.append(input[i])
            i++
        }
        return sb.toString()
    }

    /** Bare Euler `e` → Symja `E`, without touching `exp` or scientific `2e-3`. */
    private fun rewriteEuler(input: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            val euler = (c == 'e' || c == 'E') &&
                (i == 0 || !input[i - 1].isLetterOrDigit()) &&
                (i + 1 >= input.length || !input[i + 1].isLetterOrDigit())
            if (euler) {
                val scientific = i > 0 && input[i - 1].isDigit() &&
                    (input.getOrNull(i + 1) == '+' || input.getOrNull(i + 1) == '-' ||
                        input.getOrNull(i + 1)?.isDigit() == true)
                if (!scientific) {
                    sb.append('E')
                    i++
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun insertImplicitMul(input: String): String {
        val sb = StringBuilder()
        for (i in input.indices) {
            val c = input[i]
            sb.append(c)
            val n = input.getOrNull(i + 1) ?: break
            if (needsProduct(c, n)) sb.append('*')
        }
        return sb.toString()
    }

    /**
     * Juxtaposition means multiplication only *between* tokens. Splitting a
     * multi-digit literal (55 → 5*5) or an identifier is never correct.
     */
    private fun needsProduct(c: Char, n: Char): Boolean {
        val endsValue = c.isLetterOrDigit() || c == ')' || c == ']' || c == '!'
        val startsValue = n.isLetterOrDigit() || n == '(' || n == '['
        if (!endsValue || !startsValue) return false
        if (c.isDigit() && n.isDigit()) return false
        if (c.isLetter()) return false
        return true
    }
}
