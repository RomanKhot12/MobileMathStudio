package com.example.mobilemathstudio.math

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * Visual math typography: italic serif variables vs bold upright × · ÷.
 */
object FormulaRenderer {
    private val FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "asin", "acos", "atan",
        "log", "ln", "exp", "sqrt", "abs", "lim",
    )

    fun annotateWithCursor(
        text: String,
        cursorIndex: Int,
        showCursor: Boolean,
        cursorColor: Color,
        baseColor: Color,
        errorColor: Color,
        errorStart: Int?,
        errorEnd: Int?,
    ): AnnotatedString {
        val cur = cursorIndex.coerceIn(0, text.length)
        return buildAnnotatedString {
            appendSegment(text, 0, cur, baseColor, errorColor, errorStart, errorEnd)
            if (showCursor) {
                withStyle(SpanStyle(color = cursorColor)) { append("│") }
            }
            appendSegment(text, cur, text.length, baseColor, errorColor, errorStart, errorEnd)
        }
    }

    /** Plain styled text (no caret) — used for hit-testing measurement. */
    fun annotatePlain(
        text: String,
        baseColor: Color,
        errorColor: Color = Color.Unspecified,
        errorStart: Int? = null,
        errorEnd: Int? = null,
    ): AnnotatedString = buildAnnotatedString {
        appendSegment(text, 0, text.length, baseColor, errorColor, errorStart, errorEnd)
    }

    private fun AnnotatedString.Builder.appendSegment(
        text: String,
        from: Int,
        to: Int,
        baseColor: Color,
        errorColor: Color,
        errorStart: Int?,
        errorEnd: Int?,
    ) {
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c.isLetter() -> {
                    var j = i + 1
                    while (j < to && text[j].isLetterOrDigit()) j++
                    val word = text.substring(i, j)
                    val err = overlaps(i, j, errorStart, errorEnd)
                    val color = if (err) errorColor else baseColor
                    val style = if (word.lowercase() in FUNCTIONS) {
                        SpanStyle(color = color, fontStyle = FontStyle.Normal, fontFamily = FontFamily.SansSerif)
                    } else {
                        SpanStyle(
                            color = color,
                            fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Serif,
                            fontSize = 18.sp,
                        )
                    }
                    withStyle(style) { append(word) }
                    i = j
                }
                c == '×' || c == '·' || c == '÷' -> {
                    val err = overlaps(i, i + 1, errorStart, errorEnd)
                    withStyle(
                        SpanStyle(
                            color = if (err) errorColor else baseColor,
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Normal,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 18.sp,
                        ),
                    ) { append(c) }
                    i++
                }
                else -> {
                    val err = overlaps(i, i + 1, errorStart, errorEnd)
                    withStyle(
                        SpanStyle(
                            color = if (err) errorColor else baseColor,
                            fontStyle = FontStyle.Normal,
                            fontFamily = FontFamily.SansSerif,
                        ),
                    ) { append(c) }
                    i++
                }
            }
        }
    }

    private fun overlaps(start: Int, end: Int, errStart: Int?, errEnd: Int?): Boolean {
        if (errStart == null || errEnd == null) return false
        return start < errEnd && end > errStart
    }
}
