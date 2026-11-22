package com.example.mobilemathstudio.math.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathSelectionTest {

    @Test
    fun coveringATextRunSelectsEveryCharacter() {
        val doc = MathDocument.fromPlain("xyz")
        val range = MathEditor.selectionCovering(doc, listOf(0))
        assertNotNull(range)
        assertEquals("xyz", MathEditor.extractLinear(doc, range!!))
        assertEquals(IntRange(0, 2), MathEditor.selectedChars(doc, range, listOf(0), 3)?.let {
            it.first..it.last
        })
    }

    @Test
    fun deleteRangeRemovesTheHighlightedRun() {
        val doc = MathDocument.fromPlain("abcdef")
        val range = MathRange(MathCaret(listOf(0), 2), MathCaret(listOf(0), 5))
        val next = MathEditor.deleteRange(doc, range)
        assertEquals("abf", next.toLinear())
    }

    @Test
    fun backspaceIsNotUsedWhenARangeIsDeleted() {
        val doc = MathEditor.insertFraction(MathDocument.empty())
        val filled = MathEditor.insertText(doc, "12")
        val range = MathEditor.selectionCovering(filled, listOf(0, 0))!!
        val cleared = MathEditor.deleteRange(filled, range)
        assertTrue(MathEditor.isSlotEmpty(cleared, listOf(0, 0)))
        assertTrue(cleared.root.children.single() is MathNode.Fraction)
    }

    @Test
    fun collapsedRangeDoesNotChangeTheDocument() {
        val doc = MathDocument.fromPlain("ab")
        val range = MathRange(MathCaret(listOf(0), 1), MathCaret(listOf(0), 1))
        assertTrue(MathEditor.rangeIsCollapsed(doc, range))
        assertEquals("ab", MathEditor.deleteRange(doc, range).toLinear())
    }

    @Test
    fun moveCaretFromWalksOneCharacter() {
        val doc = MathDocument.fromPlain("abcd")
        val moved = MathEditor.moveCaretFrom(doc, MathCaret(listOf(0), 2), -1)
        assertEquals(1, moved.offset)
        assertFalse(MathEditor.rangeIsCollapsed(doc, MathRange(moved, MathCaret(listOf(0), 2))))
    }
}
