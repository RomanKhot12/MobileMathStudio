package com.example.mobilemathstudio.math.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertExponentTest {

    @Test
    fun longPressDigitRaisesTheNumberBeforeTheCaret() {
        var doc = MathEditor.insertText(MathDocument.empty(), "4")
        doc = MathEditor.insertExponent(doc, "8")
        assertTrue(doc.root.children.single() is MathNode.Power)
        assertEquals("4^(8)", doc.toLinear())
    }

    @Test
    fun longPressAfterARunOfDigitsRaisesTheWholeRun() {
        var doc = MathEditor.insertText(MathDocument.empty(), "45")
        doc = MathEditor.insertExponent(doc, "2")
        val pow = doc.root.children.single() as MathNode.Power
        assertEquals("45", (pow.base as MathNode.Text).value)
        assertEquals("2", (pow.exp as MathNode.Text).value)
        assertEquals("(45)^(2)", doc.toLinear())
    }

    @Test
    fun longPressAfterAVariableRaisesThatLetter() {
        var doc = MathEditor.insertText(MathDocument.empty(), "2x")
        doc = MathEditor.insertExponent(doc, "3")
        assertEquals("2x^(3)", doc.toLinear())
    }

    @Test
    fun longPressOnEmptyBaseLeavesAHole() {
        val doc = MathEditor.insertExponent(MathDocument.empty(), "5")
        assertEquals("^(5)", doc.toLinear())
        assertTrue((doc.root.children.single() as MathNode.Power).base is MathNode.Hole)
    }
}
