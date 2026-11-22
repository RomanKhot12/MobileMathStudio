package com.example.mobilemathstudio.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

/**
 * Required CAS overhaul suite: every expression from the product checklist
 * must solve (or fail) with the right status — never a false "No solutions".
 */
class CasOverhaulTest {

    private fun outcome(expr: String): SolveOutcome = MathEngine.solve(expr)

    private fun solutions(expr: String): SolveOutcome.Solutions {
        val result = outcome(expr)
        assertTrue("$expr → $result", result is SolveOutcome.Solutions)
        return result as SolveOutcome.Solutions
    }

    private fun info(expr: String): String {
        val result = outcome(expr)
        assertTrue("$expr → $result", result is SolveOutcome.Info || result is SolveOutcome.Error)
        return when (result) {
            is SolveOutcome.Info -> result.message
            is SolveOutcome.Error -> result.message
            else -> error("unreachable")
        }
    }

    private fun valuesOf(expr: String): List<Double> {
        val solved = solutions(expr)
        val lines = solved.exactLines + (solved.decimalLines ?: emptyList())
        return lines.flatMap { parseNumbers(it) }.distinctBy { MathEngine.formatNumber(it) }
    }

    private fun parseNumbers(line: String): List<Double> {
        val out = mutableListOf<Double>()
        val regex = Regex("""=\s*([+-]?\d+(?:\.\d+)?(?:/\d+)?)""")
        for (m in regex.findAll(line)) {
            val t = m.groupValues[1]
            val v = if ('/' in t) {
                val p = t.split('/')
                p[0].toDouble() / p[1].toDouble()
            } else {
                t.toDouble()
            }
            out += v
        }
        return out
    }

    private fun assertRoots(expr: String, vararg expected: Double, tolerance: Double = 1e-6) {
        val got = valuesOf(expr)
        for (e in expected) {
            assertTrue(
                "$expr expected $e, got $got from ${outcome(expr)}",
                got.any { abs(it - e) < tolerance },
            )
        }
        assertEquals(
            "$expr extra roots $got (expected ${expected.toList()}) from ${outcome(expr)}",
            expected.size,
            got.size,
        )
    }

    private fun assertFamily(expr: String, vararg needles: String) {
        val text = solutions(expr).exactLines.joinToString("\n")
        for (n in needles) {
            assertTrue("$expr missing `$n` in:\n$text", text.contains(n))
        }
        assertTrue("$expr must carry period 2πk:\n$text", text.contains("2πk") || text.contains("2π k"))
    }

    // ── Basic / intermediate ──────────────────────────────────────────

    @Test fun linearShift() = assertRoots("x + 5 = 12", 7.0)

    @Test fun linearScaled() = assertRoots("2x - 3 = 7", 5.0)

    @Test fun linearDivision() = assertRoots("x / 4 = 3", 12.0)

    @Test fun twoSidedLinear() = assertRoots("5x + 3 = 2x - 9", -4.0)

    @Test fun pureSquare() = assertRoots("x^2 = 16", 4.0, -4.0)

    @Test fun quadraticFactoredPoly() = assertRoots("x^2 - 5x + 6 = 0", 2.0, 3.0)

    @Test fun productOfBrackets() = assertRoots("(x + 2)(x - 3) = 0", -2.0, 3.0)

    @Test fun expandedLinear() = assertRoots("3(x - 4) + 5 = 2(2x + 1)", -9.0)

    @Test fun rationalWithOdz() {
        assertRoots("(2x + 3) / (x - 1) = 4", 3.5)
        val text = solutions("(2x + 3) / (x - 1) = 4").exactLines.joinToString()
        assertFalse("pole x=1 must be excluded: $text", text.contains("= 1") && !text.contains("3.5") && !text.contains("7/2"))
    }

    @Test fun cubicRealRoot() = assertRoots("x^3 - 8 = 0", 2.0)

    @Test fun sqrtOdz() {
        assertRoots("sqrt(x + 5) = 4", 11.0)
        assertRoots("√(x + 5) = 4", 11.0)
    }

