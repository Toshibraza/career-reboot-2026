package com.nova.core.agent.match

/**
 * Scores how well a spoken phrase matches a piece of on-screen or on-device text.
 *
 * Used for two things that look different but are the same problem: resolving "whats app" to an
 * installed app, and resolving "tap send" to a button labelled "Send". Both take a speech
 * transcript — imprecisely spaced, occasionally misheard — and have to pick one target or
 * honestly pick none.
 *
 * Lives in :core:agent rather than beside either caller because it holds no Android types, and
 * because getting it wrong means launching the wrong app or tapping the wrong button.
 */
object FuzzyMatcher {

    /** Below this, acting on the "best" match is worse than admitting nothing matched. */
    const val MIN_SCORE = 45

    /**
     * How clearly the winner must beat the runner-up before it counts as a match.
     *
     * A tie used to be decided by list order — "open paytm" on a phone with Paytm and
     * Paytm Money installed launched whichever the package manager listed first, and the
     * same coin-flip applied to tapping between two near-identical buttons. Declining an
     * ambiguous match costs the user one clarifying sentence; guessing wrong costs a tap
     * on the wrong control. An exact match is exempt: saying the label in full is as
     * unambiguous as speech gets.
     */
    const val AMBIGUITY_MARGIN = 10

    /**
     * Substring matches need this many characters to count.
     *
     * Without it, an app literally named "X" scores against every query containing an x, so
     * "open zqxwv" opens Twitter. Short labels have to win on an exact match or not at all.
     */
    private const val MIN_SUBSTRING_LENGTH = 4

    fun normalise(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    /** Best match for [query] among [candidates], or null when nothing is close enough. */
    fun <T> best(query: String, candidates: List<T>, label: (T) -> String): T? {
        val needle = normalise(query)
        if (needle.isEmpty()) return null

        val ranked = candidates
            .map { it to score(needle, normalise(label(it))) }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }

        val top = ranked.firstOrNull() ?: return null
        if (top.second == 100) return top.first

        // Close scores mean the transcript does not decide between them, and neither should
        // list order.
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && top.second - runnerUp.second < AMBIGUITY_MARGIN) return null

        return top.first
    }

    /** [needle] and [hay] must already be normalised. */
    fun score(needle: String, hay: String): Int = when {
        hay.isEmpty() -> 0
        hay == needle -> 100
        needle.length >= MIN_SUBSTRING_LENGTH && hay.startsWith(needle) -> 85
        needle.length >= MIN_SUBSTRING_LENGTH && hay.contains(needle) -> 70
        hay.length >= MIN_SUBSTRING_LENGTH && needle.contains(hay) -> 65
        else -> similarity(needle, hay)
    }

    /** 0..60, from normalised edit distance. Rescues near-misses, never invents a match. */
    private fun similarity(a: String, b: String): Int {
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 0
        val ratio = 1.0 - editDistance(a, b).toDouble() / longest
        return (ratio * 60).toInt()
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
