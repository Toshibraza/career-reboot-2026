package com.nova.core.agent.rules

import com.nova.core.agent.match.FuzzyMatcher

/** Text tidying shared by every rule, kept in one place so rules stay readable. */
internal object Utterance {

    private val GREETING = Regex("^(?:hey|ok|okay|hi)\\s+")
    private val POLITENESS = Regex("\\b(?:please|can you|could you|would you|i want you to|i need you to)\\b")
    private val PUNCTUATION = Regex("[^a-z0-9%+\\-\\s]")
    private val WHITESPACE = Regex("\\s+")

    /** Lowercases, drops the wake word, filler and punctuation, and collapses whitespace. */
    fun normalise(raw: String, wakeWord: String = DEFAULT_WAKE_WORD): String = raw
        .lowercase()
        .replace(GREETING, "")
        .dropWakeWord(wakeWord)
        .replace(POLITENESS, " ")
        .replace(PUNCTUATION, " ")
        .replace(WHITESPACE, " ")
        .trim()

    /**
     * Removes a leading wake word, however the recogniser spelled it.
     *
     * Scored rather than matched against a list, for the same reason the detector is: "Raza"
     * comes back as "razor" or "rasa", and a command that was recognised but not stripped is
     * just as broken as one that was never heard — "razor open youtube" would try to launch an
     * app called "razor open youtube".
     */
    private fun String.dropWakeWord(wakeWord: String): String {
        val words = trim().split(' ')
        val first = words.firstOrNull()?.filter(Char::isLetterOrDigit).orEmpty()
        if (first.isEmpty()) return this

        val score = FuzzyMatcher.score(first, FuzzyMatcher.normalise(wakeWord))
        return if (score >= WAKE_SCORE) words.drop(1).joinToString(" ") else this
    }

    /** Matches the detector's threshold — see `GatedWakeWordDetector`. */
    private const val WAKE_SCORE = 35

    private const val DEFAULT_WAKE_WORD = "raza"
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
