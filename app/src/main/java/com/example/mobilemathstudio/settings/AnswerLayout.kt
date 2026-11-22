package com.example.mobilemathstudio.settings

/** How an answer with several variables is printed. */
enum class AnswerLayout {
    /** One variable per line — the default. */
    Column,

    /** Everything on one line, separated by commas. */
    Inline,
}

/**
 * Answers are always stored one variable per line; this turns that canonical
 * form into what the user asked to see.
 */
fun formatAnswer(text: String, layout: AnswerLayout): String = when (layout) {
    AnswerLayout.Column -> text
    AnswerLayout.Inline -> text.lines().filter { it.isNotBlank() }.joinToString(", ")
}
