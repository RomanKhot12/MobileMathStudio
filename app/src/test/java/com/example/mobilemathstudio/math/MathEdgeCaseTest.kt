package com.example.mobilemathstudio.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the engine against the whole "√55 = 5" family of bugs: implicit
 * multiplication must never split a literal, and the classic edge cases must
 * degrade gracefully instead of lying.
 */
class MathEdgeCaseTest {

    private fun value(expression: String): SolveOutcome.Value {
        val outcome = MathEngine.solve(expression)
        assertTrue("$expression → $outcome", outcome is SolveOutcome.Value)
        return outcome as SolveOutcome.Value
    }

    private fun solutions(expression: String): List<String> {
        val outcome = MathEngine.solve(expression)
        assertTrue("$expression → $outcome", outcome is SolveOutcome.Solutions)
        return (outcome as SolveOutcome.Solutions).exactLines
    }

    @Test
    fun squareRootOfFiftyFive() {
        val result = value("√(55)")
        assertEquals("√(55)", result.exact)
        assertTrue("decimal was ${result.decimal}", result.decimal!!.startsWith("7.4161"))
    }

    @Test
    fun bareRadicalWithoutParenthesesMatchesTheGroupedForm() {
        assertEquals(value("√(55)").decimal, value("√55").decimal)
    }

    @Test
    fun multiDigitLiteralsSurviveImplicitMultiplication() {
        assertEquals("55", value("55").exact)
        assertEquals("124", value("123+1").exact)
        assertEquals("144", value("12*12").exact)
        assertEquals("1024", value("2^10").exact)
        assertEquals("25", value("100/4").exact)
    }

    @Test
    fun perfectSquaresStayExact() {
        assertEquals("4", value("√(16)").exact)
        assertEquals("0", value("√(0)").exact)
    }

    @Test
    fun implicitMultiplicationStillWorksAcrossTokens() {
        assertTrue(solutions("12x=24").any { it.contains("2") })
        assertEquals("14", value("2(3+4)").exact)
        assertEquals("144", value("(6+6)(6+6)").exact)
    }

    @Test
    fun decimalSeparatorIsAlwaysADot() {
        assertEquals("3.5", MathEngine.formatNumber(3.5))
        assertTrue(value("√(2)").decimal!!.startsWith("1.41"))
    }

    @Test
    fun zeroAndOneBehave() {
        assertEquals("0", value("0").exact)
        assertEquals("1", value("1").exact)
        assertEquals("0", value("0*5").exact)
        assertEquals("1", value("5^0").exact)
        assertEquals("1", value("0!").exact)
    }

    @Test
    fun divisionByZeroDoesNotProduceANumber() {
        assertEquals("∞", value("1/0").exact)
        assertEquals("undefined", value("0/0").exact)
        assertEquals("undefined", value("0^0").exact)
    }

    @Test
    fun negativeRadicandGoesComplexNotSilentlyPositive() {
        val result = value("√(-4)")
        assertTrue("exact was ${result.exact}", result.exact.contains("i"))
        assertTrue("exact was ${result.exact}", !result.exact.contains("Complex"))
    }

    @Test
    fun logarithmEdges() {
        assertEquals("0", value("ln(1)").exact)
        assertEquals("2", value("log(100)").exact)
        assertEquals("-∞", value("ln(0)").exact)
    }

    @Test
    fun exponentPrecedenceAndNegatives() {
        assertEquals("-4", value("-2^2").exact)
        assertEquals("4", value("(-2)^2").exact)
        assertEquals("1/4", value("2^(-2)").exact)
    }

    @Test
    fun paradoxicalEquationsAreReportedNotSolved() {
        assertTrue(MathEngine.solve("x+1=x") is SolveOutcome.Info)
        val identity = MathEngine.solve("x=x")
        assertTrue("x=x → $identity", identity is SolveOutcome.Solutions)
        assertTrue((identity as SolveOutcome.Solutions).exactLines.any { it.contains("x ∈ (-∞, +∞)") })
        assertTrue(MathEngine.solve("x^2=-1") is SolveOutcome.Info)
        assertTrue(MathEngine.solve("√(x)=-3") is SolveOutcome.Info)
    }

    @Test
    fun quadraticRootsAreExact() {
        val lines = solutions("x^2-5x+6=0").joinToString()
        assertTrue(lines, lines.contains("2") && lines.contains("3"))
    }
}
