package com.nova.core.agent.memory

/**
 * One thing Nova has been told to remember.
 *
 * [subject] is what the user would ask about later — "my parking spot", "the wifi password" —
 * and [detail] is the answer. Splitting them rather than storing a sentence is what makes
 * recall work: a question names the subject, not the whole original phrasing.
 */
data class MemoryEntry(
    val subject: String,
    val detail: String,
    val updatedAt: Long,
)

/**
 * Long-term memory, kept on the device.
 *
 * Deliberately a plain key-value store rather than embeddings and a vector index. What people
 * actually ask an assistant to remember — where they parked, a door code, someone's birthday —
 * is a subject and a fact, and looking those up is fuzzy string matching over a few dozen rows.
 * A vector store would be a lot of machinery for a problem that does not have it.
 */
interface Memory {

    /** Stores or replaces the fact for [subject]. */
    suspend fun remember(subject: String, detail: String)

    /** Best match for [query], or null when nothing stored is close enough. */
    suspend fun recall(query: String): MemoryEntry?

    /** Everything stored, most recently updated first. */
    suspend fun all(): List<MemoryEntry>

    /** Returns the entry that was removed, or null when nothing matched. */
    suspend fun forget(query: String): MemoryEntry?
}
