package com.example.mobilemathstudio.math

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * An expression parsed once into an evaluation tree. Evaluating it is a plain
 * tree walk, so graph sampling and numeric root finding no longer re-parse the
 * source string for every point.
 *
 * Grammar and error semantics match the original interpreter: syntax errors are
 * [ParseException]s raised by [compile]; domain errors (division by zero, root of
 * a negative, invalid factorial) and unknown variables are raised by [evaluate].
 */
class CompiledExpression private constructor(private val root: Node) {

    fun evaluate(vars: Map<String, Double>): Double {
        val value = root.eval(vars, true)
        if (value.isNaN()) throw ArithmeticException("Invalid result")
        if (value.isInfinite()) throw ArithmeticException("Division by zero")
        return value
    }

    /**
     * Never throws: domain errors and unknown variables become NaN. Used on hot
     * plotting paths where an exception per undefined point would be far too slow.
     */
    fun evaluateOrNaN(vars: Map<String, Double>): Double {
        val v = root.eval(vars, false)
        return if (v.isFinite()) v else Double.NaN
    }

    companion object {
        private const val CACHE_SIZE = 512

        private val cache = object : LinkedHashMap<String, Any>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean =
                size > CACHE_SIZE
        }

        /** Parses [source] (cached). Throws [ParseException] for malformed input. */
        fun compile(source: String): CompiledExpression {
            val cached = synchronized(cache) { cache[source] }
            when (cached) {
                is CompiledExpression -> return cached
                is ParseException -> throw ParseException(
                    cached.message ?: "Parse error",
                    cached.index,
                    cached.length,
                )
            }
            val result: Any = try {
                val parser = Compiler(source)
                val node = parser.parseExpression()
                parser.expectEnd()
                CompiledExpression(node)
            } catch (e: ParseException) {
                e
            }
            synchronized(cache) { cache[source] = result }
            if (result is ParseException) throw result
            return result as CompiledExpression
        }

