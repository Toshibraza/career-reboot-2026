package com.nova.core.agent.memory

/**
 * How long ago a fact was recorded, phrased for saying out loud.
 *
 * Raza stores when it was told something but has never said so, which means "your parking spot
 * is B4" sounds identical whether that was noted ten minutes ago or last winter. Confidence
 * without a date is the failure mode worth fixing: the fact is not wrong, but acting on it
 * might be, and only the user knows which.
 *
 * Borrowed from OpenHarness, which labels every memory it injects with its age for the same
 * reason — a stale note presented as current is worse than no note.
 */
object Freshness {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY
    private const val MONTH = 30 * DAY
    private const val YEAR = 365 * DAY

    /**
     * Anything newer than this is said without a qualifier.
     *
     * A fact from this morning is self-evidently current, and tagging every recall with an age
     * would turn a one-line answer into two. The label earns its place only once "still true?"
     * becomes a fair question.
     */
    private const val WORTH_MENTIONING = DAY

    /**
     * Rows written before timestamps meant anything.
     *
     * Nothing in this app existed in 1970, so a value near the epoch is a missing timestamp —
     * an unmigrated row, or a caller that never set one. "I noted that 56 years ago" would be
     * a confident answer to a question the data cannot answer.
     */
    private const val PLAUSIBLE_EPOCH = 1_000_000_000_000L // 2001-09-09

    /** Null when the fact is recent enough, or too old to be a real timestamp. */
    fun describe(updatedAt: Long, now: Long = System.currentTimeMillis()): String? {
        if (updatedAt < PLAUSIBLE_EPOCH) return null

        val age = now - updatedAt

        // A timestamp in the future means a clock change, not a fact from tomorrow. Saying
        // nothing is better than saying something absurd.
        if (age < WORTH_MENTIONING) return null

        return when {
            age < 2 * DAY -> "yesterday"
            age < WEEK -> "${age / DAY} days ago"
            age < 2 * WEEK -> "last week"
            age < MONTH -> "${age / WEEK} weeks ago"
            age < 2 * MONTH -> "last month"
            age < YEAR -> "${age / MONTH} months ago"
            age < 2 * YEAR -> "over a year ago"
            else -> "${age / YEAR} years ago"
        }
    }
}

/**
 * The fact, with when it was recorded if that is worth knowing.
 *
 * Reads as "your gate code is 4471 — I noted that 3 months ago", which leaves the judgement
 * where it belongs. Freshly stored facts get the plain sentence.
 */
fun MemoryEntry.spoken(now: Long = System.currentTimeMillis()): String {
    val age = Freshness.describe(updatedAt, now)
    return if (age == null) "$subject is $detail." else "$subject is $detail — I noted that $age."
}
