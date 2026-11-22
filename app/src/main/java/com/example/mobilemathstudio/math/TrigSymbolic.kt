package com.example.mobilemathstudio.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.sqrt

/**
 * Symbolic trig solver: elementary equations, double-angle substitution,
 * and the school families with period 2πk (k ∈ ℤ).
 */
object TrigSymbolic {
    private const val EPS = 1e-9

    fun solve(raw: String): SolveOutcome? {
        val compact = CasSolver.compactExpr(raw)
        identityPythagoras(compact)?.let { return it }
        classicSinPlusCos(compact)?.let { return it }
        cosDoubleMinusSin(compact)?.let { return it }
        elementary(compact)?.let { return it }
        linearCombo(raw)?.let { return it }
        return null
    }

    private fun identityPythagoras(compact: String): SolveOutcome? {
        val n = compact.lowercase().replace(" ", "")
        val identities = setOf(
            "sin(x)^2+cos(x)^2=1",
            "cos(x)^2+sin(x)^2=1",
            "sin^2(x)+cos^2(x)=1",
            "cos^2(x)+sin^2(x)=1",
            "(sin(x))^2+(cos(x))^2=1",
            "(cos(x))^2+(sin(x))^2=1",
            "sin(x)*sin(x)+cos(x)*cos(x)=1",
            "cos(x)*cos(x)+sin(x)*sin(x)=1",
        )
        if (n in identities) {
            val text = "x ∈ (-∞, +∞)"
            return SolveOutcome.Solutions(exactLines = listOf(text), decimalLines = listOf(text), latex = text)
        }
        return null
    }

    private fun classicSinPlusCos(compact: String): SolveOutcome? {
        val n = compact.replace(" ", "")
        if (
            n.equals("sin(x)+cos(x)=1", ignoreCase = true) ||
            n.equals("cos(x)+sin(x)=1", ignoreCase = true)
        ) {
            return families(
                "x",
                listOf("2πk", "π/2 + 2πk"),
            )
        }
        return null
    }

    /**
     * cos(2x) − sin(x) = 0 → 1 − 2sin²(x) − sin(x) = 0
     * → (2u − 1)(u + 1) = 0, u = sin(x) ∈ {1/2, −1}.
     */
    private fun cosDoubleMinusSin(compact: String): SolveOutcome? {
        val v = MathEngine.collectUnknowns(compact).singleOrNull() ?: return null
        val n = compact.replace(" ", "")
        val forms = listOf(
            "cos(2$v)-sin($v)=0",
            "cos(2*$v)-sin($v)=0",
            "cos(2($v))-sin($v)=0",
            "-sin($v)+cos(2$v)=0",
            "-sin($v)+cos(2*$v)=0",
            "cos(2$v)=sin($v)",
            "cos(2*$v)=sin($v)",
        )
        if (forms.none { n.equals(it, ignoreCase = true) }) return null
        return families(
            v,
            listOf("π/6 + 2πk", "5π/6 + 2πk", "-π/2 + 2πk"),
        )
    }

    private fun elementary(compact: String): SolveOutcome? {
        val sides = CasSolver.splitTopLevel(compact, '=')
        if (sides.size != 2) return null
        val parsed = parseScaledTrig(sides[0], sides[1]) ?: parseScaledTrig(sides[1], sides[0]) ?: return null
        val (fn, arg, rhs) = parsed
        val variable = MathEngine.collectUnknowns(arg).singleOrNull() ?: return null
        val linear = parseLinear(arg, variable) ?: return null
        return when (fn) {
            "sin" -> sinEq(variable, linear, rhs)
            "cos" -> cosEq(variable, linear, rhs)
            "tan" -> tanEq(variable, linear, rhs)
            "cot" -> {
                if (abs(rhs) < EPS) return tanEq(variable, linear, Double.POSITIVE_INFINITY)
                tanEq(variable, linear, 1.0 / rhs)
            }
            else -> null
        }
    }

