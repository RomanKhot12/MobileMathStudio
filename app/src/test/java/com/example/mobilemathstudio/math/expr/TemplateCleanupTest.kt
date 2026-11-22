package com.example.mobilemathstudio.math.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backspace removes a 2D template only when every slot is empty.
 * Long-press deletion ([MathEditor.deleteEnclosingTemplate]) drops it even if
 * sibling slots still hold content.
 */
class TemplateCleanupTest {

    @Test
    fun backspaceRemovesAWhollyEmptyFraction() {
        var doc = MathEditor.insertFraction(MathDocument.empty())
        assertEquals("()/()", doc.toLinear())
        assertTrue(MathEditor.isTemplateBlank(doc.root.children.single()))

        doc = MathEditor.backspace(doc)
        assertTrue(doc.isBlank())
        assertEquals(listOf(0), doc.caret.path)
    }

    @Test
    fun backspaceInAnEmptySlotKeepsAPartiallyFilledFraction() {
        var doc = MathEditor.insertFraction(MathDocument.empty())
        doc = MathEditor.insertText(doc, "1")
        doc = MathEditor.confirmSlot(doc)
        assertEquals(listOf(0, 1), doc.caret.path)

        val after = MathEditor.backspace(doc)
        assertEquals("(1)/()", after.toLinear())
        assertTrue(after.root.children.single() is MathNode.Fraction)
        assertEquals(listOf(0, 1), after.caret.path)
    }

    @Test
    fun emptyingTheLastFilledSlotThenBackspaceDropsTheFraction() {
        var doc = MathEditor.insertFraction(MathDocument.empty())
        doc = MathEditor.insertText(doc, "1")
        doc = MathEditor.backspace(doc)
        assertEquals("()/()", doc.toLinear())
        assertTrue(doc.root.children.single() is MathNode.Fraction)

        doc = MathEditor.backspace(doc)
        assertTrue(doc.isBlank())
    }

    @Test
    fun backspaceRemovesEmptyPowerAndRoot() {
        var power = MathEditor.insertPower(MathDocument.empty())
        power = MathEditor.backspace(power)
        assertTrue(power.isBlank())

        var sqrt = MathEditor.insertSqrt(MathDocument.empty())
        sqrt = MathEditor.backspace(sqrt)
        assertTrue(sqrt.isBlank())

        var nth = MathEditor.insertNthRoot(MathDocument.empty())
        nth = MathEditor.backspace(nth)
        assertTrue(nth.isBlank())
    }

    @Test
    fun backspaceInAnEmptyExponentKeepsAFilledBase() {
        var doc = MathEditor.insertPower(MathDocument.empty())
        doc = MathEditor.insertText(doc, "x")
        doc = MathEditor.confirmSlot(doc)

        val after = MathEditor.backspace(doc)
        assertEquals("x^()", after.toLinear())
        assertTrue(after.root.children.single() is MathNode.Power)
    }

    @Test
    fun nestedEmptyFractionIsRemovedWithoutTouchingTheOuterOne() {
        val doc = MathDocument(
            root = MathNode.Row(
                listOf(
                    MathNode.Fraction(
                        MathNode.Text("1"),
                        MathNode.Fraction(MathNode.Hole(), MathNode.Hole()),
                    ),
                ),
            ),
            caret = MathCaret(path = listOf(0, 1, 0), offset = 0),
        )

        val after = MathEditor.backspace(doc)
        assertEquals("(1)/()", after.toLinear())
        val outer = after.root.children.single()
        assertTrue(outer is MathNode.Fraction)
        assertFalse((outer as MathNode.Fraction).den is MathNode.Fraction)
    }

    @Test
    fun deleteEnclosingTemplateDropsAPartiallyFilledStructure() {
        var doc = MathEditor.insertFraction(MathDocument.empty())
        doc = MathEditor.insertText(doc, "1")
        doc = MathEditor.confirmSlot(doc)
        assertEquals("(1)/()", doc.toLinear())

        doc = MathEditor.deleteEnclosingTemplate(doc)
        assertTrue(doc.isBlank())
        assertEquals(listOf(0), doc.caret.path)
    }

    @Test
    fun backspaceOnABareHoleDoesNothing() {
        val empty = MathDocument.empty()
        val after = MathEditor.backspace(empty)
        assertTrue(after.isBlank())
        assertEquals(empty.root, after.root)
    }

    @Test
    fun deletingATemplateAfterANumberMergesBackToOneTextRun() {
        var doc = MathEditor.insertText(MathDocument.empty(), "5")
        doc = MathEditor.insertFraction(doc)
        assertTrue(doc.root.children.any { it is MathNode.Fraction })
        assertEquals("5", (doc.root.children.first() as MathNode.Text).value)
        assertEquals(2, doc.root.children.size)

        doc = MathEditor.backspace(doc)
        assertEquals("5", doc.toLinear())
        assertEquals(1, doc.root.children.size)
        assertTrue(doc.root.children.single() is MathNode.Text)
        assertEquals(listOf(0), doc.caret.path)
        assertEquals(1, doc.caret.offset)
        assertEquals(0, holeCount(doc.root))

        doc = MathEditor.backspace(doc)
        assertTrue(doc.isBlank())
        assertTrue(doc.root.children.single() is MathNode.Hole)
    }

    @Test
    fun deletingATemplateBetweenDigitsMergesTheSplitText() {
        var doc = MathEditor.insertText(MathDocument.empty(), "1234")
        doc = MathEditor.placeCaret(doc, listOf(0), 2)
        doc = MathEditor.insertSqrt(doc)
        doc = MathEditor.backspace(doc)
        assertEquals("1234", doc.toLinear())
        assertTrue(doc.root.children.single() is MathNode.Text)
        assertEquals(listOf(0), doc.caret.path)
        assertEquals(2, doc.caret.offset)
    }

    @Test
    fun longPressDeleteAfterANumberDoesNotLeaveAPhantomHole() {
        var doc = MathEditor.insertText(MathDocument.empty(), "5")
        doc = MathEditor.insertPower(doc)
        doc = MathEditor.deleteEnclosingTemplate(doc)
        assertEquals("5", doc.toLinear())
        assertEquals(1, doc.root.children.size)
        assertEquals(0, holeCount(doc.root))
        assertEquals(1, doc.caret.offset)
    }

    private fun holeCount(node: MathNode): Int = when (node) {
        is MathNode.Hole -> 1
        is MathNode.Text -> 0
        is MathNode.Row -> node.children.sumOf { holeCount(it) }
        is MathNode.Fraction -> holeCount(node.num) + holeCount(node.den)
        is MathNode.Power -> holeCount(node.base) + holeCount(node.exp)
        is MathNode.Root -> holeCount(node.radicand) + (node.index?.let { holeCount(it) } ?: 0)
        is MathNode.Parens -> holeCount(node.inner)
    }
}
