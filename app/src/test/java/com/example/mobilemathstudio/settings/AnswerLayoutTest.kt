package com.example.mobilemathstudio.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Answers are stored one variable per line and reformatted for display. */
class AnswerLayoutTest {

    @Test
    fun columnIsTheDefaultAndKeepsEveryVariableOnItsOwnLine() {
        assertEquals(AnswerLayout.Column, AppSettings().answerLayout)
        assertEquals(10, AppSettings().decimalPlaces)
        assertEquals("x = 2\ny = 3", formatAnswer("x = 2\ny = 3", AnswerLayout.Column))
    }

    @Test
    fun inlineJoinsTheLinesWithCommas() {
        assertEquals("x = 2, y = 3.5", formatAnswer("x = 2\ny = 3.5", AnswerLayout.Inline))
    }

    @Test
    fun inlineDropsTheBlankLinesThatSeparateSolutionSets() {
        assertEquals(
            "x = 1, y = 1, x = -1, y = -1",
            formatAnswer("x = 1\ny = 1\n\nx = -1\ny = -1", AnswerLayout.Inline),
        )
    }

    @Test
    fun aSingleAnswerLooksTheSameInBothModes() {
        AnswerLayout.entries.forEach { layout ->
            assertEquals("x = 5", formatAnswer("x = 5", layout))
        }
    }
}
