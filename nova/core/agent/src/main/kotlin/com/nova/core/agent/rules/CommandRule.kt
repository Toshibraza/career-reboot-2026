package com.nova.core.agent.rules

import com.nova.core.agent.AgentContext
import com.nova.core.agent.NovaAction

/**
 * What a rule gets to look at: the whole normalised utterance, the regex hit, and the context.
 *
 * Rules need both halves — [group] to pull out "youtube" from "open youtube", and [text] to ask
 * whether the same sentence also said "off" somewhere the pattern didn't reach.
 */
internal class RuleMatch(
    val text: String,
    /**
     * The utterance as spoken, before normalisation.
     *
     * Rules that dictate content rather than name a target need this: "type hello, how are
     * you?" must keep its punctuation, which [text] has already stripped.
     */
    val raw: String,
    private val match: MatchResult,
    val context: AgentContext,
) {
    fun group(index: Int): String = match.groupValues.getOrElse(index) { "" }

    fun contains(pattern: String): Boolean = Regex(pattern).containsMatchIn(text)

    /**
     * Pulls the tail of the raw utterance after whichever of [leadIns] it starts with, so
     * dictated text survives with its original casing and punctuation.
     */
    fun rawAfter(vararg leadIns: String): String? {
        val trimmed = raw.trim()
        val lowered = trimmed.lowercase()
        for (leadIn in leadIns) {
            val prefix = "$leadIn "
            val at = lowered.indexOf(prefix)
            if (at >= 0) return trimmed.substring(at + prefix.length).trim().ifEmpty { null }
        }
        return null
    }
}

/**
 * One pattern and what to do when it fires.
 *
 * [build] may return null to decline a match it can't make sense of — "set volume to" with no
 * number, say — and the engine keeps looking. That lets a broad pattern stay broad without
 * swallowing utterances it would only mishandle.
 */
internal class CommandRule(
    val id: String,
    private val pattern: Regex,
    private val build: (RuleMatch) -> List<NovaAction>?,
) {
    fun apply(text: String, raw: String, context: AgentContext): List<NovaAction>? {
        val match = pattern.find(text) ?: return null
        return build(RuleMatch(text, raw, match, context))
    }
}

internal fun rule(
    id: String,
    pattern: String,
    build: (RuleMatch) -> List<NovaAction>?,
) = CommandRule(id, Regex(pattern), build)

internal fun simpleRule(id: String, pattern: String, action: NovaAction) =
    CommandRule(id, Regex(pattern)) { listOf(action) }
