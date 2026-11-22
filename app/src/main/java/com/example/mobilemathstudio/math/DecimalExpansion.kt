package com.example.mobilemathstudio.math

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Digits of a value produced on demand.
 *
 * Rationals are expanded by exact long division, so a repeating tail never runs
 * out however far the user scrolls; anything else (π, e, roots) is handed to the
 * CAS, which answers with a finite — but generous — number of digits.
 */
object DecimalExpansion {

    /** Largest expansion the CAS is asked for. */
    const val CAS_LIMIT = 4000

    /** Long division can go further — this is only a sanity cap for the scroller. */
    const val RATIONAL_LIMIT = 50_000

    data class Digits(
        val negative: Boolean,
        val whole: String,
        val fraction: String,
        /** No further digits exist, or none can be produced. */
        val complete: Boolean,
    ) {
        fun render(): String = buildString {
            if (negative) append('-')
            append(whole)
            if (fraction.isNotEmpty()) {
                append('.')
                append(fraction)
            }
        }
    }

    private val RATIONAL = Regex("""^\(?([+-]?\d+)\)?\s*/\s*\(?(\d+)\)?$""")
    private val DECIMAL = Regex("""^([+-]?\d+)(?:[.,](\d+))?$""")

    fun of(value: String, digits: Int): Digits? = try {
        val text = value.trim()
        rationalOf(text)?.let { (numerator, denominator) ->
            return divide(numerator, denominator, digits.coerceAtMost(RATIONAL_LIMIT))
        }
        fromCas(text, digits)
    } catch (_: Throwable) {
        null
    }

    /** Parses `a/b`, an integer or a decimal literal into an exact fraction. */
    fun rationalOf(value: String): Pair<BigInteger, BigInteger>? {
        val text = value.trim()
        RATIONAL.matchEntire(text)?.let { match ->
            val denominator = BigInteger(match.groupValues[2])
            if (denominator.signum() == 0) return null
            return BigInteger(match.groupValues[1].trimStart('+')) to denominator
        }
        DECIMAL.matchEntire(text)?.let { match ->
            val fraction = match.groupValues[2]
            val digits = match.groupValues[1].trimStart('+') + fraction
            return BigInteger(digits) to BigInteger.TEN.pow(fraction.length)
        }
        return null
    }

    /** True when the fraction never terminates, i.e. it has a repeating period. */
    fun repeats(denominator: BigInteger): Boolean {
        var rest = denominator.abs()
        if (rest.signum() == 0) return false
        for (factor in listOf(BigInteger.valueOf(2), BigInteger.valueOf(5))) {
            while (rest.mod(factor).signum() == 0) rest /= factor
        }
        return rest != BigInteger.ONE
    }

    private fun divide(numerator: BigInteger, denominator: BigInteger, digits: Int): Digits {
        val top = numerator.abs()
        val bottom = denominator.abs()
        var rest = top.mod(bottom)
        val tail = StringBuilder()
        var produced = 0
        while (produced < digits && rest.signum() != 0) {
            rest = rest.multiply(BigInteger.TEN)
            tail.append(rest / bottom)
            rest = rest.mod(bottom)
            produced++
        }
        return Digits(
            negative = numerator.signum() < 0,
            whole = (top / bottom).toString(),
            fraction = tail.toString(),
            complete = rest.signum() == 0 || produced >= RATIONAL_LIMIT,
        )
    }

    private fun fromCas(value: String, digits: Int): Digits? {
        val wanted = digits.coerceAtMost(CAS_LIMIT)
        val text = SymjaEngine.highPrecision(value, wanted + 2) ?: return null
        val decimal = runCatching { BigDecimal(text) }.getOrNull()
            ?: runCatching { BigDecimal(SymjaEngine.sanitizeNumeric(text) ?: return null) }.getOrNull()
            ?: return null
        val plain = decimal.abs().toPlainString()
        val fraction = plain.substringAfter('.', "").take(wanted)
        return Digits(
            negative = decimal.signum() < 0,
            whole = plain.substringBefore('.'),
            fraction = fraction,
            // Nothing more to ask for once the CAS stops widening its answer.
            complete = fraction.length < wanted,
        )
    }
}
