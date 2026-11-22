package com.example.mobilemathstudio.settings

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Display-time rounding for the ≈ decimal toggle. Solver output stays at full
 * engine precision; only the printed string is shortened.
 */
object DecimalDisplay {
    const val DEFAULT_PLACES = 10
    val ALLOWED = 0..15

    fun clamp(places: Int): Int = places.coerceIn(ALLOWED.first, ALLOWED.last)

    fun applyPlaces(text: String, places: Int): String {
        val n = clamp(places)
        return DECIMAL_TOKEN.replace(text) { match ->
            formatOne(match.value, n) ?: match.value
        }
    }

    private fun formatOne(raw: String, places: Int): String? {
        val value = runCatching { BigDecimal(raw) }.getOrNull() ?: return null
        val scaled = value.setScale(places, RoundingMode.HALF_UP)
        var printed = if (places == 0) {
            scaled.toBigInteger().toString()
        } else {
            scaled.stripTrailingZeros().toPlainString()
        }
        if (printed == "-0") printed = "0"
        return printed
    }

    private val DECIMAL_TOKEN = Regex(
        """[-+]?(?:\d+\.\d+|\.\d+)(?:[eE][-+]?\d+)?|[-+]?\d+[eE][-+]?\d+""",
    )
}
