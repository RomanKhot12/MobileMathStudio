package com.example.mobilemathstudio.math.expr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Blinking-caret navigation: arrows and tap placement. */
class CaretNavigationTest {

    private fun textDoc(value: String, offset: Int = value.length) = MathDocument(
        root = MathNode.Row(listOf(MathNode.Text(value))),
        caret = MathCaret(path = listOf(0), offset = offset),
    )

    @Test
    fun arrowsWalkOneCharacterAtATime() {
        var doc = textDoc("855")
        assertEquals(3, doc.caret.offset)

        doc = MathEditor.moveCaret(doc, -1)
        assertEquals(2, doc.caret.offset)
        doc = MathEditor.moveCaret(doc, -1)
        assertEquals(1, doc.caret.offset)
        doc = MathEditor.moveCaret(doc, -1)
        assertEquals(0, doc.caret.offset)
        doc = MathEditor.moveCaret(doc, -1)
        assertEquals(0, doc.caret.offset)

        doc = MathEditor.moveCaret(doc, 1)
        assertEquals(1, doc.caret.offset)
        doc = MathEditor.moveCaret(doc, 1)
        assertEquals(2, doc.caret.offset)
    }

    @Test
    fun aTapLandsBetweenTheTappedCharacters() {
        val placed = MathEditor.placeCaret(textDoc("855"), listOf(0), 1)
        assertEquals(listOf(0), placed.caret.path)
        assertEquals(1, placed.caret.offset)

        val atEnd = MathEditor.placeCaret(textDoc("855"), listOf(0), 99)
        assertEquals(3, atEnd.caret.offset)
    }

    @Test
    fun insertingAtTheTappedSpotDoesNotAppend() {
        val caret = MathEditor.placeCaret(textDoc("855"), listOf(0), 1)
        val typed = MathEditor.insertText(caret, "0")
        assertEquals("8055", typed.toLinear())
        assertEquals(2, typed.caret.offset)
    }

    @Test
    fun arrowsCrossFromANumberIntoAFractionSlot() {
        var doc = MathEditor.insertFraction(MathDocument.empty())
        // Empty cell → fraction, caret in the numerator hole.
        assertEquals(listOf(0, 0), doc.caret.path)

        doc = MathEditor.insertText(doc, "6")
        doc = MathEditor.moveCaret(doc, 1)
        assertEquals(listOf(0, 1), doc.caret.path)

        doc = MathEditor.insertText(doc, "9")
        doc = MathEditor.moveCaret(doc, -1)
        assertEquals(listOf(0, 1), doc.caret.path)
        assertEquals(0, doc.caret.offset)

        doc = MathEditor.moveCaret(doc, -1)
        assertEquals(listOf(0, 0), doc.caret.path)
        assertEquals(1, doc.caret.offset)
    }

    @Test
    fun cursorBackOnParenthesesStillLandsInsideThem() {
        var doc = MathEditor.insertText(MathDocument.empty(), "()")
        doc = MathEditor.moveCaret(doc, -1)
        doc = MathEditor.insertText(doc, "x")
        assertEquals("(x)", doc.toLinear())
    }
}
