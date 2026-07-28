package com.nova.core.llm

/** One exchange. Kept as a pair so the transcript reads back as a conversation. */
data class Turn(val user: String, val raza: String)

/**
 * The last few exchanges, so a follow-up makes sense.
 *
 * "What about Germany?" is not a question anyone can answer without the one before it. This is
 * what makes Raza a conversation rather than a series of unrelated commands.
 *
 * Deliberately short and deliberately not persisted. Spoken conversation moves on quickly, every
 * remembered turn is tokens spent on a paid API or seconds spent on a phone CPU, and an
 * assistant that recalls last Tuesday's small talk after a reboot is unsettling rather than
 * clever. Things worth keeping go in [com.nova.core.agent.memory.Memory], which the user
 * controls explicitly.
 */
class Transcript(private val capacity: Int = DEFAULT_CAPACITY) {

    private val turns = ArrayDeque<Turn>()

    fun record(user: String, raza: String) {
        if (user.isBlank() || raza.isBlank()) return
        turns.addLast(Turn(user, raza))
        while (turns.size > capacity) turns.removeFirst()
    }

    fun recent(): List<Turn> = turns.toList()

    fun clear() = turns.clear()

    private companion object {
        /**
         * Enough for a topic to survive a couple of follow-ups, short enough that a long
         * session does not slowly become an expensive prompt.
         */
        const val DEFAULT_CAPACITY = 6
    }
}

/**
 * Prompts for conversation. Pure, so what Raza is asked can be tested without a model.
 */
object ConversationPrompt {

    /** How the model asks for a search. Checked as a prefix, so it must be unmistakable. */
    const val SEARCH_PREFIX = "SEARCH:"

    fun system(canSearch: Boolean): String = buildString {
        appendLine("You are Raza, a voice assistant on the user's Android phone.")
        appendLine()
        appendLine("Your reply is read aloud, so:")
        appendLine("- Answer in one to three sentences. Never use lists, headings or markdown.")
        appendLine("- Write numbers and symbols as they are spoken.")
        appendLine("- No preamble. Answer the question directly.")
        appendLine("- Never use emoji or URLs; they are unintelligible out loud.")
        appendLine()
        appendLine("If you do not know something, say so plainly rather than inventing it.")

        if (canSearch) {
            appendLine()
            appendLine("If answering needs current information you do not have — news, prices,")
            appendLine("scores, weather, anything recent — reply with exactly:")
            appendLine("$SEARCH_PREFIX <what to search for>")
            appendLine("and nothing else. You will be given results and asked again.")
            appendLine("Do not use $SEARCH_PREFIX for things you already know.")
        }
    }

    fun user(
        question: String,
        known: List<String>,
        history: List<Turn>,
        findings: String? = null,
    ): String = buildString {
        if (known.isNotEmpty()) {
            appendLine("What you have been told about this user:")
            known.forEach { appendLine("- $it") }
            appendLine()
        }

        if (history.isNotEmpty()) {
            appendLine("Recent conversation:")
            history.forEach {
                appendLine("User: ${it.user}")
                appendLine("You: ${it.raza}")
            }
            appendLine()
        }

        if (findings != null) {
            appendLine("Web results:")
            appendLine(findings)
            appendLine()
            appendLine("Answer using these. Do not ask to search again.")
            appendLine()
        }

        append("User: ")
        append(question)
    }
}
