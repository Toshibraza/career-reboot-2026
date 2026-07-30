package com.nova.core.llm

import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.memory.Memory
import com.nova.core.agent.memory.MemoryEntry
import com.nova.core.agent.search.SearchResult
import com.nova.core.agent.search.WebSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationActionExecutorTest {

    /** Replays scripted replies and records every prompt it was given. */
    private class ScriptedChat(private vararg val replies: String) : ChatClient {
        val systems = mutableListOf<String>()
        val users = mutableListOf<String>()
        val schemas = mutableListOf<ResponseSchema?>()
        var calls = 0
            private set

        override suspend fun complete(system: String, user: String, schema: ResponseSchema?): String {
            systems += system
            users += user
            schemas += schema
            return replies.getOrElse(calls++) { "" }
        }
    }

    private class FailingChat(private val cause: Throwable) : ChatClient {
        override suspend fun complete(system: String, user: String, schema: ResponseSchema?): String = throw cause
    }

    private class FakeMemory(private val entries: List<MemoryEntry> = emptyList()) : Memory {
        override suspend fun remember(subject: String, detail: String) = Unit
        override suspend fun recall(query: String): MemoryEntry? = null
        override suspend fun all(): List<MemoryEntry> = entries
        override suspend fun forget(query: String): MemoryEntry? = null
    }

    private class FakeSearch(
        override val isConfigured: Boolean = true,
        private val results: List<SearchResult> = emptyList(),
    ) : WebSearch {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String, limit: Int): List<SearchResult> {
            queries += query
            return results
        }
    }

    private fun answer(
        chat: ChatClient,
        memory: Memory = FakeMemory(),
        search: WebSearch = FakeSearch(isConfigured = false),
        transcript: Transcript = Transcript(),
        question: String = "what is the capital of France",
    ): ActionResult = runBlocking {
        ConversationActionExecutor(chat, memory, search, transcript)
            .execute(NovaAction.Converse(question))
    }

    @Test
    fun `answers a question in one round trip`() {
        val chat = ScriptedChat("Paris is the capital of France.")

        val result = answer(chat)

        assertEquals(1, chat.calls)
        assertEquals("Paris is the capital of France.", (result as ActionResult.Success).spoken)
    }

    @Test
    fun `conversation asks for free text, not the planner's JSON`() {
        // The regression this guards: a transport that attached the planner's response schema
        // to every request forced conversation replies into {"decision":...} JSON, which was
        // then spoken aloud verbatim.
        val chat = ScriptedChat("Paris.")

        answer(chat)

        assertEquals(listOf(null), chat.schemas)
    }

    @Test
    fun `stored facts are offered to the model`() {
        val chat = ScriptedChat("Your spot is B4.")
        val memory = FakeMemory(listOf(MemoryEntry("my parking spot", "B4", updatedAt = 1)))

        answer(chat, memory = memory, question = "where did I park again")

        assertTrue(chat.users.single(), "my parking spot is B4" in chat.users.single())
    }

    @Test
    fun `a follow-up sees the exchange before it`() {
        // The whole point of a transcript. "What about Germany?" is not answerable alone.
        val transcript = Transcript()
        val chat = ScriptedChat("Paris.", "Berlin.")

        answer(chat, transcript = transcript, question = "what is the capital of France")
        answer(chat, transcript = transcript, question = "what about Germany")

        val second = chat.users[1]
        assertTrue(second, "capital of France" in second)
        assertTrue(second, "Paris." in second)
    }

    @Test
    fun `the model can ask for a search and is then given the results`() {
        val chat = ScriptedChat("SEARCH: rain in Bengaluru today", "It's expected to rain today.")
        val search = FakeSearch(
            results = listOf(SearchResult("Bengaluru weather", "Showers expected.", "http://x")),
        )

        val result = answer(chat, search = search, question = "will it rain today")

        assertEquals(listOf("rain in Bengaluru today"), search.queries)
        assertEquals(2, chat.calls)
        assertEquals("It's expected to rain today.", (result as ActionResult.Success).spoken)
        assertTrue(chat.users[1], "Showers expected." in chat.users[1])
    }

    @Test
    fun `the second call cannot ask to search again`() {
        // One hop, never a loop. Every round trip is someone standing there waiting, and on
        // the phone's own model that is around twenty seconds each.
        val chat = ScriptedChat("SEARCH: anything", "SEARCH: more please")
        val search = FakeSearch(results = listOf(SearchResult("t", "s", "u")))

        answer(chat, search = search)

        assertEquals(2, chat.calls)
        assertEquals(1, search.queries.size)
        assertFalse(chat.systems[1], ConversationPrompt.SEARCH_PREFIX in chat.systems[1])
    }

    @Test
    fun `search is not offered when no token is configured`() {
        val chat = ScriptedChat("I don't know.")

        answer(chat, search = FakeSearch(isConfigured = false))

        // Offering an escape hatch that cannot work would make the model announce a search
        // that never happens.
        assertFalse(chat.systems.single(), ConversationPrompt.SEARCH_PREFIX in chat.systems.single())
    }

    @Test
    fun `a failed search is reported rather than answered blind`() {
        val chat = ScriptedChat("SEARCH: something current")

        val result = answer(chat, search = FakeSearch(results = emptyList()))

        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `a model failure keeps its own diagnosis`() {
        // "My OpenAI account is out of credit" must survive, or the real problem becomes
        // undiagnosable — which is exactly what happened the first time this ran live.
        val chat = FailingChat(OpenAiHttpException(429, "insufficient_quota"))

        val result = answer(chat)

        assertEquals("My OpenAI account is out of credit.", (result as ActionResult.Failure).spoken)
    }

    @Test
    fun `a failed exchange is not remembered`() {
        val transcript = Transcript()
        answer(FailingChat(OpenAiHttpException(401, "bad key")), transcript = transcript)

        assertTrue(transcript.recent().isEmpty())
    }

    @Test
    fun `an empty answer is not passed off as one`() {
        val result = answer(ScriptedChat("   "))

        assertTrue(result is ActionResult.Failure)
    }
}
