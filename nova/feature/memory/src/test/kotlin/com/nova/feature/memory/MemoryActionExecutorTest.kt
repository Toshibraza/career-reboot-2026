package com.nova.feature.memory

import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.match.FuzzyMatcher
import com.nova.core.agent.memory.Memory
import com.nova.core.agent.memory.MemoryEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryActionExecutorTest {

    /** In-memory stand-in matching SqliteMemory's semantics, including fuzzy recall. */
    private class FakeMemory : Memory {
        private val entries = mutableMapOf<String, MemoryEntry>()
        private var clock = 0L

        override suspend fun remember(subject: String, detail: String) {
            entries[subject] = MemoryEntry(subject, detail, clock++)
        }

        override suspend fun recall(query: String): MemoryEntry? =
            FuzzyMatcher.best(query, entries.values.toList()) { it.subject }

        override suspend fun all(): List<MemoryEntry> =
            entries.values.sortedByDescending { it.updatedAt }

        override suspend fun forget(query: String): MemoryEntry? =
            recall(query)?.also { entries.remove(it.subject) }
    }

    private val memory = FakeMemory()
    private val executor = MemoryActionExecutor(memory)

    private fun run(action: NovaAction): ActionResult = runBlocking { executor.execute(action) }

    private fun spokenOf(action: NovaAction): String? =
        (run(action) as? ActionResult.Success)?.spoken

    @Test
    fun `stores and reads back a fact`() {
        run(NovaAction.Remember("my parking spot", "B2"))
        assertEquals("my parking spot is B2.", spokenOf(NovaAction.Recall("parking spot")))
    }

    @Test
    fun `a new value replaces the old one`() {
        // Being told a new parking spot means the old one is wrong, not that there are two.
        run(NovaAction.Remember("my parking spot", "B2"))
        run(NovaAction.Remember("my parking spot", "D4"))

        assertEquals("my parking spot is D4.", spokenOf(NovaAction.Recall("my parking spot")))
        assertEquals(1, runBlocking { memory.all() }.size)
    }

    @Test
    fun `an unknown subject says so rather than guessing`() {
        // Reading out the nearest unrelated fact is worse than admitting nothing matched.
        run(NovaAction.Remember("my parking spot", "B2"))
        val result = run(NovaAction.Recall("the wifi password"))

        assertTrue(result is ActionResult.Failure)
        assertEquals("I don't have anything about the wifi password.", (result as ActionResult.Failure).spoken)
    }

    @Test
    fun `forgetting removes the entry`() {
        run(NovaAction.Remember("the gate code", "4B7x"))
        assertEquals("Forgotten the gate code.", spokenOf(NovaAction.ForgetMemory("gate code")))
        assertTrue(run(NovaAction.Recall("gate code")) is ActionResult.Failure)
    }

    @Test
    fun `forgetting something unknown is not silently treated as success`() {
        assertTrue(run(NovaAction.ForgetMemory("anything")) is ActionResult.Failure)
    }

    @Test
    fun `an empty memory says so`() {
        assertEquals("I haven't been told anything yet.", spokenOf(NovaAction.RecallAll))
    }

    @Test
    fun `a long list is summarised rather than recited`() {
        // Spoken aloud, twenty facts is useless; the count says there is more without
        // reading it all out.
        repeat(8) { index -> run(NovaAction.Remember("thing $index", "value $index")) }

        val spoken = spokenOf(NovaAction.RecallAll).orEmpty()
        assertTrue("And 3 more." in spoken)
        assertTrue("thing 7 is value 7" in spoken)
    }

    @Test
    fun `casing of a stored detail survives`() {
        run(NovaAction.Remember("the gate code", "4B7x"))
        assertTrue("4B7x" in spokenOf(NovaAction.Recall("gate code")).orEmpty())
    }
}
