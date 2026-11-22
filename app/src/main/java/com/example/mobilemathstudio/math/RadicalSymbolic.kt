package com.example.mobilemathstudio.math

import kotlin.math.abs
import kotlin.math.pow

/**
 * Handles √A = B / sqrt(A) = B / A^(1/n) = B with ODZ:
 * A ≥ 0 and B ≥ 0 (principal even root), then A = B^n.
 * Example: √(2x+5) = x-5 → x = 10 (x = 2 rejected).
 */
object RadicalSymbolic {
    private val pattern = Regex(
        """^\s*(?:√|sqrt|Sqrt)\s*\((.+)\)\s*=\s*(.+)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val patternBare = Regex(
        """^\s*√\s*([A-Za-z]\w*|\d+(?:\.\d+)?)\s*=\s*(.+)\s*$""",
    )
    private val patternPowerRoot = Regex(
        """^\s*(.+)\^\(1/(\d+)\)\s*=\s*(.+)\s*$""",
    )
    private val patternPowerRootParen = Regex(
        """^\s*(.+)\^\(1/\((.+)\)\)\s*=\s*(.+)\s*$""",
    )

    fun solve(raw: String): SolveOutcome? {
        val compact = CasSolver.compactExpr(raw)
        match(compact)?.let { (under, rhs, n) -> return solveRoot(under, rhs, n) }
        match(raw.trim())?.let { (under, rhs, n) -> return solveRoot(under, rhs, n) }
        return null
    }

    private data class RootEq(val under: String, val rhs: String, val n: Int)

    private fun match(text: String): RootEq? {
        pattern.matchEntire(text)?.let {
            return RootEq(it.groupValues[1], it.groupValues[2], 2)
        }
        patternBare.matchEntire(text)?.let {
            return RootEq(it.groupValues[1], it.groupValues[2], 2)
        }
        patternPowerRoot.matchEntire(text)?.let {
            val n = it.groupValues[2].toIntOrNull() ?: return@let
            if (n >= 2) return RootEq(it.groupValues[1], it.groupValues[3], n)
        }
        patternPowerRootParen.matchEntire(text)?.let {
            val n = it.groupValues[2].toIntOrNull() ?: return@let
            if (n >= 2) return RootEq(it.groupValues[1], it.groupValues[3], n)
        }
        return null
    }

    private fun solveRoot(under: String, rhs: String, n: Int): SolveOutcome {
        val variable = MathEngine.collectUnknowns("$under+$rhs").firstOrNull() ?: "x"
        val zero = "($under)-(($rhs)^$n)"
        val candidates = CasSolver.numericRoots(zero, variable)

        val valid = candidates.filter { x ->
            try {
                val a = MathEngine.evaluate(under, mapOf(variable to x))
                val b = MathEngine.evaluate(rhs, mapOf(variable to x))
                if (a < -1e-9) return@filter false
                if (n % 2 == 0 && b < -1e-9) return@filter false
                val principal = a.coerceAtLeast(0.0).pow(1.0 / n)
                abs(principal - b) < 1e-6 || (n % 2 == 1 && abs(-principal - b) < 1e-6)
            } catch (_: Throwable) {
                false
            }
        }.distinctBy { MathEngine.formatNumber(it) }

        return when {
            valid.isEmpty() -> SolveOutcome.Info(SolveMessages.NO_SOLUTIONS)
            else -> {
                val exact = valid.map { "$variable = ${CasSolver.formatExact(it)}" }
                val dec = valid.map { "$variable = ${MathEngine.formatNumber(it)}" }
                SolveOutcome.Solutions(exactLines = exact, decimalLines = dec)
            }
        }
    }
}
