package com.example.mobilemathstudio.math

import com.example.mobilemathstudio.math.expr.MathCaret
import com.example.mobilemathstudio.math.expr.MathDocument
import com.example.mobilemathstudio.math.expr.MathNode

/**
 * Parses a LaTeX fragment into the structured AST so an imported formula is
 * rendered as a real fraction / root / power instead of raw text.
 *
 * Deliberately forgiving: anything it does not understand survives as plain
 * text rather than failing the whole import.
 */
object LatexImporter {

    data class Imported(
        val documents: List<MathDocument>,
        /** True when the source was a `cases` / aligned block with 2+ rows. */
        val isSystem: Boolean,
    )

    fun import(latex: String): Imported {
        val rows = splitRows(strip(latex))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (rows.isEmpty()) return Imported(listOf(MathDocument.empty()), false)
        val docs = rows.map { toDocument(it) }
        return Imported(docs, docs.size > 1)
    }

    /** Single-formula convenience wrapper. */
    fun importOne(latex: String): MathDocument = import(latex).documents.first()

    private fun toDocument(source: String): MathDocument {
        val children = rewriteImported(Parser(source).parseRow(stopAtClose = false))
        val row = MathNode.Row(children.ifEmpty { listOf(MathNode.Hole()) })
        return MathDocument(root = row, caret = tailCaret(row))
    }

    private fun tailCaret(row: MathNode.Row): MathCaret {
        val index = row.children.lastIndex.coerceAtLeast(0)
        val offset = (row.children.getOrNull(index) as? MathNode.Text)?.value?.length ?: 0
        return MathCaret(listOf(index), offset)
    }

    private fun strip(raw: String): String {
        var s = peelMathDelimiters(raw.trim())
        s = s.replace("\\displaystyle", " ")
            .replace("\\textstyle", " ")
            .replace("\\limits", "")
            .replace("\\left", "")
            .replace("\\right", "")
            .replace("\\!", "")
            .replace("\\,", "")
            .replace("\\;", "")
            .replace("\\:", "")
            .replace("&", "")
        return s.trim()
    }

    private fun splitRows(input: String): List<String> {
        var s = input
        ENVIRONMENTS.forEach { env ->
            s = s.replace("\\begin{$env}", "").replace("\\end{$env}", "")
        }
        return s.split("\\\\").flatMap { it.split("\\newline") }
    }

    private val ENVIRONMENTS = listOf(
        "cases", "aligned", "align", "align*", "array", "matrix",
        "pmatrix", "bmatrix", "gathered", "split", "equation", "equation*",
    )

    /** Commands that stand for an empty input slot. */
    private val PLACEHOLDERS = setOf("square", "Box", "placeholder")

    private val SYMBOLS = mapOf(
        "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "pi" to "π", "infty" to "∞", "alpha" to "α", "beta" to "β", "gamma" to "γ",
        "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε", "theta" to "θ",
        "lambda" to "λ", "mu" to "μ", "sigma" to "σ", "phi" to "φ", "varphi" to "φ",
        "omega" to "ω", "Delta" to "Δ", "Omega" to "Ω", "Sigma" to "Σ",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "lt" to "<", "gt" to ">", "ne" to "≠",
        "neq" to "≠", "approx" to "≈", "equiv" to "≡", "to" to "→",
        "rightarrow" to "→", "Rightarrow" to "⇒", "leftarrow" to "←",
        "int" to "∫", "sum" to "Σ", "prod" to "∏", "partial" to "∂",
        "ldots" to "…", "dots" to "…", "cdots" to "…", "angle" to "∠",
        "degree" to "°", "circ" to "°", "in" to "∈", "notin" to "∉",
        "subset" to "⊂", "cup" to "∪", "cap" to "∩", "forall" to "∀",
        "exists" to "∃", "sqrt" to "√",
    )

