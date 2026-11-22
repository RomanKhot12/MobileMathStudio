package com.example.mobilemathstudio.math

/**
 * Result payload supporting exact ↔ decimal toggle.
 */
sealed class SolveOutcome {
    data class Value(
        val exact: String,
        val decimal: String? = null,
        val latex: String = exact,
    ) : SolveOutcome() {
        val display: String get() = exact
    }

    data class Solutions(
        val exactLines: List<String>,
        val decimalLines: List<String>? = null,
        val latex: String = exactLines.joinToString(", "),
    ) : SolveOutcome() {
        val lines: List<String> get() = exactLines
    }

    data class Info(val message: String) : SolveOutcome()

    data class Error(
        val message: String,
        val start: Int = 0,
        val end: Int = 0,
    ) : SolveOutcome()
}
