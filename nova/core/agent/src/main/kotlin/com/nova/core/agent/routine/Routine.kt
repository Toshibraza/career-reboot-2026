package com.nova.core.agent.routine

/** A time on a 24-hour clock. */
data class TimeOfDay(val hour: Int, val minute: Int) {

    init {
        require(hour in 0..23) { "hour out of range: $hour" }
        require(minute in 0..59) { "minute out of range: $minute" }
    }

    /** "8:05 am" — for reading back to the user, not for parsing. */
    fun spoken(): String {
        val period = if (hour < 12) "am" else "pm"
        val twelve = when {
            hour % 12 == 0 -> 12
            else -> hour % 12
        }
        return if (minute == 0) "$twelve $period" else "$twelve:%02d $period".format(minute)
    }

    companion object {
        private val EXPLICIT = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b")
        private val TWENTY_FOUR = Regex("\\b(\\d{1,2}):(\\d{2})\\b")
        private val BARE_HOUR = Regex("\\bat (\\d{1,2})\\b")

        /**
         * Reads a time out of an utterance, or null when there isn't one.
         *
         * Handles "8 am", "8:30 pm", "18:00" and a bare "at 8". The bare form is genuinely
         * ambiguous, so it is resolved by the accompanying words — "at 8 in the morning" is
         * 08:00, "at 8 tonight" is 20:00 — and otherwise assumes the nearer waking hour, since
         * someone saying "remind me at 8" over breakfast rarely means eight at night.
         */
        fun parse(text: String): TimeOfDay? {
            val lower = text.lowercase()

            EXPLICIT.find(lower)?.let { match ->
                val rawHour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toIntOrNull() ?: 0
                val isPm = match.groupValues[3] == "pm"
                if (rawHour !in 1..12 || minute !in 0..59) return null

                val hour = when {
                    isPm && rawHour != 12 -> rawHour + 12
                    !isPm && rawHour == 12 -> 0
                    else -> rawHour
                }
                return TimeOfDay(hour, minute)
            }

            TWENTY_FOUR.find(lower)?.let { match ->
                val hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toInt()
                if (hour !in 0..23 || minute !in 0..59) return null
                return TimeOfDay(hour, minute)
            }

            BARE_HOUR.find(lower)?.let { match ->
                val rawHour = match.groupValues[1].toInt()
                if (rawHour !in 0..23) return null

                val eveningWord = Regex("\\b(?:tonight|evening|night|pm)\\b").containsMatchIn(lower)
                val morningWord = Regex("\\b(?:morning|am)\\b").containsMatchIn(lower)

                val hour = when {
                    rawHour > 12 -> rawHour
                    eveningWord && rawHour < 12 -> rawHour + 12
                    morningWord -> rawHour
                    // No hint: 1-6 reads as afternoon, 7-12 as morning. "At 3" is almost never
                    // three in the morning; "at 9" almost always is nine in the morning.
                    rawHour in 1..6 -> rawHour + 12
                    else -> rawHour
                }
                return TimeOfDay(hour % 24, 0)
            }

            return null
        }
    }
}

/** What makes a routine run. */
sealed interface RoutineTrigger {

    /** Every day at this time. */
    data class Daily(val at: TimeOfDay) : RoutineTrigger

    /**
     * Fires once at the next occurrence of [at], then is removed. Reminders are these.
     *
     * A time of day rather than an absolute moment, so the rule that creates it stays pure and
     * testable — resolving "6 pm" to a timestamp needs to know what "now" is, and that belongs
     * with the scheduler, not the parser.
     */
    data class OnceAt(val at: TimeOfDay) : RoutineTrigger
}

/**
 * A trigger plus something to say to Nova.
 *
 * The [command] is stored as the **utterance**, not as parsed actions. A routine created today
 * then benefits from every later improvement to the rule engine, and a stored plan would freeze
 * whatever the parser understood at the moment it was created. It also means the whole existing
 * command vocabulary works inside a routine for free.
 */
data class Routine(
    val id: String,
    val trigger: RoutineTrigger,
    val command: String,
    val createdAt: Long,
)

interface RoutineStore {

    suspend fun add(routine: Routine)

    suspend fun all(): List<Routine>

    suspend fun remove(id: String)

    /** Removes the routine whose command best matches [query], or null when none is close. */
    suspend fun removeMatching(query: String): Routine?
}