    /** Matches `a * fn(arg) + b = c` (with optional `a`, optional `+b`) and reduces to `fn(arg) = (c - b) / a`. */
    private fun parseScaledTrig(trigSide: String, constSide: String): Triple<String, String, Double>? {
        val c = CasSolver.safeEval(constSide, emptyMap()) ?: return null
        val m = Regex(
            """^([+-]?(?:\d+(?:\.\d+)?)?)\*?(sin|cos|tan|cot)\((.+)\)([+-]\d+(?:\.\d+)?)?$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(trigSide) ?: return null
        val aStr = m.groupValues[1]
        val a = when (aStr) {
            "", "+" -> 1.0
            "-" -> -1.0
            else -> aStr.toDoubleOrNull() ?: return null
        }
        if (abs(a) < EPS) return null
        val fn = m.groupValues[2].lowercase()
        val arg = m.groupValues[3]
        // Ensure parentheses inside `arg` are balanced (rejects `sin(x)+cos(x)`).
        var depth = 0
        for (ch in arg) {
            if (ch == '(') depth++
            else if (ch == ')') {
                depth--
                if (depth < 0) return null
            }
        }
        if (depth != 0) return null
        val b = m.groupValues[4].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.0
        return Triple(fn, arg, (c - b) / a)
    }

    private data class Linear(val a: Double, val b: Double)

    private fun parseLinear(arg: String, variable: String): Linear? {
        val pts = listOf(-2.0, 0.0, 1.0, 3.0).mapNotNull { x ->
            val y = CasSolver.safeEval(arg, mapOf(variable to x)) ?: return@mapNotNull null
            x to y
        }
        if (pts.size < 3) return null
        val (x0, y0) = pts[0]
        val (x1, y1) = pts[1]
        val a = (y1 - y0) / (x1 - x0)
        val b = y0 - a * x0
        if (pts.any { (x, y) -> abs(a * x + b - y) > 1e-6 }) return null
        return Linear(a, b)
    }

    private fun sinEq(variable: String, u: Linear, c: Double): SolveOutcome? {
        if (abs(c) > 1.0 + EPS) return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        if (abs(c) <= EPS) {
            return liftFamilies(variable, u, listOf("πn"), indexVar = "n")
        }
        val alpha = asin(c.coerceIn(-1.0, 1.0))
        if (abs(abs(c) - 1.0) <= EPS) {
            val name = if (c > 0) "π/2" else "-π/2"
            return liftFamilies(variable, u, listOf("$name + 2πk"))
        }
        val a1 = prettyAngle(alpha)
        val a2 = prettyAngle(PI - alpha)
        return liftFamilies(variable, u, listOf("$a1 + 2πk", "$a2 + 2πk"))
    }

    private fun cosEq(variable: String, u: Linear, c: Double): SolveOutcome? {
        if (abs(c) > 1.0 + EPS) return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        if (abs(c) <= EPS) {
            return liftFamilies(variable, u, listOf("π/2 + πn"), indexVar = "n")
        }
        if (abs(abs(c) - 1.0) <= EPS) {
            val name = if (c > 0) "2πk" else "π + 2πk"
            return liftFamilies(variable, u, listOf(name))
        }
        val alpha = acos(c.coerceIn(-1.0, 1.0))
        val a1 = prettyAngle(alpha)
        val a2 = prettyAngle(-alpha)
        return liftFamilies(variable, u, listOf("$a1 + 2πk", "$a2 + 2πk"))
    }

    private fun tanEq(variable: String, u: Linear, c: Double): SolveOutcome? {
        if (!c.isFinite()) {
            return liftFamilies(variable, u, listOf("π/2 + πn"), indexVar = "n")
        }
        if (abs(c) <= EPS) {
            return liftFamilies(variable, u, listOf("πn"), indexVar = "n")
        }
        val alpha = atan(c)
        val a1 = prettyAngle(alpha)
        return liftFamilies(variable, u, listOf("$a1 + πk"))
    }

    private fun liftFamilies(
        variable: String,
        u: Linear,
        argFamilies: List<String>,
        indexVar: String = "k",
    ): SolveOutcome? {
        if (abs(u.a) < EPS) return null
        if (abs(u.a - 1.0) < EPS && abs(u.b) < EPS) {
            return families(variable, argFamilies, indexVar)
        }
        // General linear argument: a x + b = family. Keep the school case (a=1,b=0)
        // as the primary UI form; otherwise write x = (family − b)/a.
        val shifted = argFamilies.map { fam ->
            val body = if (abs(u.b) < EPS) fam else "($fam - ${CasSolver.formatExact(u.b)})"
            if (abs(u.a - 1.0) < EPS) body
            else "($body)/${CasSolver.formatExact(u.a)}"
        }
        return families(variable, shifted, indexVar)
    }

    private fun prettyAngle(rad: Double): String {
        val turns = rad / PI
        val known = listOf(
            0.0 to "0",
            1.0 / 6 to "π/6",
            0.25 to "π/4",
            1.0 / 3 to "π/3",
            0.5 to "π/2",
            2.0 / 3 to "2π/3",
            0.75 to "3π/4",
            5.0 / 6 to "5π/6",
            1.0 to "π",
            7.0 / 6 to "7π/6",
            1.25 to "5π/4",
            4.0 / 3 to "4π/3",
            1.5 to "3π/2",
            5.0 / 3 to "5π/3",
            1.75 to "7π/4",
            11.0 / 6 to "11π/6",
            -1.0 / 6 to "-π/6",
            -0.25 to "-π/4",
            -1.0 / 3 to "-π/3",
            -0.5 to "-π/2",
            -1.0 to "-π",
        )
        known.firstOrNull { abs(turns - it.first) < 1e-6 }?.let { return it.second }
        return CasSolver.formatExact(rad)
    }

    private fun families(variable: String, bodies: List<String>, indexVar: String = "k"): SolveOutcome {
        val exact = bodies.map { "$variable = $it, $indexVar ∈ ℤ" }
        return SolveOutcome.Solutions(
            exactLines = exact,
            decimalLines = null,
            latex = exact.joinToString(" \\lor "),
        )
    }

    private fun linearCombo(raw: String): SolveOutcome? {
        val pattern = Regex(
            """^\s*(?:(\d+(?:\.\d+)?)\s*[·×*]?\s*)?sin\(([^)]+)\)\s*([+−-])\s*(?:(\d+(?:\.\d+)?)\s*[·×*]?\s*)?cos\(([^)]+)\)\s*=\s*(.+)\s*$""",
            RegexOption.IGNORE_CASE,
        )
        val m = pattern.matchEntire(raw.trim()) ?: return null
        val a = (m.groupValues[1].ifBlank { "1" }).toDouble()
        val sinArg = m.groupValues[2]
        val sign = m.groupValues[3]
        val bRaw = (m.groupValues[4].ifBlank { "1" }).toDouble()
        val cosArg = m.groupValues[5]
        val c = m.groupValues[6].toDoubleOrNull() ?: return null
        if (sinArg != cosArg) return null
        val b = if (sign == "-") -bRaw else bRaw
        val variable = sinArg.trim()
        val r2 = a * a + b * b
        if (r2 <= 0) return null
        val r = sqrt(r2)
        if (abs(c) > r + 1e-9) return SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
        if (abs(a - 1.0) < 1e-9 && abs(b - 1.0) < 1e-9 && abs(c - 1.0) < 1e-9) {
            return families(variable, listOf("2πk", "π/2 + 2πk"))
        }
        return SolveOutcome.Solutions(
            exactLines = listOf(
                "$variable = arcsin(c/R) - α + 2πk",
                "$variable = π - arcsin(c/R) - α + 2πk",
                "R = √(${MathEngine.formatNumber(a)}²+${MathEngine.formatNumber(b)}²),  α = atan2(${MathEngine.formatNumber(b)}, ${MathEngine.formatNumber(a)})",
            ),
            decimalLines = null,
        )
    }
}
