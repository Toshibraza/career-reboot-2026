package com.nova.core.agent.vision

/**
 * Turns recognised lines into something worth hearing.
 *
 * Pure, so the part that decides what a user actually hears is testable without a camera, a
 * screen or a model.
 */
object OcrSummary {

    /** Beyond this it stops being listening and starts being endurance. */
    const val SPOKEN_LINES = 12

    /** Shorter than this and it is almost always a stray glyph, an icon label, or noise. */
    private const val MIN_MEANINGFUL = 2

    fun summarise(lines: List<String>, limit: Int = SPOKEN_LINES): String {
        val useful = lines
            .map { it.trim() }
            .filter { it.length >= MIN_MEANINGFUL && it.any(Char::isLetterOrDigit) }

        if (useful.isEmpty()) return "I couldn't find any text."

        val spoken = useful.take(limit).joinToString(". ")
        val remainder = useful.size - limit

        return if (remainder > 0) "$spoken. And $remainder more lines." else spoken
    }
}
