package com.example.mobilemathstudio.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Systems used to fail wholesale because Symja's `size()` includes the head,
 * so every argument walk ran one index past the end and threw.
 */
class SystemSolverTest {

    private fun solve(vararg equations: String): SolveOutcome.Solutions {
        val outcome = MathEngine.solveSystem(equations.toList())
        assertTrue("${equations.toList()} → $outcome", outcome is SolveOutcome.Solutions)
        return outcome as SolveOutcome.Solutions
    }

    @Test
    fun independentVariablesWithConstantsOnBothSides() {
        val result = solve("55 - 66 = 9x", "55 - 66 = 9y")
        assertEquals(listOf("x = -11/9", "y = -11/9"), result.exactLines)
        val decimals = assertNotNull(result.decimalLines).let { result.decimalLines!! }
        assertTrue(decimals.toString(), decimals.all { it.contains("-1.2222") })
    }

    @Test
    fun eachVariableGetsItsOwnLine() {
        val lines = solve("x+y+z=6", "x-y=0", "z=2").exactLines
        assertEquals(listOf("x = 2", "y = 2", "z = 2"), lines)
        assertTrue("no line may pack two variables", lines.none { it.contains(",") })
    }

    @Test
    fun classicCoupledSystem() {
        val lines = solve("x+y=5", "x-y=1").exactLines
        assertEquals(listOf("x = 3", "y = 2"), lines)
    }

    @Test
    fun implicitCoefficientsAndNegativeConstants() {
        val lines = solve("2x+3y=13", "x-y=-1").exactLines
        assertEquals(listOf("x = 2", "y = 3"), lines)
    }

    @Test
    fun contradictorySystemReportsNoSolutions() {
        val outcome = MathEngine.solveSystem(listOf("x+y=1", "x+y=2"))
        assertTrue("$outcome", outcome is SolveOutcome.Info)
    }

    @Test
    fun dependentSystemIsNotAnError() {
        val outcome = MathEngine.solveSystem(listOf("x+y=1", "2x+2y=2"))
        assertTrue("$outcome", outcome !is SolveOutcome.Error)
    }

    @Test
    fun everyEquationCountIsHandledWithoutErrors() {
        listOf(
            listOf("x=2", "y=3"),
            listOf("9x=-11", "9y=-11"),
            listOf("x/2+y=4", "x-y=1"),
            listOf("√(4)x=8", "y=1"),
        ).forEach { system ->
            val outcome = MathEngine.solveSystem(system)
            assertTrue("$system → $outcome", outcome !is SolveOutcome.Error)
        }
    }

    @Test
    fun tooFewEquationsIsRejectedCleanly() {
        val outcome = MathEngine.solveSystem(listOf("x+y=5"))
        assertTrue("$outcome", outcome is SolveOutcome.Error)
    }
}
