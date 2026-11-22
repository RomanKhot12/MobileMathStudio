package com.example.mobilemathstudio.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InequalityDomainSolverTest {

    private fun text(expr: String): String {
        val result = MathEngine.solve(expr)
        assertTrue("$expr → $result", result is SolveOutcome.Solutions)
        return (result as SolveOutcome.Solutions).exactLines.joinToString("\n")
    }

    @Test
    fun linearStrictInequalityGivesARay() {
        val out = text("2x - 4 > 0")
        assertTrue(out, out.contains("x > 2"))
    }

    @Test
    fun quadraticClosedInequalityGivesASegment() {
        val out = text("x^2 - 9 ≤ 0")
        assertTrue(out, out.contains("x ∈ [-3, 3]") || (out.contains("-3") && out.contains("3")))
    }

    @Test
    fun domainOfRootAndPole() {
        val out = text("1/(x-3)+√x")
        assertTrue(out, out.contains("∈"))
        assertTrue(out, out.contains("0"))
        assertTrue(out, out.contains("3"))
        assertTrue(out, out.contains("∪"))
    }

    @Test
    fun sqrtDomainIsNonNegative() {
        val out = text("√x")
        assertTrue(out, out.contains("x ≥ 0") || out.contains("x ∈ [0"))
    }

    @Test
    fun equationsAreLeftToTheCas() {
        assertNull(InequalityDomainSolver.solve("2x - 4 = 0"))
        val solved = MathEngine.solve("2x - 4 = 0")
        assertTrue(solved is SolveOutcome.Solutions)
    }

    @Test
    fun plainLinearWithoutRelationIsNotClaimedAsDomain() {
        assertNull(InequalityDomainSolver.solve("2x+1"))
    }

    @Test
    fun splitFindsUnicodeInequalities() {
        val rel = InequalityDomainSolver.splitRelation("x^2-9≤0")
        assertEquals("≤", rel!!.op)
        assertEquals("x^2-9", rel.left)
        assertEquals("0", rel.right)
    }

    @Test
    fun identitiesReturnFullRealLineWithOdzHolesInsteadOfNoSolutions() {
        assertEquals("x ∈ (-∞, +∞)", text("sin^2(x) + cos^2(x) = 1"))
        assertEquals("x ∈ (-∞, +∞)", text("x - x = 0"))
        assertEquals("x ∈ (-∞, +∞)", text("x^2 >= 0"))
        assertEquals("x ∈ (-∞, 0) ∪ (0, +∞)", text("x / x = 1"))
        assertEquals("x ∈ (-∞, 1) ∪ (1, +∞)", text("(x^2 - 1)/(x - 1) = x + 1"))
        assertEquals("x ∈ [0, +∞)", text("√(x) = √(x)"))
        assertEquals("x ∈ (0, +∞)", text("ln(x) = ln(x)"))
    }

    @Test
    fun equationsAndInequalitiesWithIntervalSolutionsUseStrictMathematicalNotation() {
        assertEquals("x ∈ [0, +∞)", text("|x| = x"))
        assertEquals("x ∈ [0, 2]", text("|x| + |x - 2| = 2"))
        assertEquals("x ∈ (-∞, -2) ∪ [5, +∞)", text("(x - 5)/(x + 2) >= 0"))
        assertEquals("x ∈ [-2, 4)", text("-2 ≤ x < 4"))

        val sys = MathEngine.solveSystem(listOf("x > 1", "x ≤ 5"))
        assertTrue("$sys", sys is SolveOutcome.Solutions)
        assertEquals("x ∈ (1, 5]", (sys as SolveOutcome.Solutions).exactLines.single())
    }

    @Test
    fun periodicTrigZerosOutputInfiniteSeriesInsteadOfNoSolutions() {
        val sinZero = text("sin(x) = 0")
        assertTrue("sin(x)=0 → $sinZero", sinZero.contains("πn") && sinZero.contains("n ∈ ℤ"))

        val sinZeroRev = text("0 = sin(x)")
        assertTrue("0=sin(x) → $sinZeroRev", sinZeroRev.contains("πn") && sinZeroRev.contains("n ∈ ℤ"))

        val scaledSin = text("2sin(x) = 0")
        assertTrue("2sin(x)=0 → $scaledSin", scaledSin.contains("πn") && scaledSin.contains("n ∈ ℤ"))

        val cosZero = text("cos(x) = 0")
        assertTrue("cos(x)=0 → $cosZero", cosZero.contains("π/2") && cosZero.contains("πn") && cosZero.contains("ℤ"))
    }
}
