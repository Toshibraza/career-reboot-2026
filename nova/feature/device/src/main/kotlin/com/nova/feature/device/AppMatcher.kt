package com.nova.feature.device

/**
 * Scores how well a spoken app name matches an installed app's label.
 *
 * Pulled out of [AppRegistry] so it can be unit-tested without a Context — this is the part
 * that gets things wrong, and getting it wrong means launching the wrong app.
 *
 * Matching has to be forgiving, because the input is a speech transcript: "whats app",
 * "what's app" and "whatsapp" are the same request, and the recogniser picks whichever it
 * feels like. Normalisation strips spaces and punctuation so those collapse to one string.
 */
internal object AppMatcher {

    /** Below this, launching the "best" match is more annoying than saying "not found". */
    const val MIN_SCORE = 45

    /**
     * Substring matches need this many characters to count.
     *
     * Without it, an app literally named "X" scores 65 against every query containing an x,
     * so "open zqxwv" opens Twitter. Short labels have to win on an exact match or not at all.
     */
    private const val MIN_SUBSTRING_LENGTH = 4

    fun normalise(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    /** Best match for [query] among [candidates], or null when nothing is close enough. */
    fun <T> best(query: String, candidates: List<T>, label: (T) -> String): T? {
        val needle = normalise(query)
        if (needle.isEmpty()) return null

        return candidates
            .map { it to score(needle, normalise(label(it))) }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first
    }

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
