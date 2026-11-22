package com.example.mobilemathstudio.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two-storey fractions, mixed numbers and the digits behind a long answer. */
class AnswerMathTest {

    private fun render(node: com.example.mobilemathstudio.math.expr.MathDocument) = node.toLinear()

    @Test
    fun anImproperFractionGainsItsMixedForm() {
        val mixed = assertNotNull(AnswerMath.mixedOf("121/9")).let { AnswerMath.mixedOf("121/9")!! }
        assertEquals("13", mixed.whole)
        assertEquals("4", mixed.num)
        assertEquals("9", mixed.den)
    }

    @Test
    fun properFractionsAndIntegersStayAsTheyAre() {
        assertEquals(null, AnswerMath.mixedOf("4/9"))
        assertEquals(null, AnswerMath.mixedOf("18/9"))
        assertEquals(null, AnswerMath.mixedOf("13"))
    }

    @Test
    fun negativeFractionsConvertOnTheirMagnitude() {
        val mixed = AnswerMath.mixedOf("-11/9")!!
        assertEquals("1", mixed.whole)
        assertEquals("2", mixed.num)
        assertEquals("9", mixed.den)
    }

    @Test
    fun theValueBecomesARealFractionNodeNotASlash() {
        val document = AnswerMath.document("121/9", mixed = true)
        // linearize re-parenthesises a Fraction node, which a plain text run never is.
        assertEquals("(121)/(9) = 13(4)/(9)", render(document))
    }

    @Test
    fun withoutMixedModeOnlyTheFractionIsDrawn() {
        assertEquals("(121)/(9)", render(AnswerMath.document("121/9", mixed = false)))
    }

    @Test
    fun aDecimalValueIsLeftAlone() {
        assertEquals("13.4444444444", render(AnswerMath.document("13.4444444444", mixed = true)))
    }

    @Test
    fun fractionsEmbeddedInALongerValueAreStillStacked() {
        assertEquals("2+(1)/(3)", render(AnswerMath.document("2+1/3", mixed = true)))
    }

    @Test
    fun answersSplitIntoLabelledLines() {
        val lines = AnswerMath.split("x = 121/9\n\ny = -11/9")
        assertEquals(2, lines.size)
        assertEquals(AnswerLine("x", "121/9"), lines[0])
        assertEquals(AnswerLine("y", "-11/9"), lines[1])
    }

    @Test
    fun aColumnAnswerKeepsTheLabelAndTheMixedForm() {
        val document = AnswerMath.lineDocument(AnswerLine("x", "121/9"), mixed = true)
        assertEquals("x = (121)/(9) = 13(4)/(9)", render(document))
    }

    @Test
    fun piOverTwoBecomesAStackedFraction() {
        assertEquals("(π)/(2)", render(AnswerMath.document("π/2", mixed = true)))
    }

    @Test
    fun inspectSourcePicksTheWholeConstantButFallsBackToPiInsideAFamily() {
        assertEquals("121/9", AnswerMath.inspectSource("121/9"))
        assertEquals("π", AnswerMath.inspectSource("π"))
        assertEquals("π/2", AnswerMath.inspectSource("π/2"))
        assertEquals("√2", AnswerMath.inspectSource("√2"))
        assertEquals("π", AnswerMath.inspectSource("2πk"))
        assertEquals("13.444444444444", AnswerMath.inspectSource("13.444444444444"))
        assertEquals(null, AnswerMath.inspectSource("exp"))
    }

    @Test
    fun shortAndExactValuesAreNotClickable() {
        assertFalse(AnswerMath.isInspectable("13"))
        assertFalse(AnswerMath.isInspectable("1/2"))
        assertFalse(AnswerMath.isInspectable("0.125"))
        assertFalse(AnswerMath.isInspectable("∞"))
        assertFalse(AnswerMath.isInspectable("2i"))
        assertFalse(AnswerMath.isInspectable(""))
    }

    @Test
    fun longDivisionKeepsProducingDigits() {
        val digits = DecimalExpansion.of("121/9", 40)!!
        assertEquals("13", digits.whole)
        assertEquals(40, digits.fraction.length)
        assertTrue(digits.fraction.all { it == '4' })
        assertFalse(digits.complete)
        assertEquals(400, DecimalExpansion.of("121/9", 400)!!.fraction.length)
    }

    @Test
    fun aTerminatingDecimalReportsThatItIsDone() {
        val digits = DecimalExpansion.of("1/8", 40)!!
        assertEquals("0", digits.whole)
        assertEquals("125", digits.fraction)
        assertTrue(digits.complete)
        assertEquals("0.125", digits.render())
    }

    @Test
    fun negativeValuesKeepTheirSign() {
        val digits = DecimalExpansion.of("-11/9", 6)!!
        assertTrue(digits.negative)
        assertEquals("-1.222222", digits.render())
    }

    @Test
    fun seventhsRepeatWithTheClassicPeriod() {
        assertEquals("142857142857", DecimalExpansion.of("1/7", 12)!!.fraction)
    }

    @Test
    fun irrationalsComeFromTheCasWithManyDigits() {
        val pi = DecimalExpansion.of("π", 60)
        assertNotNull("CAS did not expand π: ${SymjaEngine.highPrecision("π", 20)}", pi)
        assertEquals("3", pi!!.whole)
        assertTrue(pi.render(), pi.render().startsWith("3.14159265"))
        assertFalse(pi.complete)

        val root = DecimalExpansion.of("√2", 30)
        assertNotNull("CAS did not expand √2: ${SymjaEngine.highPrecision("√2", 20)}", root)
        assertTrue(root!!.render(), root.render().startsWith("1.4142135623"))
    }

    @Test
    fun nonNumericValuesSimplyHaveNoExpansion() {
        assertEquals(null, DecimalExpansion.of("∞", 10))
        assertEquals(null, DecimalExpansion.of("k", 10))
    }
}
