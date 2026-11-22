package com.example.mobilemathstudio.math.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ✓ must leave a nested power before jumping to the next fraction slot. */
class ConfirmSlotNestedTest {

    @Test
    fun powerInsideNumeratorReturnsToTheNumeratorFirst() {
        var doc = MathEditor.insertFraction(MathDocument.empty())
        doc = MathEditor.insertPower(doc)
        doc = MathEditor.insertText(doc, "x")
        doc = MathEditor.confirmSlot(doc)
        doc = MathEditor.insertText(doc, "2")
        assertEquals("(x^(2))/()", doc.toLinear())
        assertEquals(listOf(0, 0, 1), doc.caret.path)

        doc = MathEditor.confirmSlot(doc)
        assertNotEquals("jumped to the denominator too early", listOf(0, 1), doc.caret.path)
        assertEquals(listOf(0, 0, 1), doc.caret.path)

        doc = MathEditor.insertText(doc, "+1")
        assertEquals("(x^(2)+1)/()", doc.toLinear())

        doc = MathEditor.confirmSlot(doc)
        assertEquals(listOf(0, 1), doc.caret.path)
    }

    @Test
    fun aBareFractionStillMovesFromNumeratorToDenominator() {
        var doc = MathEditor.insertFraction(MathDocument.empty())
        doc = MathEditor.insertText(doc, "1")
        doc = MathEditor.confirmSlot(doc)
        assertEquals(listOf(0, 1), doc.caret.path)
        assertEquals("(1)/()", doc.toLinear())
    }

    @Test
    fun powerInsideDenominatorStaysInTheDenominator() {
        var doc = MathEditor.insertFraction(MathDocument.empty())
        doc = MathEditor.insertText(doc, "1")
        doc = MathEditor.confirmSlot(doc)
        doc = MathEditor.insertPower(doc)
        doc = MathEditor.insertText(doc, "x")
        doc = MathEditor.confirmSlot(doc)
        doc = MathEditor.insertText(doc, "2")

        doc = MathEditor.confirmSlot(doc)
        assertTrue(doc.caret.path.size >= 2)
        assertEquals(1, doc.caret.path[1])
        doc = MathEditor.insertText(doc, "+3")
        assertTrue(doc.toLinear(), doc.toLinear().contains("x^(2)+3"))
        assertTrue(doc.toLinear().startsWith("(1)/"))
    }
}
