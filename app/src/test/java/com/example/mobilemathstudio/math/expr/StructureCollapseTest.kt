package com.example.mobilemathstudio.math.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blur-time cleanup. A structure the user started filling keeps its remaining
 * holes as usable input slots; only a wholly untouched one is thrown away.
 */
class StructureCollapseTest {

    private fun collapsed(doc: MathDocument) = MathEditor.collapseEmpty(doc).toLinear()

    @Test
    fun aHalfFilledFractionKeepsItsEmptySlot() {
        val doc = MathDocument(
            root = MathNode.Row(
                listOf(MathNode.Fraction(MathNode.Text("1"), MathNode.Hole())),
            ),
        )
        assertEquals("(1)/()", collapsed(doc))
    }

    @Test
    fun anUntouchedFractionIsDiscarded() {
        val doc = MathDocument(
            root = MathNode.Row(listOf(MathNode.Fraction(MathNode.Hole(), MathNode.Hole()))),
        )
        assertTrue(MathEditor.collapseEmpty(doc).isBlank())
    }

    @Test
    fun aHalfFilledPowerKeepsItsExponentSlot() {
        val doc = MathDocument(
            root = MathNode.Row(listOf(MathNode.Power(MathNode.Text("x"), MathNode.Hole()))),
        )
        assertEquals("x^()", collapsed(doc))
    }

    @Test
    fun anEmptyRootIsDiscardedButAFilledIndexSurvives() {
        val empty = MathDocument(
            root = MathNode.Row(listOf(MathNode.Root(null, MathNode.Hole()))),
        )
        assertTrue(MathEditor.collapseEmpty(empty).isBlank())

        val indexed = MathDocument(
            root = MathNode.Row(listOf(MathNode.Root(MathNode.Text("3"), MathNode.Hole()))),
        )
        assertEquals("()^(1/(3))", collapsed(indexed))
    }

    @Test
    fun survivingSlotsCanBeAddressedAndFilled() {
        val doc = MathEditor.collapseEmpty(
            MathDocument(
                root = MathNode.Row(
                    listOf(MathNode.Fraction(MathNode.Text("1"), MathNode.Hole())),
                ),
            ),
        )
        assertTrue(MathEditor.isSlotEmpty(doc, listOf(0, 1)))

        val filled = MathEditor.replaceNodeAt(doc, listOf(0, 1), MathNode.Text("2"))
        assertEquals("(1)/(2)", filled.toLinear())
    }
}
