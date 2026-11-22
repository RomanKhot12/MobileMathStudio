package com.example.mobilemathstudio.math

import com.example.mobilemathstudio.math.expr.MathNode
import com.example.mobilemathstudio.math.expr.linearize

/**
 * Bidirectional helpers between canvas syntax and LaTeX.
 *
 * Export walks the AST directly ([toLatex] with a [MathNode]) — the linear
 * string form loses structure, so `(1)/(2)` could never become `\frac{1}{2}`.
 */
object LatexCodec {

    /** Structured export: the only form that produces correct LaTeX. */
    fun toLatex(node: MathNode): String = when (node) {
        is MathNode.Hole -> "\\square"
        is MathNode.Text -> escapeText(node.value)
        is MathNode.Row -> node.children.joinToString("") { toLatex(it) }
        is MathNode.Fraction -> "\\frac{${toLatex(node.num)}}{${toLatex(node.den)}}"
        is MathNode.Power -> "{${toLatex(node.base)}}^{${toLatex(node.exp)}}"
        is MathNode.Root -> if (node.index == null) {
            "\\sqrt{${toLatex(node.radicand)}}"
        } else {
            "\\sqrt[${toLatex(node.index)}]{${toLatex(node.radicand)}}"
        }
        is MathNode.Parens -> "\\left(${toLatex(node.inner)}\\right)"
    }

    /** Legacy string path, kept for plain linear expressions. */
    fun toLatex(expression: String): String {
        var s = expression
        SYMBOL_TO_LATEX.forEach { (glyph, latex) -> s = s.replace(glyph, latex) }
        FUNCTION_NAMES.forEach { name -> s = s.replace("$name(", "\\$name(") }
        // √(x) has no structure left in a linear string, but braces beat nothing.
        s = Regex("""√\(([^()]*)\)""").replace(s) { "\\sqrt{${it.groupValues[1]}}" }
        return s
    }

    fun fromLatex(latex: String): String = linearize(LatexImporter.importOne(latex).root)

    private fun escapeText(value: String): String {
        var s = value
        SYMBOL_TO_LATEX.forEach { (glyph, latex) -> s = s.replace(glyph, latex) }
        FUNCTION_NAMES.forEach { name -> s = s.replace(name, "\\$name ") }
        return s
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    private val SYMBOL_TO_LATEX = linkedMapOf(
        "÷" to "\\div ",
        "×" to "\\times ",
        "·" to "\\cdot ",
        "−" to "-",
        "±" to "\\pm ",
        "π" to "\\pi ",
        "∞" to "\\infty ",
        "≤" to "\\le ",
        "≥" to "\\ge ",
        "≠" to "\\neq ",
        "≈" to "\\approx ",
        "∫" to "\\int ",
        "Σ" to "\\sum ",
        "→" to "\\to ",
        "α" to "\\alpha ",
        "β" to "\\beta ",
        "γ" to "\\gamma ",
        "θ" to "\\theta ",
        "λ" to "\\lambda ",
        "μ" to "\\mu ",
        "φ" to "\\varphi ",
        "ω" to "\\omega ",
    )

    private val FUNCTION_NAMES = listOf("arcsin", "arccos", "arctan", "sin", "cos", "tan", "log", "ln")
}