    private val FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "log", "ln", "lg", "exp", "lim", "max", "min",
        "det", "gcd", "mod",
    )

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseRow(stopAtClose: Boolean): List<MathNode> {
            val out = mutableListOf<MathNode>()
            val buf = StringBuilder()

            fun flush() {
                if (buf.isNotEmpty()) {
                    out += MathNode.Text(buf.toString())
                    buf.clear()
                }
            }

            while (pos < src.length) {
                val c = src[pos]
                when {
                    c == '}' -> {
                        if (stopAtClose) {
                            pos++
                            flush()
                            return out
                        }
                        pos++
                    }
                    c == ')' && stopAtClose -> {
                        pos++
                        flush()
                        return out
                    }
                    c == '{' -> {
                        pos++
                        val inner = parseRow(stopAtClose = true)
                        flush()
                        out += wrap(inner)
                    }
                    c == '(' -> {
                        pos++
                        val inner = parseRow(stopAtClose = true)
                        flush()
                        out += MathNode.Parens(wrap(inner))
                    }
                    c == '^' -> {
                        pos++
                        val base = takeBase(out, buf)
                        flush()
                        out += MathNode.Power(base, wrap(readArgument()))
                    }
                    c == '_' -> {
                        pos++
                        val sub = linearOf(wrap(readArgument()))
                        buf.append('_').append(sub)
                    }
                    c == '\\' -> {
                        val command = readCommand()
                        when {
                            command == "frac" || command == "dfrac" || command == "tfrac" -> {
                                flush()
                                val num = wrap(readArgument())
                                val den = wrap(readArgument())
                                out += MathNode.Fraction(num, den)
                            }
                            command == "sqrt" -> {
                                flush()
                                val index = readOptionalArgument()?.let { wrap(Parser(it).parseRow(false)) }
                                out += MathNode.Root(index, wrap(readArgument()))
                            }
                            command == "text" || command == "mathrm" || command == "operatorname" -> {
                                buf.append(linearOf(wrap(readArgument())))
                            }
                            // How an empty slot is exported, so it must come back
                            // as a slot rather than the word "square".
                            command in PLACEHOLDERS -> {
                                flush()
                                out += MathNode.Hole()
                            }
                            command in FUNCTIONS -> buf.append(command)
                            SYMBOLS.containsKey(command) -> buf.append(SYMBOLS.getValue(command))
                            command.isEmpty() -> pos++
                            else -> buf.append(command)
                        }
                    }
                    c.isWhitespace() -> {
                        pos++
                        if (buf.isNotEmpty() && buf.last() != ' ') buf.append(' ')
                    }
                    else -> {
                        buf.append(nativeOperator(c, src.getOrNull(pos + 1)))
                        pos++
                    }
                }
            }
            flush()
            return out
        }

        /** `2x^2` raises only `x`, not `2x`. */
        private fun takeBase(out: MutableList<MathNode>, buf: StringBuilder): MathNode {
            if (buf.isNotEmpty()) {
                val last = buf.last()
                buf.deleteCharAt(buf.length - 1)
                return MathNode.Text(last.toString())
            }
            return if (out.isNotEmpty()) out.removeAt(out.lastIndex) else MathNode.Hole()
        }

        private fun readCommand(): String {
            pos++ // consume backslash
            val start = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            if (pos == start) {
                // Escaped punctuation such as \{ or \%
                val ch = src.getOrNull(pos)?.also { pos++ }
                return ch?.toString() ?: ""
            }
            return src.substring(start, pos)
        }

        private fun readArgument(): List<MathNode> {
            skipSpaces()
            if (pos < src.length && src[pos] == '{') {
                pos++
                return parseRow(stopAtClose = true)
            }
            if (pos < src.length && src[pos] == '\\') {
                val command = readCommand()
                if (command in PLACEHOLDERS) return listOf(MathNode.Hole())
                val text = SYMBOLS[command] ?: command
                return listOf(MathNode.Text(text))
            }
            val ch = src.getOrNull(pos) ?: return listOf(MathNode.Hole())
            pos++
            return listOf(MathNode.Text(ch.toString()))
        }

        private fun readOptionalArgument(): String? {
            skipSpaces()
            if (pos >= src.length || src[pos] != '[') return null
            val close = src.indexOf(']', pos)
            if (close < 0) return null
            val body = src.substring(pos + 1, close)
            pos = close + 1
            return body
        }

        private fun skipSpaces() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }

    private fun wrap(nodes: List<MathNode>): MathNode = when {
        nodes.isEmpty() -> MathNode.Hole()
        nodes.size == 1 -> nodes.first()
        else -> MathNode.Row(nodes)
    }

    private fun linearOf(node: MathNode): String = com.example.mobilemathstudio.math.expr.linearize(node)

    /**
     * Outer math fences only: `$…$`, `$$…$$`, `\(…\)`, `\[…\]`.
     * Leftover wrapping dollars are peeled repeatedly, then any stray `$` is dropped.
     */
    private fun peelMathDelimiters(raw: String): String {
        var s = raw.trim()
        var guard = 0
        while (guard++ < 8) {
            val next = when {
                s.startsWith("$$") && s.endsWith("$$") && s.length >= 4 ->
                    s.substring(2, s.length - 2)
                s.startsWith("\\[") && s.endsWith("\\]") && s.length >= 4 ->
                    s.substring(2, s.length - 2)
                s.startsWith("\\(") && s.endsWith("\\)") && s.length >= 4 ->
                    s.substring(2, s.length - 2)
                s.startsWith("$") && s.endsWith("$") && s.length >= 2 && !s.startsWith("$$") ->
                    s.substring(1, s.length - 1)
                else -> break
            }
            s = next.trim()
        }
        return s.replace("$", "").trim()
    }

    /** `*` → · (`\cdot`), `:` → ÷ (`\div`) except in `:=`. */
    private fun nativeOperator(c: Char, next: Char?): Char = when {
        c == '*' -> '·'
        c == ':' && next != '=' -> '÷'
        else -> c
    }

    private fun rewriteOps(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val a = value[i]
            val b = value.getOrNull(i + 1)
            when {
                a == '>' && b == '=' -> {
                    out.append('≥')
                    i += 2
                }
                a == '<' && b == '=' -> {
                    out.append('≤')
                    i += 2
                }
                else -> {
                    out.append(nativeOperator(a, b))
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun rewriteImported(nodes: List<MathNode>): List<MathNode> =
        foldSlashes(nodes.map { rewriteNode(it) })

    private fun rewriteNode(node: MathNode): MathNode = when (node) {
        is MathNode.Hole -> node
        is MathNode.Text -> foldChild(MathNode.Text(rewriteOps(node.value)))
        is MathNode.Row -> MathNode.Row(
            foldSlashes(node.children.map { rewriteNode(it) }).ifEmpty { listOf(MathNode.Hole()) },
        )
        is MathNode.Parens -> MathNode.Parens(foldChild(rewriteNode(node.inner)))
        is MathNode.Fraction -> MathNode.Fraction(
            foldChild(rewriteNode(node.num)),
            foldChild(rewriteNode(node.den)),
        )
        is MathNode.Power -> MathNode.Power(
            foldChild(rewriteNode(node.base)),
            foldChild(rewriteNode(node.exp)),
        )
        is MathNode.Root -> MathNode.Root(
            index = node.index?.let { foldChild(rewriteNode(it)) },
            radicand = foldChild(rewriteNode(node.radicand)),
        )
    }

    /** Fold slashes that live inside a single child (e.g. `a/b` as one Text run). */
    private fun foldChild(node: MathNode): MathNode {
        val folded = when (node) {
            is MathNode.Row -> foldSlashes(node.children)
            else -> foldSlashes(listOf(node))
        }
        return wrap(folded)
    }

    /** Turns `(A)/(B)` and `A/B` into [MathNode.Fraction], including nested rows. */
    private fun foldSlashes(nodes: List<MathNode>): List<MathNode> {
        val tokens = expandSlashText(nodes)
        val out = mutableListOf<MathNode>()
        var i = 0
        while (i < tokens.size) {
            val cur = tokens[i]
            if (isSlash(cur) && out.isNotEmpty() && i + 1 < tokens.size) {
                val left = unwrapParens(out.removeAt(out.lastIndex))
                val right = unwrapParens(tokens[i + 1])
                out += MathNode.Fraction(left, right)
                i += 2
            } else {
                out += cur
                i++
            }
        }
        return out
    }

    private fun expandSlashText(nodes: List<MathNode>): List<MathNode> {
        val out = mutableListOf<MathNode>()
        for (node in nodes) {
            if (node is MathNode.Text && node.value.contains('/') && node.value != "/") {
                node.value.split('/').forEachIndexed { index, part ->
                    if (index > 0) out += MathNode.Text("/")
                    if (part.isNotEmpty()) out += MathNode.Text(part)
                }
            } else {
                out += node
            }
        }
        return out
    }

    private fun isSlash(node: MathNode): Boolean =
        node is MathNode.Text && node.value == "/"

    private fun unwrapParens(node: MathNode): MathNode {
        var current = node
        while (current is MathNode.Parens) current = current.inner
        return current
    }
}