        fun compileOrNull(source: String): CompiledExpression? = try {
            compile(source)
        } catch (_: ParseException) {
            null
        }
    }

    /** [strict] = throw like the interpreter did; otherwise report NaN. */
    private abstract class Node {
        abstract fun eval(v: Map<String, Double>, strict: Boolean): Double
        open val constant: Boolean get() = false
    }

    private class Const(val value: Double) : Node() {
        override fun eval(v: Map<String, Double>, strict: Boolean) = value
        override val constant: Boolean get() = true
    }

    private class Variable(val name: String, val key: String, val start: Int) : Node() {
        override fun eval(v: Map<String, Double>, strict: Boolean): Double =
            v[name] ?: v[key] ?: if (strict) {
                throw ParseException("Unknown '$name'", start, name.length)
            } else {
                Double.NaN
            }
    }

    private class Add(val a: Node, val b: Node) : Node() {
        override fun eval(v: Map<String, Double>, strict: Boolean) = a.eval(v, strict) + b.eval(v, strict)
    }

    private class Sub(val a: Node, val b: Node) : Node() {
        override fun eval(v: Map<String, Double>, strict: Boolean) = a.eval(v, strict) - b.eval(v, strict)
    }

    private class Mul(val a: Node, val b: Node) : Node() {
        override fun eval(v: Map<String, Double>, strict: Boolean) = a.eval(v, strict) * b.eval(v, strict)
    }

    private class Div(val a: Node, val b: Node) : Node() {
        override fun eval(v: Map<String, Double>, strict: Boolean): Double {
            val num = a.eval(v, strict)
            val den = b.eval(v, strict)
            if (abs(den) < 1e-15) return fail(strict, "Division by zero")
            return num / den
        }
    }

    private class Pow(val a: Node, val b: Node) : Node() {
        override fun eval(v: Map<String, Double>, strict: Boolean) = a.eval(v, strict).pow(b.eval(v, strict))
    }

    private class Neg(val a: Node) : Node() {
        override fun eval(v: Map<String, Double>, strict: Boolean) = -a.eval(v, strict)
    }

    private class Percent(val a: Node) : Node() {
        override fun eval(v: Map<String, Double>, strict: Boolean) = a.eval(v, strict) / 100.0
    }

    private class Factorial(val a: Node) : Node() {
        override fun eval(v: Map<String, Double>, strict: Boolean): Double {
            val value = a.eval(v, strict)
            if (value.isNaN() || value < 0 || abs(value - value.toLong()) > 1e-9 || value > 170) {
                return fail(strict, "Invalid factorial")
            }
            var result = 1.0
            for (i in 2..value.toLong()) result *= i
            return result
        }
    }

    private class Func(val fn: Int, val arg: Node) : Node() {
        override fun eval(v: Map<String, Double>, strict: Boolean): Double {
            val x = arg.eval(v, strict)
            return when (fn) {
                SIN -> sin(x)
                COS -> cos(x)
                TAN -> tan(x)
                COT -> {
                    val t = tan(x)
                    if (abs(t) < 1e-15) fail(strict, "Division by zero") else 1.0 / t
                }
                ASIN -> asin(x)
                ACOS -> acos(x)
                ATAN -> atan(x)
                LOG -> log10(x)
                LN -> ln(x)
                SQRT -> if (x < 0) fail(strict, "Square root of negative") else sqrt(x)
                ABS -> abs(x)
                else -> exp(x)
            }
        }
    }

    /** Recursive-descent parser producing [Node]s; mirrors the original evaluator. */
    private class Compiler(private val input: String) {
        private var pos = 0

        fun parseExpression(): Node = parseAddSub()

        fun expectEnd() {
            skipWs()
            if (pos < input.length) throw ParseException("Unexpected '${input[pos]}'", pos)
        }

        private fun parseAddSub(): Node {
            var node = parseMulDiv()
            while (true) {
                skipWs()
                node = when (peek()) {
                    '+' -> { pos++; fold(Add(node, parseMulDiv())) }
                    '-', '−' -> { pos++; fold(Sub(node, parseMulDiv())) }
                    else -> return node
                }
            }
        }

        private fun parseMulDiv(): Node {
            var node = parsePower()
            while (true) {
                skipWs()
                val c = peek()
                node = when {
                    c == '*' || c == '×' || c == '·' -> { pos++; fold(Mul(node, parsePower())) }
                    c == '/' || c == '÷' -> { pos++; fold(Div(node, parsePower())) }
                    // Implicit multiplication: 9x, 2(x+1), (a)(b), πx, √(…)
                    c != null && startsPrimary(c) -> fold(Mul(node, parsePower()))
                    else -> return node
                }
            }
        }

        private fun startsPrimary(c: Char): Boolean = when {
            // '|' is deliberately excluded: a closing bar would be misread as
            // juxtaposition (e.g. |-4|). Products need 2*|x| or 2abs(x).
            c == '(' || c == '[' || c == '√' || c == 'π' || c == '∞' -> true
            c.isDigit() || c == '.' || c == ',' -> true
            c.isLetter() -> true
            else -> false
        }

        /** Unary signs bind looser than ^, so -3^2 = -(3^2) = -9, while 2^-1 = 0.5. */
        private fun parsePower(): Node {
            skipWs()
            when (peek()) {
                '+' -> { pos++; return parsePower() }
                '-', '−' -> { pos++; return fold(Neg(parsePower())) }
            }
            val base = parsePostfix()
            skipWs()
            if (peek() == '^') {
                pos++
                return fold(Pow(base, parseExponent()))
            }
            return base
        }

        private fun parseExponent(): Node {
            skipWs()
            return when (peek()) {
                '+' -> { pos++; parseExponent() }
                '-', '−' -> { pos++; fold(Neg(parseExponent())) }
                else -> parsePostfix()
            }
        }

        private fun parsePostfix(): Node {
            var node = parsePrimary()
            while (true) {
                skipWs()
                node = when (peek()) {
                    '%' -> { pos++; fold(Percent(node)) }
                    '!' -> { pos++; fold(Factorial(node)) }
                    else -> return node
                }
            }
        }

        private fun parsePrimary(): Node {
            skipWs()
            val c = peek() ?: throw ParseException("Unexpected end", input.length)
            return when {
                c == '(' || c == '[' -> {
                    val close = if (c == '(') ')' else ']'
                    val start = pos
                    pos++
                    val node = parseExpression()
                    skipWs()
                    if (peek() != close) throw ParseException("Unclosed '$c'", start, 1)
                    pos++
                    node
                }
                c == '|' -> {
                    val start = pos
                    pos++
                    val node = parseExpression()
                    skipWs()
                    if (peek() != '|') throw ParseException("Unclosed '|'", start, 1)
                    pos++
                    fold(Func(ABS, node))
                }
                c == '√' -> {
                    val start = pos
                    pos++
                    skipWs()
                    if (peek() == '(') {
                        pos++
                        val node = parseExpression()
                        skipWs()
                        if (peek() != ')') throw ParseException("Unclosed √(", start, 1)
                        pos++
                        fold(Func(SQRT, node))
                    } else {
                        fold(Func(SQRT, parseExponent()))
                    }
                }
                c == 'π' -> { pos++; Const(PI) }
                c == '∞' -> { pos++; Const(Double.POSITIVE_INFINITY) }
                c.isDigit() || c == '.' || c == ',' -> parseNumber()
                c.isLetter() -> parseIdentifier()
                else -> throw ParseException("Unexpected '$c'", pos)
            }
        }

        private fun parseNumber(): Node {
            val start = pos
            val buf = StringBuilder()
            while (true) {
                val ch = peek() ?: break
                when {
                    ch.isDigit() -> { buf.append(ch); pos++ }
                    ch == '.' || ch == ',' -> { buf.append('.'); pos++ }
                    else -> break
                }
            }
            val value = buf.toString().toDoubleOrNull()
                ?: throw ParseException("Invalid number", start, (pos - start).coerceAtLeast(1))
            return Const(value)
        }

        private fun parseIdentifier(): Node {
            val start = pos
            while (peek()?.isLetterOrDigit() == true) pos++
            val name = input.substring(start, pos)
            val key = name.lowercase()
            skipWs()
            // Support standard mathematical power-before-argument notation: sin^2(x), cos^3(2x), ln^2(x).
            if (key in FUNCTION_IDS && peek() == '^') {
                val savedPos = pos
                pos++
                val expNode = parseExponent()
                skipWs()
                if (peek() == '(') {
                    val call = parseFunctionCall(start, name, key)
                    return fold(Pow(call, expNode))
                }
                pos = savedPos
            }
            if (peek() == '(') {
                return parseFunctionCall(start, name, key)
            }
            return when (key) {
                "pi" -> Const(PI)
                "e" -> Const(E)
                "infinity" -> Const(Double.POSITIVE_INFINITY)
                else -> Variable(name, key, start)
            }
        }

        private fun parseFunctionCall(start: Int, name: String, key: String): Node {
            pos++
            val args = mutableListOf<Node>()
            skipWs()
            if (peek() != ')') {
                args += parseExpression()
                while (true) {
                    skipWs()
                    if (peek() != ',') break
                    pos++
                    args += parseExpression()
                }
            }
            skipWs()
            if (peek() != ')') throw ParseException("Unclosed function call", start, name.length)
            pos++
            val fn = FUNCTION_IDS[key] ?: throw ParseException("Unknown function '$key'", start)
            if (args.size != 1) throw ParseException("$key expects 1 argument", start)
            return fold(Func(fn, args[0]))
        }

        /**
         * Folds constant subtrees. A subtree that fails (1/0) is kept as is so the
         * error still surfaces from evaluate(), exactly like the interpreter.
         */
        private fun fold(node: Node): Node {
            val children = when (node) {
                is Add -> node.a.constant && node.b.constant
                is Sub -> node.a.constant && node.b.constant
                is Mul -> node.a.constant && node.b.constant
                is Div -> node.a.constant && node.b.constant
                is Pow -> node.a.constant && node.b.constant
                is Neg -> node.a.constant
                is Percent -> node.a.constant
                is Factorial -> node.a.constant
                is Func -> node.arg.constant
                else -> false
            }
            if (!children) return node
            return try {
                Const(node.eval(emptyMap(), true))
            } catch (_: ArithmeticException) {
                node
            }
        }

        private fun peek(): Char? = input.getOrNull(pos)
        private fun skipWs() {
            while (peek()?.isWhitespace() == true) pos++
        }
    }
}

private fun fail(strict: Boolean, message: String): Double =
    if (strict) throw ArithmeticException(message) else Double.NaN

private const val SIN = 0
private const val COS = 1
private const val TAN = 2
private const val COT = 3
private const val ASIN = 4
private const val ACOS = 5
private const val ATAN = 6
private const val LOG = 7
private const val LN = 8
private const val SQRT = 9
private const val ABS = 10
private const val EXP = 11

private val FUNCTION_IDS = mapOf(
    "sin" to SIN, "cos" to COS, "tan" to TAN, "cot" to COT,
    "asin" to ASIN, "acos" to ACOS, "atan" to ATAN,
    "log" to LOG, "ln" to LN, "sqrt" to SQRT, "abs" to ABS, "exp" to EXP,
)
