package com.example.mobilemathstudio.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CompiledExpressionTest {

    private fun eval(src: String, vararg vars: Pair<String, Double>) =
        MathEngine.evaluate(src, mapOf(*vars))

    @Test
    fun grammarMatchesTheInterpreter() {
        assertEquals(14.0, eval("2+3*4"), 1e-12)
        assertEquals(20.0, eval("(2+3)4"), 1e-12)
        assertEquals(18.0, eval("9x", "x" to 2.0), 1e-12)
        assertEquals(-9.0, eval("-3^2"), 1e-12)
        assertEquals(9.0, eval("(-3)^2"), 1e-12)
        assertEquals(0.5, eval("2^-1"), 1e-12)
        assertEquals(-1.0, eval("2-3"), 1e-12)
        assertEquals(-6.0, eval("2*-3"), 1e-12)
        assertEquals(-4.0, eval("-x^2", "x" to 2.0), 1e-12)
        assertEquals(4.0, eval("|-4|"), 1e-12)
        assertEquals(3.0, eval("√9"), 1e-12)
        assertEquals(6.0, eval("2√(9)"), 1e-12)
        assertEquals(120.0, eval("5!"), 1e-12)
        assertEquals(0.5, eval("50%"), 1e-12)
        assertEquals(2.5, eval("2,5"), 1e-12)
        assertEquals(Math.PI * 2, eval("2π"), 1e-12)
        assertEquals(1.0, eval("ln(e)"), 1e-12)
        assertEquals(2.0, eval("log(100)"), 1e-12)
        assertEquals(7.0, eval("X+5", "x" to 2.0), 1e-12)
    }

    @Test
    fun domainErrorsStillThrowForTheSolver() {
        for (src in listOf("1/0", "√(-4)", "sqrt(-1)", "(-1)!", "1/(x-2)")) {
            try {
                eval(src, "x" to 2.0)
                fail("$src should throw")
            } catch (_: ArithmeticException) {
            }
        }
    }

    @Test
    fun syntaxErrorsCarryTheirPosition() {
        try {
            eval("2+(3")
            fail()
        } catch (e: ParseException) {
            assertEquals(2, e.index)
        }
        try {
            eval("foo(2)")
            fail()
        } catch (e: ParseException) {
            assertTrue(e.message!!.contains("foo"))
        }
        try {
            eval("q+1")
            fail()
        } catch (e: ParseException) {
            assertTrue(e.message!!.contains("q"))
        }
    }

    @Test
    fun plottingPathNeverThrows() {
        val c = CompiledExpression.compile("1/x + √(x) + ln(x) + q")
        assertTrue(c.evaluateOrNaN(mapOf("x" to 0.0)).isNaN())
        assertTrue(c.evaluateOrNaN(mapOf("x" to -1.0)).isNaN())
        assertTrue(CompiledExpression.compile("x^2").evaluateOrNaN(mapOf("x" to 3.0)) == 9.0)
    }

    @Test
    fun compiledTreesAreCachedAndReusable() {
        val a = CompiledExpression.compile("x^2-4x+4")
        val b = CompiledExpression.compile("x^2-4x+4")
        assertTrue(a === b)
        for (x in -5..5) assertEquals((x - 2.0) * (x - 2.0), a.evaluate(mapOf("x" to x.toDouble())), 1e-9)
    }
}
