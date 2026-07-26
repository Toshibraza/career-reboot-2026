package com.nova.core.agent.notifications

/** One notification, reduced to what is worth saying out loud. */
data class NovaNotification(
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
) {
    /** "WhatsApp, Amit: see you at six" */
    fun spoken(): String = buildString {
        append(appLabel)
        if (title.isNotBlank()) append(", ${title.clip(MAX_TITLE)}")
        if (text.isNotBlank()) append(": ${text.clip(MAX_TEXT)}")
    }

    private companion object {
        const val MAX_TITLE = 60
        const val MAX_TEXT = 90

        /**
         * Notification bodies are not written to be heard.
         *
         * A marketing email arrives as three hundred characters of prose, and reading it out
         * buries the message that actually mattered. Cut at a word boundary so it ends on a
         * word rather than mid-syllable.
         */
        fun String.clip(limit: Int): String {
            val flat = replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            if (flat.length <= limit) return flat

            val cut = flat.take(limit)
            val lastSpace = cut.lastIndexOf(' ')
            return (if (lastSpace > limit / 2) cut.take(lastSpace) else cut).trimEnd(',', '.', ' ') + "…"
        }
    }
}

/**
 * The notifications currently on the phone.
 *
 * Read on demand, never stored. Notifications are other people's messages as much as the
 * user's, and a transcript of them sitting in a database is a liability with no matching
 * benefit — nothing Nova does needs yesterday's notifications.
 */
interface NotificationReader {

    /**
     * Whether notifications can be read, and if not, why.
     *
     * Three states rather than a boolean: "not granted" and "granted but the service has not
     * bound yet" need different answers. Collapsing them tells a user to grant access they
     * already granted, which is worse than saying nothing.
     */
    val availability: NotificationAccess

    /** Newest first. Empty when there is nothing, which is different from unavailable. */
    suspend fun current(): List<NovaNotification>
}

enum class NotificationAccess {
    READY,

    /** The user has not granted notification access. */
    NOT_GRANTED,

    /** Granted, but the listener has not bound yet — usually a moment after a restart. */
    NOT_CONNECTED,
}

/**
 * Turns a list into one spoken line.
 *
 * Free function rather than a method so it is testable without any Android machinery — this is
 * the part that decides what a user actually hears, and it is worth pinning.
 */
fun List<NovaNotification>.summarise(limit: Int = 5): String {
    if (isEmpty()) return "Nothing new."

    val spoken = take(limit).joinToString(". ") { it.spoken() }
    val remainder = size - limit

    return when {
        remainder > 0 -> "You have $size. $spoken. And $remainder more."
        size == 1 -> spoken
        else -> "You have $size. $spoken"
    }
}
