package com.example.mobilemathstudio.math

import com.example.mobilemathstudio.math.expr.MathDocument
import com.example.mobilemathstudio.math.expr.MathNode
import java.math.BigInteger

/** One `x = value` row of an answer. [label] is empty for a bare value. */
data class AnswerLine(val label: String, val value: String)

/** A mixed number: `whole` plus `num/den`, all written without a sign. */
data class MixedNumber(val whole: String, val num: String, val den: String)

/**
 * Turns the flat text an engine produces (`x = 121/9`) into something that can be
 * drawn as real mathematics: two-storey fractions and mixed numbers.
 */
object AnswerMath {

    private val FRACTION = Regex("""(-?(?:\d+|π|e))\s*/\s*(\d+|π|e)""")
    private val WHOLE_FRACTION = Regex("""^\s*(-?\d+)\s*/\s*(\d+)\s*$""")
    private val LONG_TAIL = Regex("""[.,]\d{11,}""")
    private val IMAGINARY = Regex("""(^|[^A-Za-z])i($|[^A-Za-z])""")
    private val EULER = Regex("""(^|[^A-Za-z])[eE]($|[^A-Za-z])""")
    private const val MINUS = "\u2212"

    fun split(text: String): List<AnswerLine> = text.lines()
        .filter { it.isNotBlank() }
        .map { line ->
            val at = line.indexOf('=')
            if (at < 0) AnswerLine(label = "", value = line.trim())
            else AnswerLine(label = line.take(at).trim(), value = line.substring(at + 1).trim())
        }

    /**
     * Renders a value as a formula. Every `a/b` inside it becomes a fraction with
     * the numerator above the denominator; when [mixed] is set and the value is a
     * single improper fraction, the equivalent mixed number is appended after an
     * equals sign — `121/9 = 13 4/9`.
     */
    fun document(value: String, mixed: Boolean): MathDocument {
        val nodes = mutableListOf<MathNode>()
        nodes += valueNodes(value)
        if (mixed) {
            mixedOf(value)?.let { number ->
                nodes += MathNode.Text(" = ")
                nodes += mixedNodes(value.trim().startsWith("-"), number)
            }
        }
        return MathDocument(root = MathNode.Row(nodes))
    }

    /** A full answer row, including the `x =` label when there is one. */
    fun lineDocument(line: AnswerLine, mixed: Boolean): MathDocument {
        val value = document(line.value, mixed)
        if (line.label.isEmpty()) return value
        val label = if (line.label.length == 1 && line.label[0].isLetter()) {
            listOf(MathNode.Text(line.label), MathNode.Text(" = "))
        } else {
            listOf(MathNode.Text("${line.label} = "))
        }
        return MathDocument(root = MathNode.Row(label + value.root.children))
    }

    /** The mixed form of an improper fraction, or null when there is nothing to convert. */
    fun mixedOf(value: String): MixedNumber? {
        val match = WHOLE_FRACTION.matchEntire(value) ?: return null
        val numerator = match.groupValues[1].removePrefix("-").toBigIntegerOrNull() ?: return null
        val denominator = match.groupValues[2].toBigIntegerOrNull() ?: return null
        if (denominator.signum() == 0) return null
        val whole = numerator / denominator
        val rest = numerator.mod(denominator)
        // Proper fractions and plain integers stay as they are.
        if (whole.signum() == 0 || rest.signum() == 0) return null
        return MixedNumber(whole.toString(), rest.toString(), denominator.toString())
    }

    /**
     * The expression whose digits the long-number viewer should expand, or null
     * when there is nothing interesting to open.
     */
    fun inspectSource(value: String): String? {
        val text = value.trim()
        if (text.isEmpty()) return null
        if ('∞' in text || "undefined" in text || IMAGINARY.containsMatchIn(text)) return null
        if (LONG_TAIL.containsMatchIn(text)) return text
        DecimalExpansion.rationalOf(text)?.let { (_, denominator) ->
            return text.takeIf { DecimalExpansion.repeats(denominator) }
        }
        if (looksLikeNumericConstant(text)) {
            if (text == "π" || text == "Pi" || 'π' in text || '√' in text) return text.replace("Pi", "π")
            if (text == "e" || text == "E" || EULER.containsMatchIn(text)) return text
        }
        if ('π' in text) return "π"
        if (EULER.containsMatchIn(text)) return "e"
        return null
    }

    fun isInspectable(value: String): Boolean = inspectSource(value) != null

    /**
     * Digits, arithmetic and the constants π / e / √ — not a free variable like k.
     */
    private fun looksLikeNumericConstant(text: String): Boolean {
        if (text.none { it.isDigit() || it == 'π' || it == 'e' || it == 'E' || it == '√' }) return false
        return text.all { c ->
            c.isDigit() || c == 'π' || c == 'e' || c == 'E' || c == '√' ||
                c == '(' || c == ')' || c == '/' || c == '*' || c == '+' ||
                c == '-' || c == '.' || c == '^' || c == ' '
        }
    }

    private fun valueNodes(value: String): List<MathNode> {
        val nodes = mutableListOf<MathNode>()
        var index = 0
        for (match in FRACTION.findAll(value)) {
            if (match.range.first > index) {
                nodes += MathNode.Text(value.substring(index, match.range.first))
            }
            nodes += fractionNodes(match.groupValues[1], match.groupValues[2])
            index = match.range.last + 1
        }
        if (index < value.length) nodes += MathNode.Text(value.substring(index))
        return nodes.ifEmpty { listOf(MathNode.Text(value)) }
    }

    private fun fractionNodes(numerator: String, denominator: String): List<MathNode> {
        val negative = numerator.startsWith("-")
        val fraction = MathNode.Fraction(
            num = MathNode.Text(numerator.removePrefix("-")),
            den = MathNode.Text(denominator),
        )
        // The sign belongs beside the bar, not on top of it.
        return if (negative) listOf(MathNode.Text(MINUS), fraction) else listOf(fraction)
    }

    private fun mixedNodes(negative: Boolean, number: MixedNumber): List<MathNode> {
        val nodes = mutableListOf<MathNode>()
        if (negative) nodes += MathNode.Text(MINUS)
        nodes += MathNode.Text(number.whole)
        nodes += MathNode.Fraction(MathNode.Text(number.num), MathNode.Text(number.den))
        return nodes
    }

    private fun String.toBigIntegerOrNull() = runCatching { BigInteger(this) }.getOrNull()
}
