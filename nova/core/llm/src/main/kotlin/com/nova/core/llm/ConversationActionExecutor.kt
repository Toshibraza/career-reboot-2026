package com.nova.core.llm

import com.nova.core.agent.ActionExecutor
import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.memory.Memory
import com.nova.core.agent.search.SearchSummary
import com.nova.core.agent.search.WebSearch

/**
 * Talks, rather than operating the phone.
 *
 * Everything else Raza does turns an utterance into an action on the device. This answers
 * questions: what it has been told, what it can look up, and what the model knows. It is the
 * difference between a remote control and an assistant.
 *
 * Three sources, in that order of trust. Stored facts come first because they are the user's own
 * words and no model should overrule them. The web is next, for anything current. The model
 * fills in the rest and is told to admit ignorance rather than invent — a confident wrong answer
 * spoken aloud is worse than "I don't know", because there is nothing on screen to check it
 * against.
 */
class ConversationActionExecutor(
    private val chat: ChatClient,
    private val memory: Memory,
    private val search: WebSearch,
    private val transcript: Transcript = Transcript(),
) : ActionExecutor {

    override val name: String = "conversation"

    override fun canHandle(action: NovaAction): Boolean = action is NovaAction.Converse

    override suspend fun execute(action: NovaAction): ActionResult {
        val question = (action as? NovaAction.Converse)?.utterance?.trim()
            ?: return ActionResult.Unhandled(action)

        if (question.isBlank()) return ActionResult.Failure("I didn't catch that.")

        val known = runCatching { memory.all().map { "${it.subject} is ${it.detail}" } }
            .getOrDefault(emptyList())
            .take(MAX_FACTS)

        val history = transcript.recent()

        val first = runCatching {
            chat.complete(
                system = ConversationPrompt.system(canSearch = search.isConfigured),
                user = ConversationPrompt.user(question, known, history),
            )
        }.getOrElse { return ActionResult.Failure(it.spokenLlmFailure(), it) }

        val answer = if (first.needsSearch()) {
            answerWithSearch(question, known, history, first.searchQuery())
                ?: return ActionResult.Failure("I couldn't look that up just now.")
        } else {
            first.trim()
        }

        if (answer.isBlank()) return ActionResult.Failure("I don't have an answer for that.")

        transcript.record(question, answer)
        return ActionResult.Success(answer)
    }

    /**
     * One search, then one more model call. Never a loop.
     *
     * A model that can keep asking will, and every round trip is a person standing there waiting
     * — around twenty seconds a step on the phone's own model. One hop answers the questions
     * worth answering; a second would mostly buy latency.
     */
    private suspend fun answerWithSearch(
        question: String,
        known: List<String>,
        history: List<Turn>,
        query: String,
    ): String? {
        val findings = runCatching { search.search(query.ifBlank { question }) }
            .getOrElse { return null }

        if (findings.isEmpty()) return null

        return runCatching {
            chat.complete(
                system = ConversationPrompt.system(canSearch = false),
                user = ConversationPrompt.user(
                    question = question,
                    known = known,
                    history = history,
                    findings = SearchSummary.spoken(query, findings, limit = findings.size),
                ),
            ).trim()
        }.getOrNull()
    }

    private fun String.needsSearch(): Boolean =
        trim().startsWith(ConversationPrompt.SEARCH_PREFIX, ignoreCase = true)

    private fun String.searchQuery(): String =
        trim().removePrefix(ConversationPrompt.SEARCH_PREFIX)
            .removePrefix(ConversationPrompt.SEARCH_PREFIX.lowercase())
            .trim()
            .trim('"')

    private companion object {
        /**
         * Stored facts included in the prompt.
         *
         * Everything the user has told Raza is a small list today, but it grows without bound
         * and the prompt does not. Recency order comes from [Memory.all].
         */
        const val MAX_FACTS = 20
    }
}