    @Test fun linearSystem2x2() {
        val result = MathEngine.solveSystem(listOf("x + y = 10", "x - y = 2"))
        assertTrue("$result", result is SolveOutcome.Solutions)
        val text = (result as SolveOutcome.Solutions).exactLines.joinToString("\n")
        val xs = result.exactLines.flatMap { parseNumbers(it) }
        assertTrue("x=6 y=4 from $text", xs.any { abs(it - 6.0) < 1e-6 } && xs.any { abs(it - 4.0) < 1e-6 })
        assertTrue(text.contains("x") && text.contains("y"))
    }

    // ── Advanced / stress ─────────────────────────────────────────────

    @Test fun sineHalf() {
        assertFamily("sin(x) = 0.5", "π/6", "5π/6")
        assertFamily("sin(x) = 1/2", "π/6", "5π/6")
    }

    @Test fun cosineDoubleAngle() {
        assertFamily("cos(2x) - sin(x) = 0", "π/6", "5π/6", "-π/2")
    }

    @Test fun logSumDropsExtraneous() {
        val got = valuesOf("log(x) + log(x - 3) = 1")
        assertTrue("expected x=5, got $got from ${outcome("log(x) + log(x - 3) = 1")}", got.any { abs(it - 5.0) < 1e-6 })
        assertFalse("extraneous x=-2 must be dropped: $got", got.any { abs(it + 2.0) < 1e-6 })
        assertEquals(1, got.size)
    }

    @Test fun naturalLog() {
        val result = solutions("ln(x) = 2")
        val text = result.exactLines.joinToString()
        val nums = valuesOf("ln(x) = 2") + (result.decimalLines?.flatMap { parseNumbers(it) } ?: emptyList())
        val e2 = exp(2.0)
        assertTrue(
            "ln(x)=2 → e^2, got $text / $nums",
            text.contains("e^2") || text.contains("E^2") || nums.any { abs(it - e2) < 1e-3 },
        )
        assertTrue(nums.isEmpty() || nums.any { abs(it - e2) < 1e-3 } || text.contains("e^2"))
    }

    @Test fun exponential() = assertRoots("2^(x+1) = 16", 3.0)

    @Test fun coshIdentity() = assertRoots("e^x + e^(-x) = 2", 0.0)

    @Test fun absoluteValue() = assertRoots("|2x - 5| = 3", 1.0, 4.0)

    @Test fun divisionByZeroEquation() {
        val message = info("x / 0 = 5")
        assertTrue(
            "x/0=5 → $message",
            message.contains("zero", ignoreCase = true) || message.contains(SolveMessages.NO_SOLUTIONS),
        )
        assertFalse("must not invent a root: $message", message.contains("x ="))
    }

    @Test fun noRealRoots() {
        val message = info("x^2 + 1 = 0")
        assertTrue(
            "x^2+1=0 → $message",
            message.contains("real", ignoreCase = true) ||
                message.contains("i") ||
                message.contains(SolveMessages.NO_SOLUTIONS),
        )
        val result = outcome("x^2 + 1 = 0")
        if (result is SolveOutcome.Solutions) {
            val text = result.exactLines.joinToString()
            assertTrue("complex form must mention i: $text", text.contains("i"))
        }
    }

    @Test fun pythagoreanIdentity() {
        val text = solutions("sin(x)^2 + cos(x)^2 = 1").exactLines.joinToString("\n")
        assertTrue("identity → $text", text.contains("x ∈ (-∞, +∞)"))
        val textPow = solutions("sin^2(x) + cos^2(x) = 1").exactLines.joinToString("\n")
        assertTrue("sin^2 identity → $textPow", textPow.contains("x ∈ (-∞, +∞)"))
    }

    @Test fun contradiction() {
        val message = info("x = x + 1")
        assertTrue(message.contains(SolveMessages.NO_SOLUTIONS) || message.contains("No solutions"))
        assertFalse(outcome("x = x + 1") is SolveOutcome.Solutions)
    }

    @Test fun radicalStillRejectsExtraneous() {
        val got = valuesOf("√(2x+5)=x-5")
        assertTrue(got.any { abs(it - 10.0) < 1e-6 })
        assertFalse(got.any { abs(it - 2.0) < 1e-6 })
    }
}
