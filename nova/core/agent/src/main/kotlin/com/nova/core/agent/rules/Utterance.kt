package com.nova.core.agent.rules

/** Text tidying shared by every rule, kept in one place so rules stay readable. */
internal object Utterance {

    private val WAKE_PREFIX = Regex("^(?:hey |ok |okay )?nova[,\\s]+")
    private val POLITENESS = Regex("\\b(?:please|can you|could you|would you|i want you to|i need you to)\\b")
    private val PUNCTUATION = Regex("[^a-z0-9%+\\-\\s]")
    private val WHITESPACE = Regex("\\s+")

    /** Lowercases, drops the wake word, filler and punctuation, and collapses whitespace. */
    fun normalise(raw: String): String = raw
        .lowercase()
        .replace(WAKE_PREFIX, "")
        .replace(POLITENESS, " ")
        .replace(PUNCTUATION, " ")
        .replace(WHITESPACE, " ")
        .trim()
}

/** Spoken numbers, because speech recognisers emit "fifty" as often as "50". */
internal object Numbers {

    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )

    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    private val NAMED = mapOf(
        "hundred" to 100, "full" to 100, "max" to 100, "maximum" to 100,
        "half" to 50, "min" to 0, "minimum" to 0, "zero" to 0, "none" to 0,
    )

    /**
     * Reads the first number in [text], as digits ("50") or words ("fifty", "twenty five").
     * Returns null when there is nothing numeric to find.
     */
    fun firstIn(text: String): Int? {
        Regex("\\d+").find(text)?.let { return it.value.toIntOrNull() }

        val tokens = text.split(' ')
        tokens.forEachIndexed { index, token ->
            TENS[token]?.let { tens ->
                val unit = tokens.getOrNull(index + 1)?.let { UNITS[it] } ?: 0
                return if (unit in 1..9) tens + unit else tens
            }
            UNITS[token]?.let { return it }
            NAMED[token]?.let { return it }
        }
        return null
    }
}
